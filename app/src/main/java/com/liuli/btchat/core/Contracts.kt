package com.liuli.btchat.core

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Frozen service contracts.
 *
 * The UI only ever reads [ChatStore] and only ever writes through [ChatEngine];
 * every other module talks to these interfaces and never to each other's
 * implementation classes.
 */

interface ChatStore {

    /** Bumped after any write, so Compose screens can re-read cheaply. */
    val revision: StateFlow<Long>

    /**
     * 非空表示**持久化已经失效**：写入只留在内存里，重启就没了。
     *
     * 这个提示必须显示给用户。存储层在数据库打不开时会退化成内存实现，让界面
     * 继续可用 —— 这个设计本身是对的，但退化之后应用看起来**和一个刚装好、
     * 什么都没有的应用完全一样**：没有会话、没有联系人、身份是新的。用户看到
     * 的结论只会是「我的聊天记录全没了」，而且没有任何东西告诉他这只是暂时的、
     * 数据其实还在磁盘上。
     *
     * 默认 null，所以只有真正会退化的实现需要覆写它。
     */
    val persistenceWarning: String? get() = null

    fun conversations(): List<Conversation>
    fun conversation(id: String): Conversation?
    fun saveConversation(c: Conversation)
    fun deleteConversation(id: String)
    fun clearHistory(convId: String)
    fun setDraft(convId: String, draft: String)
    fun totalUnread(): Int

    fun messages(convId: String, limit: Int = 400): List<Message>
    fun latestMessage(convId: String): Message?
    fun message(id: String): Message?
    fun saveMessage(m: Message)
    fun deleteMessage(id: String)
    fun setMessageState(id: String, state: MsgState)
    fun setMessageProgress(
        id: String,
        transferred: Long,
        progress: Float,
        state: TransferState,
        localPath: String? = null,
        thumbPath: String? = null
    )
    fun markConversationRead(convId: String)
    fun searchMessages(query: String, limit: Int = 60): List<Message>

    /** Tombstones a message in place: the sender took it back. */
    fun recallMessage(id: String)

    /** "我的收藏". */
    fun setStarred(id: String, starred: Boolean)
    fun starredMessages(limit: Int = 200): List<Message>
    fun starredCount(): Int

    fun contacts(): List<Contact>
    fun contact(deviceId: String): Contact?
    fun saveContact(c: Contact)
    fun deleteContact(deviceId: String)

    fun members(groupId: String): List<Member>
    fun setMembers(groupId: String, members: List<Member>)
    fun addMember(groupId: String, m: Member)
    fun removeMember(groupId: String, memberId: String)
    fun groupIds(): List<String>
}

interface SettingsApi {
    val flow: StateFlow<Prefs>
    fun current(): Prefs
    fun edit(block: (Prefs) -> Prefs)
    /** Creates the local identity on first launch and returns it. */
    fun ensureIdentity(): Prefs
}

interface MediaVault {
    suspend fun importImage(uri: Uri): ImportedMedia?
    suspend fun importVideo(uri: Uri): ImportedMedia?
    /** Picks image-or-video by sniffing the content type. */
    suspend fun importAny(uri: Uri): ImportedMedia?

    /** Resolves a small bitmap for a bubble; may decode what arrived inline. */
    suspend fun thumbnail(att: Attachment): File?
    fun fileFor(att: Attachment): File?

    /** Writes an incoming base64 thumbnail to disk, returning its path. */
    fun storeIncomingThumb(transferId: String, b64: String?): String?

    /** Allocates the destination for a payload that is about to arrive. */
    fun newIncomingFile(transferId: String, fileName: String): File

    /** Publishes a received image/video into the system gallery. */
    fun exportToGallery(file: File, mime: String): Boolean

    fun shareUri(file: File): Uri

    fun deleteTransferFiles(transferId: String)

    /**
     * Removes whatever an attachment actually owns on disk.
     *
     * An outgoing payload is written under an id minted at import time, which
     * is *not* the transfer id the engine later puts on the attachment, so
     * deleting by transfer id alone leaks the file. This is the call that
     * always works.
     */
    fun deleteAttachmentFiles(att: Attachment)
}

interface ChatEngine {
    val link: StateFlow<LinkStatus>
    val peers: StateFlow<List<Peer>>
    val transfers: StateFlow<List<TransferProgress>>
    /** Conversation ids where the other side is currently typing. */
    val typing: StateFlow<Set<String>>

    fun start()
    fun stop()

    fun startScan()
    fun stopScan()
    fun refreshBonded()

    /** Opens a link to [peer]; returns false when the adapter refuses. */
    fun connect(peer: Peer): Boolean
    fun disconnect(address: String)
    fun isConnected(deviceId: String): Boolean

    fun sendText(convId: String, text: String, quoteMsgId: String? = null): Message?
    fun sendMedia(convId: String, imported: ImportedMedia): Message?
    fun resend(messageId: String)
    fun cancelTransfer(transferId: String)

    /** Takes a message back; the peer sees a tombstone instead. */
    fun recall(messageId: String)

    /** Deletes the message on both sides. */
    fun deleteForEveryone(messageId: String)

    /** Re-sends an existing message (and its file, if any) into another chat. */
    fun forward(messageId: String, toConvId: String): Message?

    fun setStarred(messageId: String, starred: Boolean)

    fun setTyping(convId: String, on: Boolean)
    fun markRead(convId: String)

    fun createGroup(name: String, avatarSeed: Int, members: List<Contact>): Conversation?
    fun renameGroup(groupId: String, name: String)
    fun addGroupMember(groupId: String, contact: Contact)
    fun removeGroupMember(groupId: String, deviceId: String)
    fun leaveGroup(groupId: String)
    fun dissolveGroup(groupId: String)

    // ---- 群管理（微信对等）----
    //
    // All of these write locally and then broadcast the member table + group
    // settings in one `GroupUpdatePacket`; nothing here decides who is allowed
    // to do what — the UI only shows the entries to the owner/admin, which keeps
    // the transport free of policy it cannot verify anyway.
    fun updateGroupAnnouncement(groupId: String, text: String)
    fun setGroupMuteAll(groupId: String, on: Boolean)
    fun setMemberMuted(groupId: String, deviceId: String, muted: Boolean)
    fun setGroupAdmin(groupId: String, deviceId: String, admin: Boolean)
    fun transferGroupOwner(groupId: String, deviceId: String)
    fun setMyGroupNickname(groupId: String, nickname: String)

    fun directConversationWith(contact: Contact): Conversation
    fun ensureDirectConversation(peer: Peer): Conversation
}
