package com.liuli.btchat.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Quote
import com.liuli.btchat.core.Role
import com.liuli.btchat.core.TransferState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/*
 * Persistence gateway for the whole app.
 *
 * Owner: `data-store`. [Store] is the only caller; every method here is a thin,
 * allocation-light translation between the frozen domain model in
 * `com.liuli.btchat.core` and the SQLite file `liuli.db`.
 *
 * Rules that hold for every statement in this file:
 *  - values are always bound (`?`), never spliced into SQL text;
 *  - statements that touch more than one row run inside a transaction;
 *  - every cursor is closed through `use { }`.
 */

// ------------------------------------------------------------------- cursor helpers

private fun Cursor.text(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""

private fun Cursor.textOrNull(col: String): String? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getString(i)
}

private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))

private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

private fun Cursor.bool(col: String): Boolean = getInt(getColumnIndexOrThrow(col)) != 0

/** Tolerant `String` -> enum mapping: an unknown name from an older/newer build degrades to [fallback]. */
private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
    raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun String?.toJson(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

// ------------------------------------------------------------------------- codecs

/**
 * Column-level mirror of [Attachment].
 *
 * The model type lives in the frozen `core` package and carries no serialization
 * annotations, so the `messages.attachment` column stores this DTO instead and
 * converts in both directions with [of] / [toModel].
 */
internal data class AttachmentDto(
    val transferId: String = "",
    val kind: String = MsgKind.FILE.name,
    val fileName: String = "",
    val mime: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val localPath: String? = null,
    val thumbPath: String? = null,
    val thumbB64: String? = null,
    val progress: Float = 0f,
    val state: String = TransferState.IDLE.name,
    val transferred: Long = 0L
) {
    fun toModel(): Attachment = Attachment(
        transferId = transferId,
        kind = enumOr(kind, MsgKind.FILE),
        fileName = fileName,
        mime = mime,
        size = size,
        sha256 = sha256,
        width = width,
        height = height,
        durationMs = durationMs,
        localPath = localPath,
        thumbPath = thumbPath,
        thumbB64 = thumbB64,
        progress = progress,
        state = enumOr(state, TransferState.IDLE),
        transferred = transferred
    )

    companion object {
        fun of(a: Attachment): AttachmentDto = AttachmentDto(
            transferId = a.transferId,
            kind = a.kind.name,
            fileName = a.fileName,
            mime = a.mime,
            size = a.size,
            sha256 = a.sha256,
            width = a.width,
            height = a.height,
            durationMs = a.durationMs,
            localPath = a.localPath,
            thumbPath = a.thumbPath,
            thumbB64 = a.thumbB64,
            progress = a.progress,
            state = a.state.name,
            transferred = a.transferred
        )
    }
}

/**
 * JSON codec for the `messages.attachment` column.
 *
 * It deliberately uses the kotlinx-serialization *tree* API instead of
 * `@Serializable` codegen: this module applies no serialization compiler plug-in,
 * so generated serializers do not exist at runtime. For the same reason the
 * element is rendered through `Json.encodeToString(JsonElement.serializer(), …)`
 * — `JsonPrimitive.toString()` would emit an unquoted, invalid fragment.
 *
 * Malformed input never throws: a corrupt blob simply yields "no attachment"
 * so one bad row cannot make a conversation unreadable.
 */
internal object AttachmentCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(attachment: Attachment?): String? = attachment?.let { encode(AttachmentDto.of(it)) }

    fun encode(dto: AttachmentDto): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("transferId", JsonPrimitive(dto.transferId))
            put("kind", JsonPrimitive(dto.kind))
            put("fileName", JsonPrimitive(dto.fileName))
            put("mime", JsonPrimitive(dto.mime))
            put("size", JsonPrimitive(dto.size))
            put("sha256", JsonPrimitive(dto.sha256))
            put("width", JsonPrimitive(dto.width))
            put("height", JsonPrimitive(dto.height))
            put("durationMs", JsonPrimitive(dto.durationMs))
            put("localPath", dto.localPath.toJson())
            put("thumbPath", dto.thumbPath.toJson())
            put("thumbB64", dto.thumbB64.toJson())
            put("progress", JsonPrimitive(dto.progress))
            put("state", JsonPrimitive(dto.state))
            put("transferred", JsonPrimitive(dto.transferred))
        }
    )

    fun decode(raw: String?): Attachment? {
        if (raw.isNullOrBlank()) return null
        return runCatching { decodeDto(raw).toModel() }.getOrNull()
    }

    fun decodeDto(raw: String): AttachmentDto {
        val o = json.parseToJsonElement(raw).jsonObject
        return AttachmentDto(
            transferId = o.text("transferId") ?: "",
            kind = o.text("kind") ?: MsgKind.FILE.name,
            fileName = o.text("fileName") ?: "",
            mime = o.text("mime") ?: "",
            size = o.long("size") ?: 0L,
            sha256 = o.text("sha256") ?: "",
            width = o.int("width") ?: 0,
            height = o.int("height") ?: 0,
            durationMs = o.long("durationMs") ?: 0L,
            localPath = o.text("localPath"),
            thumbPath = o.text("thumbPath"),
            thumbB64 = o.text("thumbB64"),
            progress = o.float("progress") ?: 0f,
            state = o.text("state") ?: TransferState.IDLE.name,
            transferred = o.long("transferred") ?: 0L
        )
    }
}

/**
 * Column-level mirror of [Quote] — what a message is replying to, flattened so a
 * bubble renders without a second lookup.
 *
 * Same role as [AttachmentDto]: the frozen model type carries no serialization
 * annotations, so the `messages.quote` column stores this DTO and converts in
 * both directions. (The wire protocol has its own `core.QuoteDto`; the two never
 * meet because this one never leaves the data package.)
 */
internal data class QuoteDto(
    val msgId: String = "",
    val senderName: String = "",
    val preview: String = ""
) {
    fun toModel(): Quote = Quote(msgId = msgId, senderName = senderName, preview = preview)

    companion object {
        fun of(q: Quote): QuoteDto = QuoteDto(msgId = q.msgId, senderName = q.senderName, preview = q.preview)
    }
}

/**
 * JSON codec for the `messages.quote` column.
 *
 * Mirrors [AttachmentCodec] on purpose: the kotlinx-serialization *tree* API
 * (no `@Serializable` codegen), serialization through
 * `Json.encodeToString(JsonElement.serializer(), …)` because
 * `JsonPrimitive.toString()` emits unquoted fragments, and total tolerance on
 * the way in — malformed JSON yields `null` instead of an exception.
 */
internal object QuoteCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(quote: Quote?): String? = quote?.let { encode(QuoteDto.of(it)) }

    fun encode(dto: QuoteDto): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("msgId", JsonPrimitive(dto.msgId))
            put("senderName", JsonPrimitive(dto.senderName))
            put("preview", JsonPrimitive(dto.preview))
        }
    )

    fun decode(raw: String?): Quote? {
        if (raw.isNullOrBlank()) return null
        return runCatching { decodeDto(raw).toModel() }.getOrNull()
    }

    fun decodeDto(raw: String): QuoteDto {
        val o = json.parseToJsonElement(raw).jsonObject
        return QuoteDto(
            msgId = o.text("msgId") ?: "",
            senderName = o.text("senderName") ?: "",
            preview = o.text("preview") ?: ""
        )
    }
}

/**
 * Codec for the `messages.read_by` column — the set of deviceIds that
 * acknowledged a group message. Stored as a JSON array so that an id containing
 * a comma (device ids arrive from the wire and are not necessarily UUIDs) still
 * round-trips; a plain comma-separated string is accepted as a legacy/degraded
 * input.
 */
internal object StringSetCodec {

    private val json = Json

    fun encode(values: Set<String>): String = json.encodeToString(
        JsonElement.serializer(),
        JsonArray(values.map { JsonPrimitive(it) })
    )

    fun decode(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet()
        }.getOrElse {
            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}

/**
 * `LIKE` helper for [LiuliDb.searchMessages].
 *
 * User text is never spliced into SQL; it is bound as a single parameter, and
 * the pattern builder escapes the metacharacters `%` and `_` plus the escape
 * character itself, so a query of `100%` matches the literal text `100%`
 * instead of "anything".
 */
internal object SqlLike {

    /** The escape character declared by `… LIKE ? ESCAPE '\'`. */
    const val ESCAPE = '\\'

    /** Wraps [query] into the `%…%` pattern used by "contains" search. */
    fun containsPattern(query: String): String {
        val out = StringBuilder(query.length + 2)
        out.append('%')
        for (ch in query) {
            if (ch == ESCAPE || ch == '%' || ch == '_') out.append(ESCAPE)
            out.append(ch)
        }
        out.append('%')
        return out.toString()
    }
}

// ---------------------------------------------------------------------- database

/**
 * `SQLiteOpenHelper` for `liuli.db` (schema version 2).
 *
 * Schema:
 * ```
 *  conversations(id PK, kind, title, peer_id, avatar_seed, last_at, last_preview,
 *                unread, pinned, muted, draft, i_am_owner, member_count)
 *  messages(id PK, conv_id, sender_id, sender_name, kind, text, sent_at, state,
 *           outgoing, read_by, attachment, recalled, starred, quote)
 *  contacts(device_id PK, name, remark, address, avatar_seed, added_at, last_seen)
 *  members(group_id, device_id, name, avatar_seed, role, joined_at, online,
 *          PRIMARY KEY(group_id, device_id))
 *  index messages(conv_id, sent_at)   -- chat window reads
 *  index messages(sent_at)            -- global (search / snapshot) reads
 *  index messages(starred, sent_at)   -- 我的收藏
 * ```
 *
 * `attachment` and `quote` hold JSON (see [AttachmentCodec] / [QuoteCodec]);
 * `read_by` holds a JSON array of deviceIds.
 *
 * Version 1 shipped before recall / quote / star existed; [onUpgrade] adds those
 * three columns in place so an installed database keeps every message.
 *
 * Row order: `rowid` is used as the final tie-break wherever the model implies
 * insertion order, because plain `UPDATE`-then-`INSERT` upserts keep a row's
 * `rowid` stable across saves (an `INSERT OR REPLACE` would not).
 *
 * The helper itself is not thread safe in the "read-modify-write" sense; [Store]
 * serialises every call through one lock, which is also what keeps the
 * message + conversation-summary update atomic for callers.
 */
internal class LiuliDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    // ------------------------------------------------------------- creation

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_CONVERSATIONS)
        db.execSQL(SQL_CREATE_MESSAGES)
        db.execSQL(SQL_CREATE_CONTACTS)
        db.execSQL(SQL_CREATE_MEMBERS)
        db.execSQL(SQL_CREATE_INDEX_CONV_AT)
        db.execSQL(SQL_CREATE_INDEX_AT)
        db.execSQL(SQL_CREATE_INDEX_STARRED)
    }

    /**
     * Incremental schema migration. Users already carry a `liuli.db` from the
     * previous release, so **no table is ever dropped or rebuilt here**: every
     * step is additive DDL that keeps existing rows (and their ids) intact.
     *
     * [SQLiteOpenHelper] runs this method inside its own transaction together
     * with the `user_version` bump, so either all steps land and the version
     * moves to [newVersion], or everything rolls back and the next launch retries
     * from the same [oldVersion]. That is why the steps below simply run in
     * sequence without a transaction of their own.
     *
     * A *downgrade* is deliberately left unhandled: [SQLiteOpenHelper] then
     * throws, [Store] degrades to the in-memory backend, and the newer database
     * file is left untouched instead of being silently rebuilt.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeToV2(db)
    }

    /**
     * v1 → v2: recall / quote / star support.
     *
     * Adds the three columns with `ALTER TABLE`, so every existing message keeps
     * its row (the new columns fall back to their `DEFAULT`, i.e. "not recalled,
     * not starred, no quote"), then creates the index the starred list needs.
     *
     * Each `ADD COLUMN` is guarded by a `PRAGMA table_info` check: the step is
     * idempotent, so a database whose columns already exist (a partially applied
     * migration, or a build that bumped the version without the DDL) upgrades
     * cleanly instead of failing with "duplicate column name" and dropping the
     * whole app onto the in-memory fallback.
     */
    private fun upgradeToV2(db: SQLiteDatabase) {
        if (!hasColumn(db, T_MESSAGES, "recalled")) {
            db.execSQL("ALTER TABLE messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasColumn(db, T_MESSAGES, "starred")) {
            db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasColumn(db, T_MESSAGES, "quote")) {
            db.execSQL("ALTER TABLE messages ADD COLUMN quote TEXT")
        }
        db.execSQL(SQL_CREATE_INDEX_STARRED)
    }

    /** True when [table] already carries [column]. Both names are file constants, never user input. */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameAt = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                if (c.getString(nameAt) == column) return true
            }
            false
        }

    // ---------------------------------------------------------- conversations

    fun conversations(): List<Conversation> =
        readableDatabase.query(
            T_CONVERSATIONS,
            CONV_COLUMNS,
            null,
            null,
            null,
            null,
            "pinned DESC, last_at DESC, rowid ASC"
        ).use { c ->
            val out = ArrayList<Conversation>(c.count)
            while (c.moveToNext()) out.add(readConversation(c))
            out
        }

    fun conversation(id: String): Conversation? =
        readableDatabase.query(T_CONVERSATIONS, CONV_COLUMNS, "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) readConversation(c) else null }

    fun saveConversation(c: Conversation) {
        upsert(T_CONVERSATIONS, "id = ?", arrayOf(c.id), conversationValues(c))
    }

    /** Drops the conversation together with its messages and its member rows. */
    fun deleteConversation(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_MESSAGES, "conv_id = ?", arrayOf(id))
            db.delete(T_MEMBERS, "group_id = ?", arrayOf(id))
            db.delete(T_CONVERSATIONS, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearHistory(convId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_MESSAGES, "conv_id = ?", arrayOf(convId))
            db.update(
                T_CONVERSATIONS,
                ContentValues().apply {
                    put("last_preview", "")
                    put("unread", 0)
                },
                "id = ?",
                arrayOf(convId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setDraft(convId: String, draft: String) {
        writableDatabase.update(
            T_CONVERSATIONS,
            ContentValues().apply { put("draft", draft) },
            "id = ?",
            arrayOf(convId)
        )
    }

    fun totalUnread(): Int =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(unread), 0) FROM conversations", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun markConversationRead(convId: String) {
        writableDatabase.update(
            T_CONVERSATIONS,
            ContentValues().apply { put("unread", 0) },
            "id = ?",
            arrayOf(convId)
        )
    }

    fun groupIds(): List<String> =
        readableDatabase.query(
            T_CONVERSATIONS,
            arrayOf("id"),
            "kind = ?",
            arrayOf(ConvKind.GROUP.name),
            null,
            null,
            "rowid ASC"
        ).use { c ->
            val out = ArrayList<String>(c.count)
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    // --------------------------------------------------------------- messages

    /**
     * Newest [limit] messages of one conversation, returned oldest-first as the
     * chat screen expects. The window is selected with `ORDER BY … DESC LIMIT ?`
     * (index `messages(conv_id, sent_at)`) and reversed in memory, so a long
     * history never loads in full.
     */
    fun messages(convId: String, limit: Int): List<Message> =
        window("conv_id = ?", arrayOf(convId), limit)

    /** Newest [limit] messages across every conversation, oldest-first. */
    fun recentMessages(limit: Int): List<Message> = window(null, null, limit)

    fun latestMessage(convId: String): Message? =
        readableDatabase.query(
            T_MESSAGES,
            MSG_COLUMNS,
            "conv_id = ?",
            arrayOf(convId),
            null,
            null,
            "sent_at DESC, rowid DESC",
            "1"
        ).use { c -> if (c.moveToFirst()) readMessage(c) else null }

    fun message(id: String): Message? = message(writableDatabase, id)

    /**
     * Inserts or updates one message and, in the same transaction, refreshes the
     * owning conversation:
     *  - `last_at` moves forward to the newest timestamp seen (never backwards);
     *  - `last_preview` shows this message's preview;
     *  - `unread` grows by one for a received, non-system message.
     *
     * The contract matches [MemoryFallback] exactly: unread counts every save of
     * a received message, so the engine must update an existing inbound message
     * through [setMessageState] / [setMessageProgress] rather than re-saving it.
     */
    fun saveMessage(m: Message) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsert(T_MESSAGES, "id = ?", arrayOf(m.id), messageValues(m))
            val unread = if (!m.outgoing && m.kind != MsgKind.SYSTEM) 1 else 0
            db.execSQL(
                "UPDATE conversations " +
                    "SET last_at = MAX(last_at, ?), last_preview = ?, unread = unread + ? " +
                    "WHERE id = ?",
                arrayOf<Any>(m.sentAt, m.preview, unread, m.convId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMessage(id: String) {
        writableDatabase.delete(T_MESSAGES, "id = ?", arrayOf(id))
    }

    fun setMessageState(id: String, state: MsgState) {
        writableDatabase.update(
            T_MESSAGES,
            ContentValues().apply { put("state", state.name) },
            "id = ?",
            arrayOf(id)
        )
    }

    /**
     * Updates only the transfer fields of the message's attachment. A message
     * without an attachment, or without a row at all, is left untouched — the
     * same no-op [MemoryFallback] performs. `null` paths keep the stored value.
     */
    fun setMessageProgress(
        id: String,
        transferred: Long,
        progress: Float,
        state: TransferState,
        localPath: String?,
        thumbPath: String?
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val att = message(db, id)?.attachment ?: return
            val updated = att.copy(
                transferred = transferred,
                progress = progress,
                state = state,
                localPath = localPath ?: att.localPath,
                thumbPath = thumbPath ?: att.thumbPath
            )
            db.update(
                T_MESSAGES,
                ContentValues().apply { put("attachment", AttachmentCodec.encode(updated)) },
                "id = ?",
                arrayOf(id)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Tombstones a message in place — used both when the user takes a message
     * back and when a peer's `RecallPacket` arrives.
     *
     * One `UPDATE` sets `recalled = 1` and wipes the payload (`text`, `attachment`)
     * while the row itself — its id, conversation, timestamp, sender, star flag —
     * stays where it is, so bubbles do not jump and both sides keep the same
     * ordering. `starred` is deliberately preserved: what was worth keeping stays
     * in 我的收藏 under its "[已撤回]" tombstone.
     *
     * It runs in a transaction together with the conversation summary refresh:
     * once the recalled message is the newest one, the chat list must read
     * "[已撤回]" as well.
     */
    fun recallMessage(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                T_MESSAGES,
                ContentValues().apply {
                    put("recalled", 1)
                    put("text", "")
                    putNull("attachment")
                },
                "id = ?",
                arrayOf(id)
            )
            refreshConversationPreview(db, id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** "我的收藏" toggle. A missing row is a no-op, matching [MemoryFallback]. */
    fun setStarred(id: String, starred: Boolean) {
        writableDatabase.update(
            T_MESSAGES,
            ContentValues().apply { put("starred", if (starred) 1 else 0) },
            "id = ?",
            arrayOf(id)
        )
    }

    /** Newest-first starred messages, served by `idx_messages_starred`. */
    fun starredMessages(limit: Int): List<Message> {
        val n = clampLimit(limit)
        if (n == 0) return emptyList()
        return readableDatabase.query(
            T_MESSAGES,
            MSG_COLUMNS,
            "starred = 1",
            null,
            null,
            null,
            "sent_at DESC, rowid DESC",
            n.toString()
        ).use { c ->
            val out = ArrayList<Message>(minOf(c.count, n))
            while (c.moveToNext()) out.add(readMessage(c))
            out
        }
    }

    fun starredCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE starred = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Recomputes a conversation's `last_preview` from its newest message.
     *
     * Used after a recall, where only the newest bubble's preview should change
     * (a recall in the middle of a history must not touch the chat list entry).
     * [Message.preview] is the single source of truth, so a recalled newest
     * message yields "[已撤回]" and an untouched one yields exactly what
     * [saveMessage] would have written.
     */
    private fun refreshConversationPreview(db: SQLiteDatabase, messageId: String) {
        val convId = db.query(T_MESSAGES, arrayOf("conv_id"), "id = ?", arrayOf(messageId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
        val latest = db.query(
            T_MESSAGES,
            MSG_COLUMNS,
            "conv_id = ?",
            arrayOf(convId),
            null,
            null,
            "sent_at DESC, rowid DESC",
            "1"
        ).use { c -> if (c.moveToFirst()) readMessage(c) else null } ?: return
        db.update(
            T_CONVERSATIONS,
            ContentValues().apply { put("last_preview", latest.preview) },
            "id = ?",
            arrayOf(convId)
        )
    }

    /** Full-text-ish search over `messages.text`, newest first. */
    fun searchMessages(query: String, limit: Int): List<Message> {
        val n = clampLimit(limit)
        if (n == 0) return emptyList()
        return readableDatabase.query(
            T_MESSAGES,
            MSG_COLUMNS,
            "text LIKE ? ESCAPE '\\'",
            arrayOf(SqlLike.containsPattern(query)),
            null,
            null,
            "sent_at DESC, rowid DESC",
            n.toString()
        ).use { c ->
            val out = ArrayList<Message>(minOf(c.count, n))
            while (c.moveToNext()) out.add(readMessage(c))
            out
        }
    }

    // --------------------------------------------------------------- contacts

    /** Contacts ordered by their displayed name (the model's own [Contact.display] rule). */
    fun contacts(): List<Contact> =
        readableDatabase.query(T_CONTACTS, CONTACT_COLUMNS, null, null, null, null, null)
            .use { c ->
                val out = ArrayList<Contact>(c.count)
                while (c.moveToNext()) out.add(readContact(c))
                out.sortedBy { it.display }
            }

    fun contact(deviceId: String): Contact? =
        readableDatabase.query(T_CONTACTS, CONTACT_COLUMNS, "device_id = ?", arrayOf(deviceId), null, null, null)
            .use { c -> if (c.moveToFirst()) readContact(c) else null }

    fun saveContact(c: Contact) {
        upsert(T_CONTACTS, "device_id = ?", arrayOf(c.deviceId), contactValues(c))
    }

    fun deleteContact(deviceId: String) {
        writableDatabase.delete(T_CONTACTS, "device_id = ?", arrayOf(deviceId))
    }

    // ---------------------------------------------------------------- members

    /** Members of a group in insertion order. */
    fun members(groupId: String): List<Member> =
        readableDatabase.query(
            T_MEMBERS,
            MEMBER_COLUMNS,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "rowid ASC"
        ).use { c ->
            val out = ArrayList<Member>(c.count)
            while (c.moveToNext()) out.add(readMember(c))
            out
        }

    /** Replaces the whole roster in one transaction and syncs `conversations.member_count`. */
    fun setMembers(groupId: String, members: List<Member>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_MEMBERS, "group_id = ?", arrayOf(groupId))
            val unique = members.distinctBy { it.deviceId }
            unique.forEach { db.insertOrThrow(T_MEMBERS, null, memberValues(groupId, it)) }
            syncMemberCount(db, groupId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addMember(groupId: String, m: Member) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsert(T_MEMBERS, "group_id = ? AND device_id = ?", arrayOf(groupId, m.deviceId), memberValues(groupId, m))
            syncMemberCount(db, groupId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removeMember(groupId: String, memberId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_MEMBERS, "group_id = ? AND device_id = ?", arrayOf(groupId, memberId))
            syncMemberCount(db, groupId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- internals

    private fun message(db: SQLiteDatabase, id: String): Message? =
        db.query(T_MESSAGES, MSG_COLUMNS, "id = ?", arrayOf(id), null, null, null)
            .use { c -> if (c.moveToFirst()) readMessage(c) else null }

    /** Shared implementation of [messages] / [recentMessages]. */
    private fun window(selection: String?, args: Array<String>?, limit: Int): List<Message> {
        val n = clampLimit(limit)
        if (n == 0) return emptyList()
        return readableDatabase.query(
            T_MESSAGES,
            MSG_COLUMNS,
            selection,
            args,
            null,
            null,
            "sent_at DESC, rowid DESC",
            n.toString()
        ).use { c ->
            val out = ArrayList<Message>(minOf(c.count, n))
            while (c.moveToNext()) out.add(readMessage(c))
            out.reverse()
            out
        }
    }

    /**
     * `UPDATE` first, `INSERT` when nothing matched: this keeps the row's
     * `rowid` stable while still behaving as an upsert on the primary key. The
     * pair runs in a transaction so a crash can never leave the row half-written,
     * and it nests correctly inside the callers' own transactions.
     */
    private fun upsert(table: String, where: String, whereArgs: Array<String>, values: ContentValues) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.update(table, values, where, whereArgs) == 0) {
                db.insertOrThrow(table, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Keeps `conversations.member_count` equal to the number of stored member rows. */
    private fun syncMemberCount(db: SQLiteDatabase, groupId: String) {
        db.execSQL(
            "UPDATE conversations SET member_count = (SELECT COUNT(*) FROM members WHERE group_id = ?) WHERE id = ?",
            arrayOf<Any>(groupId, groupId)
        )
    }

    private fun clampLimit(limit: Int): Int = limit.coerceIn(0, MAX_ROWS)

    private fun readConversation(c: Cursor): Conversation = Conversation(
        id = c.text("id"),
        kind = enumOr(c.textOrNull("kind"), ConvKind.DIRECT),
        title = c.text("title"),
        peerId = c.textOrNull("peer_id"),
        avatarSeed = c.int("avatar_seed"),
        lastMessageAt = c.long("last_at"),
        lastPreview = c.text("last_preview"),
        unread = c.int("unread"),
        pinned = c.bool("pinned"),
        muted = c.bool("muted"),
        draft = c.text("draft"),
        iAmOwner = c.bool("i_am_owner"),
        memberCount = c.int("member_count")
    )

    private fun readMessage(c: Cursor): Message = Message(
        id = c.text("id"),
        convId = c.text("conv_id"),
        senderId = c.text("sender_id"),
        senderName = c.text("sender_name"),
        kind = enumOr(c.textOrNull("kind"), MsgKind.TEXT),
        text = c.text("text"),
        attachment = AttachmentCodec.decode(c.textOrNull("attachment")),
        sentAt = c.long("sent_at"),
        state = enumOr(c.textOrNull("state"), MsgState.SENT),
        outgoing = c.bool("outgoing"),
        readBy = StringSetCodec.decode(c.textOrNull("read_by")),
        quote = QuoteCodec.decode(c.textOrNull("quote")),
        recalled = c.bool("recalled"),
        starred = c.bool("starred")
    )

    private fun readContact(c: Cursor): Contact = Contact(
        deviceId = c.text("device_id"),
        name = c.text("name"),
        remark = c.text("remark"),
        address = c.text("address"),
        avatarSeed = c.int("avatar_seed"),
        addedAt = c.long("added_at"),
        lastSeen = c.long("last_seen")
    )

    private fun readMember(c: Cursor): Member = Member(
        deviceId = c.text("device_id"),
        name = c.text("name"),
        avatarSeed = c.int("avatar_seed"),
        role = enumOr(c.textOrNull("role"), Role.MEMBER),
        joinedAt = c.long("joined_at"),
        online = c.bool("online")
    )

    private fun conversationValues(c: Conversation) = ContentValues().apply {
        put("id", c.id)
        put("kind", c.kind.name)
        put("title", c.title)
        put("peer_id", c.peerId)
        put("avatar_seed", c.avatarSeed)
        put("last_at", c.lastMessageAt)
        put("last_preview", c.lastPreview)
        put("unread", c.unread)
        put("pinned", if (c.pinned) 1 else 0)
        put("muted", if (c.muted) 1 else 0)
        put("draft", c.draft)
        put("i_am_owner", if (c.iAmOwner) 1 else 0)
        put("member_count", c.memberCount)
    }

    private fun messageValues(m: Message) = ContentValues().apply {
        put("id", m.id)
        put("conv_id", m.convId)
        put("sender_id", m.senderId)
        put("sender_name", m.senderName)
        put("kind", m.kind.name)
        put("text", m.text)
        put("sent_at", m.sentAt)
        put("state", m.state.name)
        put("outgoing", if (m.outgoing) 1 else 0)
        put("read_by", StringSetCodec.encode(m.readBy))
        put("attachment", AttachmentCodec.encode(m.attachment))
        put("recalled", if (m.recalled) 1 else 0)
        put("starred", if (m.starred) 1 else 0)
        put("quote", QuoteCodec.encode(m.quote))
    }

    private fun contactValues(c: Contact) = ContentValues().apply {
        put("device_id", c.deviceId)
        put("name", c.name)
        put("remark", c.remark)
        put("address", c.address)
        put("avatar_seed", c.avatarSeed)
        put("added_at", c.addedAt)
        put("last_seen", c.lastSeen)
    }

    private fun memberValues(groupId: String, m: Member) = ContentValues().apply {
        put("group_id", groupId)
        put("device_id", m.deviceId)
        put("name", m.name)
        put("avatar_seed", m.avatarSeed)
        put("role", m.role.name)
        put("joined_at", m.joinedAt)
        put("online", if (m.online) 1 else 0)
    }

    private companion object {
        const val DB_NAME = "liuli.db"

        /**
         * 1 → first shipped schema.
         * 2 → adds `messages.recalled` / `messages.starred` / `messages.quote` and
         *     `idx_messages_starred`; existing rows survive (see [upgradeToV2]).
         */
        const val DB_VERSION = 2

        /** Upper bound for a single read, so a hostile `limit` cannot allocate unbounded memory. */
        const val MAX_ROWS = 10_000

        const val T_CONVERSATIONS = "conversations"
        const val T_MESSAGES = "messages"
        const val T_CONTACTS = "contacts"
        const val T_MEMBERS = "members"

        const val SQL_CREATE_CONVERSATIONS = """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL DEFAULT 'DIRECT',
                title TEXT NOT NULL DEFAULT '',
                peer_id TEXT,
                avatar_seed INTEGER NOT NULL DEFAULT 0,
                last_at INTEGER NOT NULL DEFAULT 0,
                last_preview TEXT NOT NULL DEFAULT '',
                unread INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                muted INTEGER NOT NULL DEFAULT 0,
                draft TEXT NOT NULL DEFAULT '',
                i_am_owner INTEGER NOT NULL DEFAULT 0,
                member_count INTEGER NOT NULL DEFAULT 0
            )
        """

        const val SQL_CREATE_MESSAGES = """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                conv_id TEXT NOT NULL,
                sender_id TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT 'TEXT',
                text TEXT NOT NULL DEFAULT '',
                sent_at INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'SENT',
                outgoing INTEGER NOT NULL DEFAULT 0,
                read_by TEXT NOT NULL DEFAULT '[]',
                attachment TEXT,
                recalled INTEGER NOT NULL DEFAULT 0,
                starred INTEGER NOT NULL DEFAULT 0,
                quote TEXT
            )
        """

        const val SQL_CREATE_CONTACTS = """
            CREATE TABLE IF NOT EXISTS contacts (
                device_id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                remark TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                avatar_seed INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                last_seen INTEGER NOT NULL DEFAULT 0
            )
        """

        const val SQL_CREATE_MEMBERS = """
            CREATE TABLE IF NOT EXISTS members (
                group_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                avatar_seed INTEGER NOT NULL DEFAULT 0,
                role TEXT NOT NULL DEFAULT 'MEMBER',
                joined_at INTEGER NOT NULL DEFAULT 0,
                online INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (group_id, device_id)
            )
        """

        const val SQL_CREATE_INDEX_CONV_AT = "CREATE INDEX IF NOT EXISTS idx_messages_conv_at ON messages(conv_id, sent_at)"

        const val SQL_CREATE_INDEX_AT = "CREATE INDEX IF NOT EXISTS idx_messages_at ON messages(sent_at)"

        /**
         * `(starred, sent_at)` covers both halves of the starred query:
         * `WHERE starred = 1 ORDER BY sent_at DESC`.
         */
        const val SQL_CREATE_INDEX_STARRED =
            "CREATE INDEX IF NOT EXISTS idx_messages_starred ON messages(starred, sent_at)"

        val CONV_COLUMNS = arrayOf(
            "id", "kind", "title", "peer_id", "avatar_seed", "last_at", "last_preview",
            "unread", "pinned", "muted", "draft", "i_am_owner", "member_count"
        )

        val MSG_COLUMNS = arrayOf(
            "id", "conv_id", "sender_id", "sender_name", "kind", "text", "sent_at",
            "state", "outgoing", "read_by", "attachment", "recalled", "starred", "quote"
        )

        val CONTACT_COLUMNS = arrayOf(
            "device_id", "name", "remark", "address", "avatar_seed", "added_at", "last_seen"
        )

        val MEMBER_COLUMNS = arrayOf(
            "device_id", "name", "avatar_seed", "role", "joined_at", "online"
        )
    }
}
