package com.icqnew.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A contact OR a group (groups have uin = -groupId and isGroup = true). */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uin: Long,
    val name: String,
    val avatarUrl: String?,
    val online: Boolean,
    val lastSeen: Long,
    val about: String = "",
    val isGroup: Boolean = false,
    val members: Int = 0,
    val owner: Long = 0,
    /** user / group / channel */
    val kind: String = "user",
    val isPublic: Boolean = false,
    val username: String? = null,
    val verified: Boolean = false,
    /** group / channel options as JSON (GroupSettingsDto) */
    val settings: String = "",
    val botName: String = "",
    val botAvatar: String? = null,
)

/** Members of a group (they are not necessarily in the contact list). */
@Entity(tableName = "members", primaryKeys = ["gid", "uin"])
data class MemberEntity(
    val gid: Long,
    val uin: Long,
    val name: String,
    val avatarUrl: String?,
    val online: Boolean,
    val lastSeen: Long,
    val about: String = "",
    val pos: Int = 0,
    val role: String = "member",
    val mutedUntil: Long = 0,
    val verified: Boolean = false,
    val username: String? = null,
)

/** Local options of one chat. */
@Entity(tableName = "prefs")
data class ChatPrefEntity(
    @PrimaryKey val peer: Long,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    val draft: String = "",
    val wallpaper: String = "",
)

@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peer: Long,
    val outgoing: Boolean,
    val video: Boolean,
    val ts: Long,
    val duration: Int,
    /** DONE / MISSED / DECLINED / CANCELLED */
    val status: String,
)

data class CallRow(
    val id: Long,
    val peer: Long,
    val outgoing: Boolean,
    val video: Boolean,
    val ts: Long,
    val duration: Int,
    val status: String,
    val name: String?,
    val avatarUrl: String?,
)

data class FoundRow(val peerUin: Long, val name: String?, val text: String, val ts: Long)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val k: String, val v: Long)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["clientId"], unique = true),
        Index(value = ["peerUin"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long?,
    val clientId: String,
    /** user uin, or -groupId for group chats */
    val peerUin: Long,
    val fromMe: Boolean,
    val text: String,
    val ts: Long,
    /** 0 = sending (queued), 1 = accepted by server, 2 = read by the peer, 3 = failed. */
    val status: Int,
    val isRead: Boolean,
    /** text / sticker / image / file / voice / system */
    val kind: String = "text",
    val attId: String? = null,
    val attName: String? = null,
    val attSize: Long = 0,
    val attMime: String? = null,
    val attDur: Long = 0,
    /** Local copy of the attachment (outgoing files are kept here until they are uploaded). */
    val localPath: String? = null,
    val fromUin: Long = 0,
    /** server id of the message this one answers */
    val replyTo: Long? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val fwdName: String? = null,
    /** "uin:emoji;uin:emoji" */
    val reactions: String = "",
    val failReason: String? = null,
    val views: Int = 0,
    /** PollDto as JSON */
    val poll: String? = null,
    /** picture of the publication this message answers ("" = answer without picture) */
    val storyAtt: String? = null,
)

/** Result row of the chat-list query (peer + last message + unread count + local options). */
data class ChatRow(
    val uin: Long,
    val name: String,
    val avatarUrl: String?,
    val online: Boolean,
    val lastSeen: Long,
    val about: String,
    val isGroup: Boolean,
    val kind: String,
    val members: Int,
    val verified: Boolean,
    val lastStatus: Int,
    val lastText: String,
    val lastKind: String,
    val lastTs: Long,
    val lastFromMe: Boolean,
    val lastDeleted: Boolean,
    val lastAttName: String?,
    val lastSender: String?,
    val unread: Int,
    val pinned: Boolean,
    val muted: Boolean,
)

@Dao
interface ContactDao {
    @Query("SELECT uin FROM contacts WHERE username = :un COLLATE NOCASE LIMIT 1")
    suspend fun uinByUsername(un: String): Long?

    @Query("SELECT * FROM contacts WHERE isGroup = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE isGroup = 0 ORDER BY name COLLATE NOCASE")
    suspend fun allUsers(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE uin = :uin")
    fun observe(uin: Long): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE uin = :uin")
    suspend fun get(uin: Long): ContactEntity?

    @Query("SELECT uin FROM contacts WHERE isGroup = 1")
    suspend fun groupPeers(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("UPDATE contacts SET online = :online, lastSeen = :lastSeen WHERE uin = :uin")
    suspend fun setPresence(uin: Long, online: Boolean, lastSeen: Long)

    @Query("DELETE FROM contacts WHERE uin = :uin")
    suspend fun delete(uin: Long)
}

@Dao
interface MemberDao {
    @Query("SELECT uin FROM members WHERE username = :un COLLATE NOCASE LIMIT 1")
    suspend fun uinByUsername(un: String): Long?

    @Query("SELECT * FROM members WHERE gid = :gid ORDER BY pos")
    fun observe(gid: Long): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE gid = :gid AND uin = :uin")
    suspend fun get(gid: Long, uin: Long): MemberEntity?

    @Query("SELECT * FROM members WHERE uin = :uin LIMIT 1")
    suspend fun anyByUin(uin: Long): MemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<MemberEntity>)

    @Query("DELETE FROM members WHERE gid = :gid")
    suspend fun clear(gid: Long)

    @Query("UPDATE members SET online = :online, lastSeen = :lastSeen WHERE uin = :uin")
    suspend fun setPresence(uin: Long, online: Boolean, lastSeen: Long)

    @Query("UPDATE members SET name = :name, avatarUrl = :avatarUrl, about = :about WHERE uin = :uin")
    suspend fun updateProfile(uin: Long, name: String, avatarUrl: String?, about: String)
}

@Dao
interface PrefDao {
    @Query("SELECT * FROM prefs WHERE peer = :peer")
    fun observe(peer: Long): Flow<ChatPrefEntity?>

    @Query("SELECT * FROM prefs WHERE peer = :peer")
    suspend fun get(peer: Long): ChatPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: ChatPrefEntity)

    @Query("UPDATE prefs SET blocked = 0")
    suspend fun clearBlocked()

    @Query("SELECT c.* FROM prefs p JOIN contacts c ON c.uin = p.peer WHERE p.blocked = 1 ORDER BY c.name")
    fun observeBlocked(): Flow<List<ContactEntity>>
}

@Dao
interface CallDao {
    @Insert
    suspend fun insert(c: CallEntity): Long

    @Query(
        """SELECT k.id AS id, k.peer AS peer, k.outgoing AS outgoing, k.video AS video, k.ts AS ts,
           k.duration AS duration, k.status AS status, c.name AS name, c.avatarUrl AS avatarUrl
           FROM calls k LEFT JOIN contacts c ON c.uin = k.peer ORDER BY k.ts DESC LIMIT 300"""
    )
    fun observe(): Flow<List<CallRow>>

    @Query("DELETE FROM calls")
    suspend fun clear()
}

@Dao
interface KvDao {
    @Query("SELECT v FROM kv WHERE k = :k")
    suspend fun get(k: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: KvEntity)
}

@Dao
interface MessageDao {
    @Query("UPDATE messages SET views = :views WHERE serverId = :sid AND views < :views")
    suspend fun setViews(sid: Long, views: Int)

    @Query("SELECT * FROM messages WHERE peerUin = :peer ORDER BY ts ASC, id ASC")
    fun observe(peer: Long): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE serverId = :serverId")
    suspend fun byServerId(serverId: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: Long)

    /** Chats with unread messages that tag me (@username). */
    @Query("SELECT DISTINCT peerUin FROM messages WHERE isRead = 0 AND fromMe = 0 AND deleted = 0 AND text LIKE :pattern")
    fun observeMentionPeers(pattern: String): Flow<List<Long>>

    @Query("SELECT * FROM messages WHERE clientId = :clientId")
    suspend fun byClientId(clientId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("UPDATE messages SET serverId = :serverId, ts = :ts, failReason = NULL, status = CASE WHEN status = 2 THEN 2 ELSE 1 END WHERE clientId = :clientId")
    suspend fun markSent(clientId: String, serverId: Long, ts: Long)

    @Query("UPDATE messages SET status = 3, failReason = :reason WHERE clientId = :clientId AND serverId IS NULL")
    suspend fun markFailed(clientId: String, reason: String)

    @Query("UPDATE messages SET status = 0, failReason = NULL WHERE clientId = :clientId AND serverId IS NULL")
    suspend fun markSending(clientId: String)

    @Query("UPDATE messages SET attId = :attId WHERE clientId = :clientId")
    suspend fun setAttId(clientId: String, attId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE peerUin = :peer AND fromMe = 0 AND isRead = 0")
    suspend fun markRead(peer: Long): Int

    @Query("UPDATE messages SET status = 2 WHERE peerUin = :peer AND fromMe = 1 AND serverId IS NOT NULL AND serverId <= :upto AND status = 1")
    suspend fun markReadUpTo(peer: Long, upto: Long)

    @Query("SELECT * FROM messages WHERE fromMe = 1 AND status = 0 ORDER BY id ASC")
    suspend fun pending(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE fromMe = 0 AND isRead = 0")
    fun observeUnreadTotal(): Flow<Int>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE clientId = :clientId AND serverId IS NULL")
    suspend fun deletePending(clientId: String)

    @Query(
        """SELECT m.peerUin AS peerUin, c.name AS name, m.text AS text, m.ts AS ts FROM messages m
           LEFT JOIN contacts c ON c.uin = m.peerUin
           WHERE m.deleted = 0 AND m.kind = 'text' AND m.text LIKE :q ORDER BY m.ts DESC LIMIT 100"""
    )
    suspend fun search(q: String): List<FoundRow>

    @Query("DELETE FROM messages WHERE peerUin = :peer")
    suspend fun deleteChat(peer: Long)

    @Query("DELETE FROM messages WHERE peerUin = :peer AND serverId IS NOT NULL AND serverId <= :upto")
    suspend fun deleteUpTo(peer: Long, upto: Long)

    @Query(
        """
        SELECT c.uin AS uin, c.name AS name, c.avatarUrl AS avatarUrl, c.online AS online,
               c.lastSeen AS lastSeen, c.about AS about, c.isGroup AS isGroup, c.kind AS kind, c.members AS members, c.verified AS verified, COALESCE(m.status, 1) AS lastStatus,
               COALESCE(m.text, '') AS lastText, COALESCE(m.kind, 'system') AS lastKind, COALESCE(m.ts, 0) AS lastTs,
               COALESCE(m.fromMe, 0) AS lastFromMe, COALESCE(m.deleted, 0) AS lastDeleted, m.attName AS lastAttName,
               (SELECT g.name FROM members g WHERE g.gid = -c.uin AND g.uin = m.fromUin) AS lastSender,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.peerUin = c.uin AND u.fromMe = 0 AND u.isRead = 0) AS unread,
               COALESCE(p.pinned, 0) AS pinned, COALESCE(p.muted, 0) AS muted
        FROM contacts c
        LEFT JOIN messages m ON m.id = (
            SELECT x.id FROM messages x WHERE x.peerUin = c.uin ORDER BY x.ts DESC, x.id DESC LIMIT 1)
        LEFT JOIN prefs p ON p.peer = c.uin
        WHERE m.id IS NOT NULL OR c.isGroup = 1  -- a just-joined channel shows up even before its posts arrive
        ORDER BY COALESCE(p.pinned, 0) DESC, COALESCE(m.ts, 0) DESC
        """
    )
    fun observeChats(): Flow<List<ChatRow>>
}

@Database(
    entities = [
        ContactEntity::class, MessageEntity::class, MemberEntity::class, ChatPrefEntity::class, KvEntity::class,
        CallEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class IcqDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun memberDao(): MemberDao
    abstract fun prefDao(): PrefDao
    abstract fun kvDao(): KvDao
    abstract fun callDao(): CallDao
}


/** 6 -> 7: members got @username (for @mentions). Keeps all messages on the phone. */
val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN storyAtt TEXT")
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN views INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN poll TEXT")
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE members ADD COLUMN username TEXT")
    }
}
