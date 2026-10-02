package com.liuli.btchat.data

import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Role
import com.liuli.btchat.core.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the persistence layer.
 *
 * SQLite is not available on a plain JVM, so the executable half of the contract
 * is [MemoryFallback] — the same backend [Store] runs on when `liuli.db` cannot
 * be opened. It is the behavioural twin of [LiuliDb], so every rule asserted here
 * (summary updates, unread counters, member counts, ordering, search) is the rule
 * the SQL path implements too. The column codecs ([AttachmentCodec],
 * [StringSetCodec]) and the `LIKE` escape builder ([SqlLike]) are pure functions
 * and are covered directly.
 *
 * The last case runs [Store] itself with no Android context at all, which is
 * exactly the situation in which the process must degrade instead of throwing.
 */
class StoreContractTest {

    @Before
    fun resetFallback() {
        MemoryFallback.clear()
    }

    // ------------------------------------------------------------- codecs

    @Test
    fun attachment_roundTrip_keepsEveryField() {
        val original = Attachment(
            transferId = "t-1",
            kind = MsgKind.IMAGE,
            fileName = "照片 \"quoted\" \\ slash.jpg",
            mime = "image/jpeg",
            size = 123_456_789L,
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            width = 1920,
            height = 1080,
            durationMs = 4_321L,
            localPath = "/data/user/0/com.liuli.btchat/files/media/t-1__photo.jpg",
            thumbPath = "/data/user/0/com.liuli.btchat/files/thumbs/t-1.jpg",
            thumbB64 = "/9j/4AAQSkZJRgABAQ\u2028\"quoted\" 中文 🎈\n",
            progress = 0.375f,
            state = TransferState.TRANSFERRING,
            transferred = 987_654L
        )

        val blob = AttachmentCodec.encode(original)
        assertNotNull(blob)
        // Must be a real JSON object: a naive JsonPrimitive.toString() render would
        // drop the quotes and the round trip below would not survive.
        assertTrue(blob!!.startsWith("{"))
        assertTrue(blob.contains("\"fileName\""))
        assertTrue(blob.endsWith("}"))

        assertEquals(original, AttachmentCodec.decode(blob))
    }

    @Test
    fun attachment_roundTrip_usesModelDefaults() {
        val minimal = Attachment(
            transferId = "t-2",
            kind = MsgKind.FILE,
            fileName = "a.bin",
            mime = "application/octet-stream",
            size = 0L
        )
        assertEquals(minimal, AttachmentCodec.decode(AttachmentCodec.encode(minimal)))
    }

    @Test
    fun attachment_nullAndGarbageDecodeToNull() {
        assertNull(AttachmentCodec.encode(null))
        assertNull(AttachmentCodec.decode(null))
        assertNull(AttachmentCodec.decode(""))
        assertNull(AttachmentCodec.decode("   "))
        assertNull(AttachmentCodec.decode("not json at all"))
        assertNull(AttachmentCodec.decode("[1,2,3]"))
    }

    @Test
    fun attachment_ignoresUnknownKeysAndFillsDefaults() {
        val decoded = AttachmentCodec.decode("""{"transferId":"t-3","kind":"VIDEO","futureField":42}""")
        assertNotNull(decoded)
        assertEquals("t-3", decoded!!.transferId)
        assertEquals(MsgKind.VIDEO, decoded.kind)
        assertEquals("", decoded.fileName)
        assertEquals(TransferState.IDLE, decoded.state)
        assertEquals(0L, decoded.transferred)
        // An unknown enum name degrades to the documented fallback instead of throwing.
        assertEquals(MsgKind.FILE, AttachmentCodec.decode("""{"kind":"NOPE"}""")!!.kind)
    }

    @Test
    fun attachmentDto_mapsToModelAndBack() {
        val model = Attachment(
            transferId = "t-4",
            kind = MsgKind.VIDEO,
            fileName = "clip.mp4",
            mime = "video/mp4",
            size = 10L,
            width = 640,
            height = 480,
            durationMs = 5_000L,
            state = TransferState.DONE,
            transferred = 10L
        )
        val dto = AttachmentDto.of(model)
        assertEquals(model, dto.toModel())
        assertEquals(dto, AttachmentDto.of(dto.toModel()))
        assertEquals(model, AttachmentCodec.decodeDto(AttachmentCodec.encode(dto)).toModel())
    }

    @Test
    fun readBySet_roundTripsIncludingCommasAndUnicode() {
        val ids = setOf("a,b", "设备-1", "", "  spaced  ", "c\"d")
        assertEquals(ids, StringSetCodec.decode(StringSetCodec.encode(ids)))

        assertEquals(emptySet<String>(), StringSetCodec.decode(null))
        assertEquals(emptySet<String>(), StringSetCodec.decode("[]"))
        assertEquals(emptySet<String>(), StringSetCodec.decode("   "))
        // Legacy comma-separated data written before the JSON column existed.
        assertEquals(setOf("x", "y"), StringSetCodec.decode("x,y"))
        assertEquals(setOf("x"), StringSetCodec.decode("x,"))
    }

    @Test
    fun sqlLike_escapesWildcardsAndTheEscapeCharacter() {
        assertEquals("%100\\%%", SqlLike.containsPattern("100%"))
        assertEquals("%a\\_b%", SqlLike.containsPattern("a_b"))
        assertEquals("%c\\\\d%", SqlLike.containsPattern("c\\d"))
        assertEquals("%\\\\%", SqlLike.containsPattern("\\"))
        assertEquals("%中文%", SqlLike.containsPattern("中文"))
        assertEquals("%%", SqlLike.containsPattern(""))
    }

    // ------------------------------------------------------- conversations

    @Test
    fun conversations_arePinnedFirstThenNewest() {
        MemoryFallback.saveConversation(newConversation("plain", lastMessageAt = 100L))
        MemoryFallback.saveConversation(newConversation("newest", lastMessageAt = 500L))
        MemoryFallback.saveConversation(newConversation("pinned", lastMessageAt = 1L, pinned = true))

        assertEquals(
            listOf("pinned", "newest", "plain"),
            MemoryFallback.conversations().map { it.id }
        )
    }

    @Test
    fun saveMessage_updatesSummaryAndUnreadForInboundText() {
        MemoryFallback.saveConversation(newConversation("c1", lastMessageAt = 10L))

        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "你好", at = 20L))

        val c = MemoryFallback.conversation("c1")!!
        assertEquals(20L, c.lastMessageAt)
        assertEquals("你好", c.lastPreview)
        assertEquals(1, c.unread)

        // An older message must never move `last_at` backwards.
        MemoryFallback.saveMessage(newMessage("m2", "c1", text = "旧", at = 5L, outgoing = true))
        val after = MemoryFallback.conversation("c1")!!
        assertEquals(20L, after.lastMessageAt)
        assertEquals(1, after.unread)
        assertEquals(1, MemoryFallback.totalUnread())
    }

    @Test
    fun saveMessage_usesKindPreviewForMediaAndSystem() {
        MemoryFallback.saveConversation(newConversation("c1"))

        MemoryFallback.saveMessage(
            newMessage("m1", "c1", kind = MsgKind.IMAGE, at = 1L, attachment = newAttachment())
        )
        assertEquals("[图片]", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals(1, MemoryFallback.conversation("c1")!!.unread)

        // A system notice becomes the preview but never raises the unread badge.
        MemoryFallback.saveMessage(newMessage("m2", "c1", kind = MsgKind.SYSTEM, text = "对方已加入", at = 2L))
        assertEquals("对方已加入", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals(2L, MemoryFallback.conversation("c1")!!.lastMessageAt)
        assertEquals(1, MemoryFallback.conversation("c1")!!.unread)
    }

    @Test
    fun saveMessage_ignoresUnreadForOutgoingAndUnknownConversations() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "hi", at = 1L, outgoing = true))
        assertEquals(0, MemoryFallback.conversation("c1")!!.unread)

        // Saving into a conversation that does not exist yet is allowed.
        MemoryFallback.saveMessage(newMessage("m2", "ghost", text = "?", at = 2L))
        assertNotNull(MemoryFallback.message("m2"))
        assertNull(MemoryFallback.conversation("ghost"))
    }

    @Test
    fun markConversationRead_resetsUnreadAndTotalUnreadSums() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveConversation(newConversation("c2"))
        repeat(2) { MemoryFallback.saveMessage(newMessage("a$it", "c1", at = it.toLong())) }
        repeat(3) { MemoryFallback.saveMessage(newMessage("b$it", "c2", at = it.toLong())) }
        assertEquals(5, MemoryFallback.totalUnread())

        MemoryFallback.markConversationRead("c1")

        assertEquals(0, MemoryFallback.conversation("c1")!!.unread)
        assertEquals(3, MemoryFallback.totalUnread())
        MemoryFallback.markConversationRead("missing") // no-op, must not throw
    }

    @Test
    fun setDraft_isPersistedPerConversation() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.setDraft("c1", "草稿内容")
        assertEquals("草稿内容", MemoryFallback.conversation("c1")!!.draft)
        MemoryFallback.setDraft("missing", "x") // no-op
        assertNull(MemoryFallback.conversation("missing"))
    }

    // ------------------------------------------------------------ messages

    @Test
    fun messages_returnNewestWindowOldestFirst() {
        MemoryFallback.saveConversation(newConversation("c1"))
        for (i in 1..10) MemoryFallback.saveMessage(newMessage("m$i", "c1", text = "t$i", at = i * 10L))

        assertEquals(listOf("m8", "m9", "m10"), MemoryFallback.messages("c1", 3).map { it.id })
        assertEquals(10, MemoryFallback.messages("c1", 100).size)
        assertTrue(MemoryFallback.messages("c1", 0).isEmpty())
        assertTrue(MemoryFallback.messages("other", 5).isEmpty())
    }

    @Test
    fun latestMessageAndLookupById() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", at = 10L))
        MemoryFallback.saveMessage(newMessage("m2", "c1", at = 30L))
        MemoryFallback.saveMessage(newMessage("m3", "c1", at = 20L))

        assertEquals("m2", MemoryFallback.latestMessage("c1")!!.id)
        assertNull(MemoryFallback.latestMessage("empty"))
        assertEquals("m1", MemoryFallback.message("m1")!!.id)
        assertNull(MemoryFallback.message("missing"))
        assertEquals(MsgState.SENT, MemoryFallback.message("m1")!!.state)
    }

    @Test
    fun messageStateAndTransferProgress_areUpdated() {
        val att = newAttachment(state = TransferState.TRANSFERRING, transferred = 10L)
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(
            newMessage("m1", "c1", kind = MsgKind.IMAGE, at = 1L, outgoing = true, attachment = att)
        )

        MemoryFallback.setMessageState("m1", MsgState.DELIVERED)
        assertEquals(MsgState.DELIVERED, MemoryFallback.message("m1")!!.state)
        MemoryFallback.setMessageState("missing", MsgState.READ) // no-op

        MemoryFallback.setMessageProgress(
            "m1", 100L, 1f, TransferState.DONE, null, null
        )
        val updated = MemoryFallback.message("m1")!!.attachment!!
        assertEquals(100L, updated.transferred)
        assertEquals(1f, updated.progress)
        assertEquals(TransferState.DONE, updated.state)
        assertEquals("/tmp/photo.jpg", updated.localPath) // null keeps the stored path
        assertEquals("/tmp/photo.jpg.thumb", updated.thumbPath)

        MemoryFallback.setMessageProgress("m1", 55L, 0.5f, TransferState.TRANSFERRING, "/tmp/other.jpg", null)
        assertEquals("/tmp/other.jpg", MemoryFallback.message("m1")!!.attachment!!.localPath)

        // A message without an attachment ignores progress updates.
        MemoryFallback.saveMessage(newMessage("m2", "c1", at = 2L, outgoing = true))
        MemoryFallback.setMessageProgress("m2", 1L, 1f, TransferState.DONE, null, null)
        assertNull(MemoryFallback.message("m2")!!.attachment)
    }

    @Test
    fun clearHistoryAndDeleteConversation_dropMessages() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "one", at = 1L))
        MemoryFallback.saveMessage(newMessage("m2", "c1", text = "two", at = 2L))

        MemoryFallback.clearHistory("c1")
        assertTrue(MemoryFallback.messages("c1", 50).isEmpty())
        assertEquals("", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals(0, MemoryFallback.conversation("c1")!!.unread)

        MemoryFallback.saveMessage(newMessage("m3", "c1", text = "three", at = 3L))
        MemoryFallback.deleteConversation("c1")
        assertNull(MemoryFallback.conversation("c1"))
        assertNull(MemoryFallback.message("m3"))
        assertTrue(MemoryFallback.messages("c1", 50).isEmpty())
    }

    @Test
    fun searchMessages_isCaseInsensitiveNewestFirstAndLimited() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "Hello world", at = 1L))
        MemoryFallback.saveMessage(newMessage("m2", "c1", text = "hello again", at = 2L))
        MemoryFallback.saveMessage(newMessage("m3", "c1", text = "无关内容", at = 3L))

        assertEquals(listOf("m2", "m1"), MemoryFallback.searchMessages("HELLO", 10).map { it.id })
        assertEquals(listOf("m2"), MemoryFallback.searchMessages("hello", 1).map { it.id })
        assertTrue(MemoryFallback.searchMessages("hello", 0).isEmpty())
        assertTrue(MemoryFallback.searchMessages("nothing-matches", 10).isEmpty())
        assertEquals(3, MemoryFallback.searchMessages("", 10).size)
    }

    // ------------------------------------------------------------ contacts

    @Test
    fun contacts_areSortedByDisplayNameAndDeleted() {
        MemoryFallback.saveContact(Contact("d1", name = "bob", remark = "zed", address = "AA:BB"))
        MemoryFallback.saveContact(Contact("d2", name = "alice"))
        MemoryFallback.saveContact(Contact("d3", name = "carol", remark = "   ")) // blank remark -> name

        assertEquals("zed", MemoryFallback.contact("d1")!!.display)
        assertEquals("carol", MemoryFallback.contact("d3")!!.display)
        assertEquals(listOf("d2", "d3", "d1"), MemoryFallback.contacts().map { it.deviceId })

        MemoryFallback.saveContact(Contact("d2", name = "alice", remark = "AA"))
        assertEquals(3, MemoryFallback.contacts().size) // upsert, not insert
        assertEquals(listOf("d2", "d3", "d1"), MemoryFallback.contacts().map { it.deviceId })

        MemoryFallback.deleteContact("d1")
        assertNull(MemoryFallback.contact("d1"))
        assertEquals(2, MemoryFallback.contacts().size)
    }

    // ------------------------------------------------------------- members

    @Test
    fun members_keepMemberCountInSync() {
        MemoryFallback.saveConversation(newConversation("g1", kind = ConvKind.GROUP))

        MemoryFallback.setMembers("g1", listOf(Member("a", "A", 1), Member("b", "B", 2)))
        assertEquals(2, MemoryFallback.members("g1").size)
        assertEquals(2, MemoryFallback.conversation("g1")!!.memberCount)

        MemoryFallback.addMember("g1", Member("c", "C", 3, role = Role.ADMIN))
        assertEquals(3, MemoryFallback.conversation("g1")!!.memberCount)

        // Re-adding an existing device updates the entry instead of duplicating it.
        MemoryFallback.addMember("g1", Member("c", "C2", 3, online = true))
        assertEquals(3, MemoryFallback.members("g1").size)
        assertEquals("C2", MemoryFallback.members("g1").last().name)
        assertTrue(MemoryFallback.members("g1").last().online)

        // A duplicated deviceId inside one setMembers call is collapsed, like the
        // members(group_id, device_id) primary key does.
        MemoryFallback.setMembers("g1", listOf(Member("a", "A", 1), Member("a", "A2", 1), Member("b", "B", 2)))
        assertEquals(2, MemoryFallback.members("g1").size)
        assertEquals(2, MemoryFallback.conversation("g1")!!.memberCount)

        MemoryFallback.removeMember("g1", "a")
        assertEquals(1, MemoryFallback.conversation("g1")!!.memberCount)
        MemoryFallback.removeMember("g1", "nobody") // no-op

        // Leaving a group drops the roster together with the conversation.
        MemoryFallback.deleteConversation("g1")
        assertTrue(MemoryFallback.members("g1").isEmpty())
    }

    @Test
    fun groupIds_containsOnlyGroupsInCreationOrder() {
        MemoryFallback.saveConversation(newConversation("d1"))
        MemoryFallback.saveConversation(newConversation("g1", kind = ConvKind.GROUP))
        MemoryFallback.saveConversation(newConversation("g2", kind = ConvKind.GROUP))
        assertEquals(listOf("g1", "g2"), MemoryFallback.groupIds())
    }

    // ------------------------------------------ starred messages (task-18)

    /**
     * The user's report behind these tests: "收藏应该就是单独收藏他这一个消息".
     * Starring is strictly per message — one row in, one row out, with the
     * conversation it came from left alone.
     */
    @Test
    fun starringOneMessageStarsExactlyThatMessage() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveConversation(newConversation("c2"))
        for (i in 1..4) MemoryFallback.saveMessage(newMessage("a$i", "c1", text = "A$i", at = i * 10L))
        for (i in 1..2) MemoryFallback.saveMessage(newMessage("b$i", "c2", text = "B$i", at = 100L + i))

        MemoryFallback.setStarred("a2", true)

        assertEquals(listOf("a2"), MemoryFallback.starredMessages(50).map { it.id })
        assertEquals(1, MemoryFallback.starredCount())
        // No neighbour was dragged in: every other message keeps starred = false.
        val others = MemoryFallback.messages("c1", 50) + MemoryFallback.messages("c2", 50)
        assertTrue(others.filter { it.id != "a2" }.none { it.starred })
    }

    @Test
    fun starredMessages_returnsExactlyTheStarredOnesWithTheirOwnConversation() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveConversation(newConversation("c2"))
        for (i in 1..4) MemoryFallback.saveMessage(newMessage("a$i", "c1", text = "A$i", at = i * 10L))
        MemoryFallback.saveMessage(newMessage("b1", "c2", text = "B1", at = 15L))

        MemoryFallback.setStarred("a1", true)
        MemoryFallback.setStarred("a3", true)
        MemoryFallback.setStarred("b1", true)

        val starred = MemoryFallback.starredMessages(50)
        assertEquals(listOf("a3", "b1", "a1"), starred.map { it.id }) // newest first
        assertEquals(3, MemoryFallback.starredCount())
        // Each row must carry the conversation it really belongs to: the 我的收藏
        // screen shows the source with it.
        assertEquals(listOf("c1", "c2", "c1"), starred.map { it.convId })
        assertEquals("A3", starred[0].text)
        assertEquals("B1", starred[1].text)
        assertEquals("A1", starred[2].text)
    }

    @Test
    fun unstarringOneMessageLeavesTheRestUntouched() {
        MemoryFallback.saveConversation(newConversation("c1"))
        for (i in 1..3) MemoryFallback.saveMessage(newMessage("m$i", "c1", text = "t$i", at = i * 10L))
        MemoryFallback.setStarred("m1", true)
        MemoryFallback.setStarred("m2", true)
        MemoryFallback.setStarred("m3", true)
        assertEquals(3, MemoryFallback.starredCount())

        MemoryFallback.setStarred("m2", false)

        assertEquals(listOf("m3", "m1"), MemoryFallback.starredMessages(50).map { it.id })
        assertEquals(2, MemoryFallback.starredCount())
        assertFalse(MemoryFallback.message("m2")!!.starred)
        assertTrue(MemoryFallback.message("m1")!!.starred)
        assertTrue(MemoryFallback.message("m3")!!.starred)
    }

    @Test
    fun starringNeverTouchesConversationsOrContacts() {
        MemoryFallback.saveConversation(newConversation("c1", lastMessageAt = 30L, unread = 2))
        MemoryFallback.saveConversation(newConversation("c2", lastMessageAt = 10L))
        MemoryFallback.saveContact(Contact("peer-1", name = "张三", address = "AA:BB"))
        MemoryFallback.saveContact(Contact("peer-2", name = "李四"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "hi", at = 30L, outgoing = true))

        val conversationsBefore = MemoryFallback.conversations()
        val contactsBefore = MemoryFallback.contacts()

        MemoryFallback.setStarred("m1", true)

        // Starring is a message-level flag: the chat list and the address book are
        // byte-for-byte the same, and no new conversation/contact appeared.
        assertEquals(conversationsBefore, MemoryFallback.conversations())
        assertEquals(contactsBefore, MemoryFallback.contacts())
        assertEquals(2, MemoryFallback.contacts().size)
        assertEquals(2, MemoryFallback.conversations().size)
        assertEquals(2, MemoryFallback.totalUnread())
        assertTrue(MemoryFallback.starredMessages(50).single().starred)
    }

    @Test
    fun starredMessages_isEmptyWithoutStarsAndRespectsTheLimit() {
        MemoryFallback.saveConversation(newConversation("c1"))
        for (i in 1..3) MemoryFallback.saveMessage(newMessage("m$i", "c1", at = i * 10L))
        assertTrue(MemoryFallback.starredMessages(50).isEmpty())
        assertEquals(0, MemoryFallback.starredCount())

        for (i in 1..3) MemoryFallback.setStarred("m$i", true)
        assertEquals(2, MemoryFallback.starredMessages(2).size)
        assertTrue(MemoryFallback.starredMessages(0).isEmpty())

        // Deleting the conversation drops its stars with it.
        MemoryFallback.deleteConversation("c1")
        assertTrue(MemoryFallback.starredMessages(50).isEmpty())
        assertEquals(0, MemoryFallback.starredCount())
    }

    @Test
    fun storeStarringIsPerMessageWithoutAnAndroidContext() {
        Store.saveConversation(newConversation("c1"))
        Store.saveContact(Contact("peer-1", name = "张三"))
        Store.saveMessage(newMessage("m1", "c1", text = "只收藏我", at = 1L))
        Store.saveMessage(newMessage("m2", "c1", text = "别收藏我", at = 2L))

        Store.setStarred("m1", true)

        val starred = Store.starredMessages(50)
        assertEquals(listOf("m1"), starred.map { it.id })
        assertEquals("c1", starred.single().convId)
        assertEquals(1, Store.starredCount())
        assertFalse(Store.message("m2")!!.starred)
        assertEquals(1, Store.contacts().size)
        assertEquals(1, Store.conversations().size)
    }

    // ------------------------------------------------------------ revision

    @Test
    fun revision_advancesOnWritesAndStaysPutOnReads() {
        val start = MemoryFallback.revision.value
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.setDraft("c1", "草稿")
        MemoryFallback.saveContact(Contact("d1", name = "A"))
        val afterWrites = MemoryFallback.revision.value
        assertTrue("revision must move forward", afterWrites > start)

        MemoryFallback.conversations()
        MemoryFallback.contacts()
        MemoryFallback.totalUnread()
        MemoryFallback.messages("c1", 10)
        assertEquals(afterWrites, MemoryFallback.revision.value)
    }

    // ------------------------------------------------------------- restore

    @Test
    fun restore_replacesAllState() {
        MemoryFallback.saveConversation(newConversation("stale"))
        MemoryFallback.saveMessage(newMessage("stale-msg", "stale", at = 1L))

        MemoryFallback.restore(
            conversations = listOf(newConversation("c9", lastMessageAt = 42L)),
            messages = listOf(newMessage("m9", "c9", text = "hi", at = 42L)),
            contacts = listOf(Contact("d9", name = "Nine")),
            members = mapOf("c9" to listOf(Member("d9", "Nine", 9)))
        )

        assertNull(MemoryFallback.conversation("stale"))
        assertNull(MemoryFallback.message("stale-msg"))
        assertEquals(listOf("c9"), MemoryFallback.conversations().map { it.id })
        assertEquals("hi", MemoryFallback.message("m9")!!.text)
        assertEquals("Nine", MemoryFallback.contact("d9")!!.name)
        assertEquals(1, MemoryFallback.members("c9").size)
    }

    // --------------------------------------------------------------- store

    @Test
    fun store_servesTheContractWithoutAnAndroidContext() {
        // In a plain JVM test LiuliApp.instance does not exist, so Store cannot
        // reach SQLite. It must degrade to memory and keep every call working.
        assertTrue(Store.conversations().isEmpty())

        Store.saveConversation(newConversation("c1"))
        Store.saveMessage(newMessage("m1", "c1", text = "hi", at = 7L))

        assertEquals(listOf("c1"), Store.conversations().map { it.id })
        assertEquals("hi", Store.latestMessage("c1")!!.text)
        assertEquals(1, Store.totalUnread())
        assertEquals("c1", Store.message("m1")!!.convId)

        Store.markConversationRead("c1")
        assertEquals(0, Store.totalUnread())

        val before = Store.revision.value
        Store.deleteConversation("c1")
        assertTrue(Store.revision.value > before)
        assertTrue(Store.conversations().isEmpty())
        assertNull(Store.message("m1"))
    }

    // ------------------------------------------------------------- helpers

    private fun newConversation(
        id: String,
        kind: ConvKind = ConvKind.DIRECT,
        title: String = id,
        lastMessageAt: Long = 0L,
        pinned: Boolean = false,
        unread: Int = 0
    ): Conversation = Conversation(
        id = id,
        kind = kind,
        title = title,
        lastMessageAt = lastMessageAt,
        pinned = pinned,
        unread = unread
    )

    private fun newMessage(
        id: String,
        convId: String,
        text: String = "",
        kind: MsgKind = MsgKind.TEXT,
        at: Long = 0L,
        outgoing: Boolean = false,
        attachment: Attachment? = null
    ): Message = Message(
        id = id,
        convId = convId,
        senderId = if (outgoing) "me" else "peer",
        senderName = if (outgoing) "我" else "对方",
        kind = kind,
        text = text,
        attachment = attachment,
        sentAt = at,
        outgoing = outgoing
    )

    private fun newAttachment(
        state: TransferState = TransferState.TRANSFERRING,
        transferred: Long = 0L
    ): Attachment = Attachment(
        transferId = "t-1",
        kind = MsgKind.IMAGE,
        fileName = "photo.jpg",
        mime = "image/jpeg",
        size = 100L,
        localPath = "/tmp/photo.jpg",
        thumbPath = "/tmp/photo.jpg.thumb",
        state = state,
        transferred = transferred
    )
}
