package com.liuli.btchat.data

import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the "WeChat-style" message flags: quote, recall and star.
 *
 * SQLite is not available on a plain JVM, so as in [StoreContractTest] the
 * executable half runs against [MemoryFallback] — the byte-for-byte behavioural
 * twin of [LiuliDb] — plus the pure column codec [QuoteCodec]. Everything
 * asserted here is the rule the SQL path implements as well: the tombstone
 * update, the conversation-preview recap, and newest-first starred ordering.
 */
class MessageFlagsTest {

    @Before
    fun resetFallback() {
        MemoryFallback.clear()
    }

    // ------------------------------------------------------------- QuoteCodec

    @Test
    fun quoteCodec_roundTrip_keepsEveryField() {
        val quote = Quote(
            msgId = "m-1",
            senderName = "张三 \"Z\" \\ 李四",
            preview = "第一行\n第二行 100% 折扣 🎈"
        )

        val blob = QuoteCodec.encode(quote)
        assertNotNull(blob)
        // Real JSON, not a JsonPrimitive.toString() fragment.
        assertTrue(blob!!.startsWith("{"))
        assertTrue(blob.contains("\"msgId\""))
        assertTrue(blob.endsWith("}"))

        assertEquals(quote, QuoteCodec.decode(blob))
    }

    @Test
    fun quoteCodec_nullAndGarbageDecodeToNull() {
        assertNull(QuoteCodec.encode(null))
        assertNull(QuoteCodec.decode(null))
        assertNull(QuoteCodec.decode(""))
        assertNull(QuoteCodec.decode("   "))
        assertNull(QuoteCodec.decode("not json at all"))
        assertNull(QuoteCodec.decode("[1,2,3]"))
        assertNull(QuoteCodec.decode("42"))
    }

    @Test
    fun quoteCodec_ignoresUnknownKeysAndFillsDefaults() {
        val decoded = QuoteCodec.decode("""{"msgId":"m-9","futureField":[1,2]}""")
        assertNotNull(decoded)
        assertEquals("m-9", decoded!!.msgId)
        assertEquals("", decoded.senderName)
        assertEquals("", decoded.preview)
    }

    @Test
    fun quoteDto_mapsToModelAndBack() {
        val model = Quote(msgId = "m-2", senderName = "我", preview = "[图片]")
        val dto = QuoteDto.of(model)
        assertEquals(model, dto.toModel())
        assertEquals(dto, QuoteDto.of(dto.toModel()))
        assertEquals(model, QuoteCodec.decodeDto(QuoteCodec.encode(dto)).toModel())
    }

    // --------------------------------------------------------- model defaults

    @Test
    fun messageDefaults_areNotRecalledNotStarredAndUnquoted() {
        val m = newMessage("m1", "c1", text = "你好")
        assertFalse(m.recalled)
        assertFalse(m.starred)
        assertNull(m.quote)
        assertEquals("你好", m.preview)
    }

    @Test
    fun messagePreview_showsTheTombstoneForEveryKind() {
        for (kind in MsgKind.entries) {
            val m = newMessage("m-$kind", "c1", text = "内容", kind = kind).copy(recalled = true)
            assertEquals("[已撤回]", m.preview)
        }
    }

    @Test
    fun canRecall_onlyCountsOurOwnRecentNonSystemMessages() {
        val now = System.currentTimeMillis()
        assertTrue(newMessage("m1", "c1", outgoing = true, at = now).canRecall)
        assertFalse(newMessage("m2", "c1", outgoing = true, at = now - Message.RECALL_WINDOW_MS - 1).canRecall)
        assertFalse(newMessage("m3", "c1", outgoing = false, at = now).canRecall)
        assertFalse(newMessage("m4", "c1", kind = MsgKind.SYSTEM, outgoing = true, at = now).canRecall)
        assertFalse(newMessage("m5", "c1", outgoing = true, at = now).copy(recalled = true).canRecall)
    }

    // --------------------------------------------------------------- recall

    @Test
    fun recallMessage_tombstonesInPlaceAndClearsThePayload() {
        MemoryFallback.saveConversation(newConversation("c1"))
        val attachment = newAttachment()
        MemoryFallback.saveMessage(
            newMessage(
                "m1", "c1",
                text = "原始内容",
                kind = MsgKind.IMAGE,
                at = 5L,
                outgoing = true,
                attachment = attachment,
                quote = Quote("q1", "对方", "被引用的内容"),
                starred = true
            )
        )

        MemoryFallback.recallMessage("m1")

        val m = MemoryFallback.message("m1")!!
        assertTrue(m.recalled)
        assertEquals("", m.text)
        assertNull(m.attachment)
        assertEquals("[已撤回]", m.preview)
        // The row keeps its identity, ordering, metadata and star flag; only the
        // payload is gone. `quote` is preserved (the tombstone is still a reply).
        assertEquals("m1", m.id)
        assertEquals("c1", m.convId)
        assertEquals(5L, m.sentAt)
        assertTrue(m.outgoing)
        assertTrue(m.starred)
        assertEquals(Quote("q1", "对方", "被引用的内容"), m.quote)
    }

    @Test
    fun recallMessage_turnsTheNewestConversationPreviewIntoTombstone() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "一", at = 1L))
        MemoryFallback.saveMessage(newMessage("m2", "c1", text = "二", at = 2L))
        assertEquals("二", MemoryFallback.conversation("c1")!!.lastPreview)

        MemoryFallback.recallMessage("m2")

        assertEquals("[已撤回]", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals(2L, MemoryFallback.conversation("c1")!!.lastMessageAt)
    }

    @Test
    fun recallMessage_leavesAnOlderConversationPreviewAlone() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "一", at = 1L))
        MemoryFallback.saveMessage(newMessage("m2", "c1", text = "二", at = 2L))

        MemoryFallback.recallMessage("m1")

        assertEquals("二", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals("[已撤回]", MemoryFallback.message("m1")!!.preview)
    }

    @Test
    fun recallMessage_isIdempotentAndUnknownIdsAreNoOps() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "一", at = 1L))

        MemoryFallback.recallMessage("m1")
        MemoryFallback.recallMessage("m1")
        assertTrue(MemoryFallback.message("m1")!!.recalled)
        assertEquals("[已撤回]", MemoryFallback.conversation("c1")!!.lastPreview)

        MemoryFallback.recallMessage("does-not-exist")
        assertEquals("[已撤回]", MemoryFallback.conversation("c1")!!.lastPreview)
        assertEquals(1, MemoryFallback.messages("c1", 50).size)
    }

    @Test
    fun saveMessage_followsTheRecalledPreview() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "还在", at = 1L))
        MemoryFallback.recallMessage("m1")

        // Re-saving the tombstone (a receipt rewrite, for example) must not bring
        // the old preview back.
        MemoryFallback.saveMessage(MemoryFallback.message("m1")!!.copy(readBy = setOf("peer")))
        assertEquals("[已撤回]", MemoryFallback.conversation("c1")!!.lastPreview)
        assertTrue(MemoryFallback.message("m1")!!.recalled)
    }

    // ----------------------------------------------------------------- star

    @Test
    fun setStarred_togglesTheFlagAndKeepsEverythingElse() {
        MemoryFallback.saveConversation(newConversation("c1"))
        MemoryFallback.saveMessage(newMessage("m1", "c1", text = "保留我", at = 1L))

        MemoryFallback.setStarred("m1", true)
        val starred = MemoryFallback.message("m1")!!
        assertTrue(starred.starred)
        assertEquals("保留我", starred.text)

        MemoryFallback.setStarred("m1", false)
        assertFalse(MemoryFallback.message("m1")!!.starred)

        MemoryFallback.setStarred("missing", true) // no-op, must not throw
        assertNull(MemoryFallback.message("missing"))
    }

    @Test
    fun starredMessages_areNewestFirstDeduplicatedAndLimited() {
        MemoryFallback.saveConversation(newConversation("c1"))
        for (i in 1..4) MemoryFallback.saveMessage(newMessage("m$i", "c1", text = "t$i", at = i * 10L))
        MemoryFallback.setStarred("m1", true)
        MemoryFallback.setStarred("m3", true)
        MemoryFallback.setStarred("m4", true)

        assertEquals(listOf("m4", "m3", "m1"), MemoryFallback.starredMessages(10).map { it.id })
        assertEquals(listOf("m4"), MemoryFallback.starredMessages(1).map { it.id })
        assertTrue(MemoryFallback.starredMessages(0).isEmpty())

        MemoryFallback.setStarred("m4", false)
        assertEquals(listOf("m3", "m1"), MemoryFallback.starredMessages(10).map { it.id })
    }

    @Test
    fun starredCount_countsOnlyStarredMessages() {
        MemoryFallback.saveConversation(newConversation("c1"))
        for (i in 1..3) MemoryFallback.saveMessage(newMessage("m$i", "c1", at = i.toLong()))
        assertEquals(0, MemoryFallback.starredCount())

        MemoryFallback.setStarred("m1", true)
        MemoryFallback.setStarred("m2", true)
        assertEquals(2, MemoryFallback.starredCount())

        MemoryFallback.deleteMessage("m1")
        assertEquals(1, MemoryFallback.starredCount())

        MemoryFallback.clearHistory("c1")
        assertEquals(0, MemoryFallback.starredCount())
        assertTrue(MemoryFallback.starredMessages(10).isEmpty())
    }

    @Test
    fun starredRecallSurvivesTheRoundTripThroughTheStore() {
        MemoryFallback.saveConversation(newConversation("c1"))
        val original = newMessage(
            "m1", "c1",
            text = "带引用的消息",
            at = 3L,
            quote = Quote("q1", "对方", "上文"),
            starred = true
        )

        MemoryFallback.saveMessage(original)
        val back = MemoryFallback.message("m1")!!
        assertEquals(original.quote, back.quote)
        assertTrue(back.starred)
        assertFalse(back.recalled)

        // A recall after starring keeps the row in 我的收藏 as a tombstone.
        MemoryFallback.recallMessage("m1")
        assertEquals(listOf("m1"), MemoryFallback.starredMessages(10).map { it.id })
        assertEquals(1, MemoryFallback.starredCount())
    }

    // ---------------------------------------------------------------- store

    @Test
    fun storeServesRecallAndStarWithoutAnAndroidContext() {
        // No LiuliApp.instance on a plain JVM: Store must route through the
        // in-memory twin and keep the new API working.
        Store.saveConversation(newConversation("c1"))
        Store.saveMessage(newMessage("m1", "c1", text = "hello", at = 1L, outgoing = true))
        Store.saveMessage(newMessage("m2", "c1", text = "world", at = 2L, outgoing = true))

        Store.setStarred("m1", true)
        Store.setStarred("m2", true)
        assertEquals(2, Store.starredCount())
        assertEquals(listOf("m2", "m1"), Store.starredMessages(10).map { it.id })

        Store.recallMessage("m2")
        assertTrue(Store.message("m2")!!.recalled)
        assertEquals("[已撤回]", Store.latestMessage("c1")!!.preview)
        assertEquals("[已撤回]", Store.conversation("c1")!!.lastPreview)

        Store.setStarred("m1", false)
        assertEquals(1, Store.starredCount())
    }

    // -------------------------------------------------------------- helpers

    private fun newConversation(id: String, kind: ConvKind = ConvKind.DIRECT): Conversation =
        Conversation(id = id, kind = kind, title = id)

    private fun newMessage(
        id: String,
        convId: String,
        text: String = "",
        kind: MsgKind = MsgKind.TEXT,
        at: Long = 0L,
        outgoing: Boolean = false,
        attachment: Attachment? = null,
        quote: Quote? = null,
        starred: Boolean = false,
        recalled: Boolean = false
    ): Message = Message(
        id = id,
        convId = convId,
        senderId = if (outgoing) "me" else "peer",
        senderName = if (outgoing) "我" else "对方",
        kind = kind,
        text = text,
        attachment = attachment,
        sentAt = at,
        outgoing = outgoing,
        quote = quote,
        starred = starred,
        recalled = recalled
    )

    private fun newAttachment(): Attachment = Attachment(
        transferId = "t-1",
        kind = MsgKind.IMAGE,
        fileName = "photo.jpg",
        mime = "image/jpeg",
        size = 100L,
        localPath = "/tmp/photo.jpg"
    )
}
