package com.liuli.btchat.data

import android.content.Context
import com.liuli.btchat.LiuliApp
import com.liuli.btchat.core.ChatStore
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's chat database, backed by SQLite (`liuli.db`, schema v1 — see [LiuliDb]).
 *
 * Owner: `data-store`. [Store] is the only [ChatStore] the rest of the app sees;
 * every screen reads through it and only the engine writes.
 *
 * ## Threading
 * One [LiuliDb] instance is opened lazily on the first call and every operation
 * runs inside a single monitor, so a read-modify-write sequence (an unread
 * counter, a message plus its conversation summary) can never interleave with
 * another thread. The queries are index-backed and tiny, which is why the frozen
 * synchronous contract is served directly on the calling thread.
 *
 * ## Degradation
 * A database that cannot be opened or used (no storage permission, full disk,
 * corrupt file, downgraded schema) must never take the UI down with it:
 * the first failure snapshots whatever is on disk into [MemoryFallback] and the
 * process keeps running on memory for the rest of its lifetime. Writes still
 * work, the UI keeps refreshing, and nothing is dropped mid-session — the only
 * loss is persistence across restarts, which is reported through
 * [persistenceError].
 */
object Store : ChatStore {

    /** How many recent messages the in-memory snapshot keeps when the database dies. */
    private const val SNAPSHOT_MESSAGES = 2_000

    private val rev = MutableStateFlow(0L)

    /** Bumped after every write so Compose screens can re-read cheaply. */
    override val revision: StateFlow<Long> = rev

    /** Serialises all database *and* fallback access. */
    private val lock = Any()

    @Volatile
    private var helper: LiuliDb? = null

    @Volatile
    private var openAttempted = false

    @Volatile
    private var error: String? = null

    /** True once the SQLite file was found unusable and the process moved to [MemoryFallback]. */
    internal val usingMemoryFallback: Boolean
        get() = openAttempted && helper == null

    /** Class + message of the failure that triggered the fallback, or null while SQLite is healthy. */
    internal val persistenceError: String?
        get() = error

    // ------------------------------------------------------------ plumbing

    private fun bump() {
        rev.value = rev.value + 1
    }

    /** [LiuliApp.instance] is lateinit; touching it too early must not be fatal. */
    private fun contextOrNull(): Context? = try {
        LiuliApp.instance
    } catch (_: Throwable) {
        null
    }

    /**
     * Opens the helper once. Returns null while the fallback is in charge — either
     * because the application context does not exist yet (a later call retries) or
     * because the database already failed (permanent for this process).
     */
    private fun openLocked(): LiuliDb? {
        helper?.let { return it }
        if (openAttempted) return null
        val ctx = contextOrNull() ?: return null
        openAttempted = true
        return try {
            LiuliDb(ctx).also {
                it.writableDatabase // force creation now, inside the try
                helper = it
            }
        } catch (t: Exception) {
            degrade(t)
            null
        }
    }

    /** Switches permanently to [MemoryFallback], keeping a best-effort copy of the stored data. */
    private fun degrade(cause: Exception) {
        if (error == null) {
            error = cause::class.java.simpleName + ": " + (cause.message ?: "数据库不可用")
        }
        val broken = helper
        helper = null
        if (broken != null) {
            snapshotInto(broken)
            runCatching { broken.close() }
        }
    }

    /**
     * Copies the database into [MemoryFallback] so a mid-session failure does not
     * blank the UI. All-or-nothing: a read that fails leaves the fallback empty
     * rather than half-populated.
     */
    private fun snapshotInto(source: LiuliDb) {
        runCatching {
            val conversations = source.conversations()
            val messages = source.recentMessages(SNAPSHOT_MESSAGES)
            val contacts = source.contacts()
            val members = conversations.associate { it.id to source.members(it.id) }
            MemoryFallback.restore(conversations, messages, contacts, members)
        }
    }

    /** Runs [work] on SQLite, or [memory] when persistence is unavailable. */
    private inline fun <T> guarded(memory: () -> T, work: (LiuliDb) -> T): T = synchronized(lock) {
        val db = openLocked()
        if (db == null) {
            memory()
        } else {
            try {
                work(db)
            } catch (t: Exception) {
                degrade(t)
                memory()
            }
        }
    }

    /** [guarded] for a mutation: the revision always advances, whichever backend ran. */
    private inline fun write(memory: () -> Unit, work: (LiuliDb) -> Unit) {
        guarded(memory, work)
        bump()
    }

    // -------------------------------------------------------- conversations

    override fun conversations(): List<Conversation> =
        guarded(MemoryFallback::conversations, LiuliDb::conversations)

    override fun conversation(id: String): Conversation? =
        guarded({ MemoryFallback.conversation(id) }) { it.conversation(id) }

    override fun saveConversation(c: Conversation) {
        write({ MemoryFallback.saveConversation(c) }) { it.saveConversation(c) }
    }

    /**
     * Removing a conversation has to take its media with it.
     *
     * This used to delete only rows. Of the half-dozen places that call this
     * (删除会话, 退群, 解散群, 被移出群聊, 删除好友…), exactly one remembered to
     * delete the attachments first, so every other path left the pictures,
     * videos and voice notes in `files/media` forever — the rows that held their
     * paths are gone, so nothing can ever find them again.
     *
     * Doing it here fixes all callers at once, and it must happen *before* the
     * rows go: afterwards there is no way to know which files belonged to this
     * conversation.
     */
    override fun deleteConversation(id: String) {
        runCatching {
            messages(id, limit = Int.MAX_VALUE).forEach { m ->
                m.attachment?.let { att -> runCatching { Svc.media.deleteAttachmentFiles(att) } }
            }
        }
        write({ MemoryFallback.deleteConversation(id) }) { it.deleteConversation(id) }
    }

    /**
     * Same reasoning as [deleteConversation], for 清空聊天记录.
     *
     * `messages()` defaults to a window of 400, which silently skipped the
     * attachments of anything older — so a long conversation kept its oldest
     * media on disk while the rows were deleted.
     */
    override fun clearHistory(convId: String) {
        runCatching {
            messages(convId, limit = Int.MAX_VALUE).forEach { m ->
                m.attachment?.let { att -> runCatching { Svc.media.deleteAttachmentFiles(att) } }
            }
        }
        write({ MemoryFallback.clearHistory(convId) }) { it.clearHistory(convId) }
    }

    override fun setDraft(convId: String, draft: String) {
        write({ MemoryFallback.setDraft(convId, draft) }) { it.setDraft(convId, draft) }
    }

    override fun totalUnread(): Int = guarded(MemoryFallback::totalUnread, LiuliDb::totalUnread)

    override fun markConversationRead(convId: String) {
        write({ MemoryFallback.markConversationRead(convId) }) { it.markConversationRead(convId) }
    }

    // ------------------------------------------------------------- messages

    override fun messages(convId: String, limit: Int): List<Message> =
        guarded({ MemoryFallback.messages(convId, limit) }) { it.messages(convId, limit) }

    override fun latestMessage(convId: String): Message? =
        guarded({ MemoryFallback.latestMessage(convId) }) { it.latestMessage(convId) }

    override fun message(id: String): Message? =
        guarded({ MemoryFallback.message(id) }) { it.message(id) }

    override fun saveMessage(m: Message) {
        write({ MemoryFallback.saveMessage(m) }) { it.saveMessage(m) }
    }

    override fun deleteMessage(id: String) {
        write({ MemoryFallback.deleteMessage(id) }) { it.deleteMessage(id) }
    }

    override fun setMessageState(id: String, state: MsgState) {
        write({ MemoryFallback.setMessageState(id, state) }) { it.setMessageState(id, state) }
    }

    override fun setMessageProgress(
        id: String,
        transferred: Long,
        progress: Float,
        state: TransferState,
        localPath: String?,
        thumbPath: String?
    ) {
        write({
            MemoryFallback.setMessageProgress(id, transferred, progress, state, localPath, thumbPath)
        }) {
            it.setMessageProgress(id, transferred, progress, state, localPath, thumbPath)
        }
    }

    override fun searchMessages(query: String, limit: Int): List<Message> =
        guarded({ MemoryFallback.searchMessages(query, limit) }) { it.searchMessages(query, limit) }

    override fun recallMessage(id: String) {
        write({ MemoryFallback.recallMessage(id) }) { it.recallMessage(id) }
    }

    override fun setStarred(id: String, starred: Boolean) {
        write({ MemoryFallback.setStarred(id, starred) }) { it.setStarred(id, starred) }
    }

    override fun starredMessages(limit: Int): List<Message> =
        guarded({ MemoryFallback.starredMessages(limit) }) { it.starredMessages(limit) }

    override fun starredCount(): Int = guarded(MemoryFallback::starredCount, LiuliDb::starredCount)

    // ------------------------------------------------------------- contacts

    override fun contacts(): List<Contact> = guarded(MemoryFallback::contacts, LiuliDb::contacts)

    override fun contact(deviceId: String): Contact? =
        guarded({ MemoryFallback.contact(deviceId) }) { it.contact(deviceId) }

    override fun saveContact(c: Contact) {
        write({ MemoryFallback.saveContact(c) }) { it.saveContact(c) }
    }

    override fun deleteContact(deviceId: String) {
        write({ MemoryFallback.deleteContact(deviceId) }) { it.deleteContact(deviceId) }
    }

    // -------------------------------------------------------------- members

    override fun members(groupId: String): List<Member> =
        guarded({ MemoryFallback.members(groupId) }) { it.members(groupId) }

    override fun setMembers(groupId: String, members: List<Member>) {
        write({ MemoryFallback.setMembers(groupId, members) }) { it.setMembers(groupId, members) }
    }

    override fun addMember(groupId: String, m: Member) {
        write({ MemoryFallback.addMember(groupId, m) }) { it.addMember(groupId, m) }
    }

    override fun removeMember(groupId: String, memberId: String) {
        write({ MemoryFallback.removeMember(groupId, memberId) }) { it.removeMember(groupId, memberId) }
    }

    override fun groupIds(): List<String> = guarded(MemoryFallback::groupIds, LiuliDb::groupIds)
}

/**
 * Pure-Kotlin twin of [Store], used when the SQLite file cannot be opened and as
 * the behavioural reference for the whole [ChatStore] contract.
 *
 * It is deliberately free of Android imports so it can be exercised by plain JVM
 * unit tests, and it mirrors [LiuliDb] on every observable rule:
 *  - the same sort orders (pinned/time for conversations, time for messages —
 *    insertion order as the tie-break on both sides);
 *  - the same summary update in [saveMessage];
 *  - the same recall tombstone and the same recap of the conversation preview;
 *  - the same newest-first ordering of [starredMessages];
 *  - the same member-count bookkeeping;
 *  - the same cleanup of a deleted conversation's messages and members.
 *
 * Not thread safe on its own: in production it only runs under `Store`'s lock.
 */
internal object MemoryFallback : ChatStore {

    /** Upper bound for a single read, matching [LiuliDb]. */
    private const val MAX_ROWS = 10_000

    private val rev = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = rev

    private val convs = LinkedHashMap<String, Conversation>()
    private val msgs = LinkedHashMap<String, Message>()
    private val contactsById = LinkedHashMap<String, Contact>()
    private val membersByGroup = LinkedHashMap<String, MutableList<Member>>()

    private fun bump() {
        rev.value = rev.value + 1
    }

    /** Drops every row. Used by tests and when a snapshot would start from scratch. */
    fun clear() {
        convs.clear()
        msgs.clear()
        contactsById.clear()
        membersByGroup.clear()
        bump()
    }

    /**
     * Replaces the whole state with [conversations], [messages], [contacts] and
     * [members] — the snapshot [Store] takes right before it degrades to memory.
     */
    fun restore(
        conversations: List<Conversation>,
        messages: List<Message>,
        contacts: List<Contact>,
        members: Map<String, List<Member>>
    ) {
        convs.clear()
        msgs.clear()
        contactsById.clear()
        membersByGroup.clear()
        conversations.forEach { convs[it.id] = it }
        messages.forEach { msgs[it.id] = it }
        contacts.forEach { contactsById[it.deviceId] = it }
        members.forEach { (groupId, list) -> membersByGroup[groupId] = list.distinctBy { it.deviceId }.toMutableList() }
        bump()
    }

    // -------------------------------------------------------- conversations

    override fun conversations(): List<Conversation> =
        convs.values.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageAt }
        )

    override fun conversation(id: String): Conversation? = convs[id]

    override fun saveConversation(c: Conversation) {
        convs[c.id] = c
        bump()
    }

    /**
     * Removes the conversation with its messages and, for a group, its roster —
     * the same cleanup [LiuliDb.deleteConversation] performs, so no orphan rows
     * survive a `leaveGroup` / `dissolveGroup`.
     */
    override fun deleteConversation(id: String) {
        convs.remove(id)
        msgs.values.removeAll { it.convId == id }
        membersByGroup.remove(id)
        bump()
    }

    override fun clearHistory(convId: String) {
        msgs.values.removeAll { it.convId == convId }
        convs[convId]?.let { convs[convId] = it.copy(lastPreview = "", unread = 0) }
        bump()
    }

    override fun setDraft(convId: String, draft: String) {
        convs[convId]?.let { convs[convId] = it.copy(draft = draft) }
        bump()
    }

    override fun totalUnread(): Int = convs.values.sumOf { it.unread }

    // ------------------------------------------------------------- messages

    override fun messages(convId: String, limit: Int): List<Message> {
        val n = limit.coerceIn(0, MAX_ROWS)
        if (n == 0) return emptyList()
        return msgs.values.filter { it.convId == convId }.sortedBy { it.sentAt }.takeLast(n)
    }

    override fun latestMessage(convId: String): Message? {
        var best: Message? = null
        for (m in msgs.values) {
            if (m.convId != convId) continue
            if (best == null || m.sentAt >= best.sentAt) best = m
        }
        return best
    }

    override fun message(id: String): Message? = msgs[id]

    /**
     * Stores the message and refreshes the owning conversation in one step:
     * `last_at` only ever moves forward, `last_preview` follows this message, and
     * a received non-system message raises `unread` by one.
     *
     * Note the contract this shares with [LiuliDb]: the unread counter is raised by
     * every save of an inbound message, so the engine must update an existing
     * inbound message through [setMessageState] / [setMessageProgress] instead of
     * saving it again.
     */
    override fun saveMessage(m: Message) {
        msgs[m.id] = m
        convs[m.convId]?.let { c ->
            convs[m.convId] = c.copy(
                lastMessageAt = maxOf(c.lastMessageAt, m.sentAt),
                lastPreview = m.preview,
                unread = if (!m.outgoing && m.kind != MsgKind.SYSTEM) c.unread + 1 else c.unread
            )
        }
        bump()
    }

    override fun deleteMessage(id: String) {
        msgs.remove(id)
        bump()
    }

    override fun setMessageState(id: String, state: MsgState) {
        msgs[id]?.let { msgs[id] = it.copy(state = state) }
        bump()
    }

    override fun setMessageProgress(
        id: String,
        transferred: Long,
        progress: Float,
        state: TransferState,
        localPath: String?,
        thumbPath: String?
    ) {
        msgs[id]?.let { m ->
            val a = m.attachment ?: return@let
            msgs[id] = m.copy(
                attachment = a.copy(
                    transferred = transferred,
                    progress = progress,
                    state = state,
                    localPath = localPath ?: a.localPath,
                    thumbPath = thumbPath ?: a.thumbPath
                )
            )
        }
        bump()
    }

    override fun markConversationRead(convId: String) {
        convs[convId]?.let { convs[convId] = it.copy(unread = 0) }
        bump()
    }

    override fun searchMessages(query: String, limit: Int): List<Message> {
        val n = limit.coerceIn(0, MAX_ROWS)
        if (n == 0) return emptyList()
        val found = ArrayList<Message>()
        for (m in msgs.values) if (m.text.contains(query, ignoreCase = true)) found.add(m)
        found.reverse() // newest insertion first, matching LiuliDb's `rowid DESC` tie-break
        found.sortByDescending { it.sentAt }
        return found.take(n)
    }

    /**
     * Tombstones the message in place, exactly like [LiuliDb.recallMessage]: the
     * row keeps its identity and ordering, the payload (`text`, `attachment`) is
     * dropped, and `starred` survives so a kept message stays in 我的收藏.
     *
     * The conversation summary is then recomputed from the newest message, so
     * recalling the last bubble turns the chat list entry into "[已撤回]" while a
     * recall in the middle of a history leaves it alone.
     */
    override fun recallMessage(id: String) {
        msgs[id]?.let { m ->
            msgs[id] = m.copy(recalled = true, text = "", attachment = null)
            convs[m.convId]?.let { c ->
                latestMessage(m.convId)?.let { latest ->
                    convs[m.convId] = c.copy(lastPreview = latest.preview)
                }
            }
        }
        bump()
    }

    override fun setStarred(id: String, starred: Boolean) {
        msgs[id]?.let { msgs[id] = it.copy(starred = starred) }
        bump()
    }

    override fun starredMessages(limit: Int): List<Message> {
        val n = limit.coerceIn(0, MAX_ROWS)
        if (n == 0) return emptyList()
        val found = ArrayList<Message>()
        for (m in msgs.values) if (m.starred) found.add(m)
        found.reverse() // matching LiuliDb's `sent_at DESC, rowid DESC`
        found.sortByDescending { it.sentAt }
        return found.take(n)
    }

    override fun starredCount(): Int = msgs.values.count { it.starred }

    // ------------------------------------------------------------- contacts

    override fun contacts(): List<Contact> = contactsById.values.sortedBy { it.display }

    override fun contact(deviceId: String): Contact? = contactsById[deviceId]

    override fun saveContact(c: Contact) {
        contactsById[c.deviceId] = c
        bump()
    }

    override fun deleteContact(deviceId: String) {
        contactsById.remove(deviceId)
        bump()
    }

    // -------------------------------------------------------------- members

    override fun members(groupId: String): List<Member> = membersByGroup[groupId].orEmpty()

    override fun setMembers(groupId: String, members: List<Member>) {
        membersByGroup[groupId] = members.distinctBy { it.deviceId }.toMutableList()
        syncMemberCount(groupId)
        bump()
    }

    override fun addMember(groupId: String, m: Member) {
        val list = membersByGroup.getOrPut(groupId) { mutableListOf() }
        val at = list.indexOfFirst { it.deviceId == m.deviceId }
        if (at >= 0) list[at] = m else list.add(m)
        syncMemberCount(groupId)
        bump()
    }

    override fun removeMember(groupId: String, memberId: String) {
        membersByGroup[groupId]?.removeAll { it.deviceId == memberId }
        syncMemberCount(groupId)
        bump()
    }

    override fun groupIds(): List<String> =
        convs.values.filter { it.kind == ConvKind.GROUP }.map { it.id }

    /** Keeps `conversations.memberCount` equal to the stored roster, as the SQL path does. */
    private fun syncMemberCount(groupId: String) {
        convs[groupId]?.let { convs[groupId] = it.copy(memberCount = membersByGroup[groupId]?.size ?: 0) }
    }
}
