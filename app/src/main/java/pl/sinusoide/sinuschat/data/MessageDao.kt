package pl.sinusoide.sinuschat.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<MessageEntity>)

    @Update
    fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs ASC")
    fun observeMessages(conversationId: String): LiveData<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC")
    fun getAllForConversationSync(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestampMs DESC")
    fun getAllSync(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = :status")
    fun getByStatus(status: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    fun deleteById(id: String)

    @Query("SELECT id FROM messages")
    fun getAllIds(): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderHash = :senderHash AND isSystemMessage = 0")
    fun countFromSender(conversationId: String, senderHash: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    fun clearConversation(conversationId: String)

    @Query("UPDATE messages SET timestampMs = :ts WHERE id = :id")
    fun updateTimestamp(id: String, ts: Long)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET trLocal = :translated, trFrom = :from, trTo = :to WHERE id = :id")
    fun setLocalTranslation(id: String, translated: String?, from: String?, to: String?)

    @Query("SELECT * FROM messages WHERE senderHash != :me AND senderHash != '' AND isSystemMessage = 0 " +
           "AND type = '${MessageEntity.TYPE_ATTACHMENT}' AND viewOnce = 0 AND timestampMs > :since " +
           "ORDER BY timestampMs DESC LIMIT 40")
    fun getRecentIncomingAttachments(me: String, since: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND " +
           "(status = '${MessageEntity.STATUS_UNDELIVERED}' OR type = '${MessageEntity.TYPE_EXPIRED_NOTICE}')")
    fun countExpiryMarks(conversationId: String): Int

    @Query("SELECT DISTINCT conversationId FROM messages WHERE " +
           "status = '${MessageEntity.STATUS_UNDELIVERED}' OR type = '${MessageEntity.TYPE_EXPIRED_NOTICE}'")
    fun getConversationsWithExpiryMarks(): List<String>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND status = '${MessageEntity.STATUS_PENDING}'")
    fun getPendingOutgoing(conversationId: String): List<MessageEntity>

    @Query("SELECT DISTINCT conversationId FROM messages WHERE status = '${MessageEntity.STATUS_PENDING}'")
    fun getConversationsWithPending(): List<String>

    @Query(
        "UPDATE messages SET status = '${MessageEntity.STATUS_DELIVERED}' " +
        "WHERE conversationId = :conversationId AND senderHash = :myHash " +
        "AND timestampMs <= :uptoMs AND status = '${MessageEntity.STATUS_SENT}'"
    )
    fun markDeliveredUpTo(conversationId: String, myHash: String, uptoMs: Long)

    @Query(
        "UPDATE messages SET status = '${MessageEntity.STATUS_READ}' " +
        "WHERE conversationId = :conversationId AND senderHash = :myHash " +
        "AND timestampMs <= :uptoMs " +
        "AND status IN ('${MessageEntity.STATUS_SENT}', '${MessageEntity.STATUS_DELIVERED}')"
    )
    fun markReadUpTo(conversationId: String, myHash: String, uptoMs: Long)

    @Query(
        "UPDATE messages SET status = '${MessageEntity.STATUS_DELIVERED}' " +
        "WHERE conversationId = :conversationId AND senderHash = :myHash AND isSystemMessage = 0 " +
        "AND status = '${MessageEntity.STATUS_READ}' AND timestampMs > :readUpTo AND timestampMs <= :deliveredUpTo"
    )
    fun downgradeToDelivered(conversationId: String, myHash: String, readUpTo: Long, deliveredUpTo: Long)

    @Query(
        "UPDATE messages SET status = '${MessageEntity.STATUS_SENT}' " +
        "WHERE conversationId = :conversationId AND senderHash = :myHash AND isSystemMessage = 0 " +
        "AND status IN ('${MessageEntity.STATUS_READ}', '${MessageEntity.STATUS_DELIVERED}') " +
        "AND timestampMs > :deliveredUpTo"
    )
    fun downgradeToSent(conversationId: String, myHash: String, deliveredUpTo: Long)

    @Query("DELETE FROM messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :now")
    fun deleteExpiredMessages(now: Long): Int

    @Query("SELECT * FROM messages WHERE expiresAtMs IS NOT NULL AND expiresAtMs <= :now")
    fun getExpiredMessages(now: Long): List<MessageEntity>

    @Query("UPDATE messages SET expiresAtMs = :expireAt " +
           "WHERE conversationId = :conversationId AND senderHash != :myHash " +
           "AND timestampMs >= :sinceMs " +
           "AND expiresAtMs IS NULL AND isSystemMessage = 0")
    fun setExpiryForIncoming(conversationId: String, myHash: String, sinceMs: Long, expireAt: Long)

    @Query("UPDATE messages SET expiresAtMs = :expireAt " +
           "WHERE conversationId = :conversationId AND senderHash = :myHash " +
           "AND timestampMs >= :sinceMs AND timestampMs <= :uptoMs " +
           "AND expiresAtMs IS NULL AND isSystemMessage = 0")
    fun setExpiryForReadOutgoing(conversationId: String, myHash: String, sinceMs: Long, uptoMs: Long, expireAt: Long)

    @Query("SELECT DISTINCT senderHash FROM messages WHERE isSystemMessage = 0 " +
           "AND senderHash != '' AND conversationId NOT LIKE 'g!_%' ESCAPE '!'")
    fun getPrivateSenderHashes(): List<String>

    @Query("SELECT DISTINCT conversationId FROM messages")
    fun getConversationIds(): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conv AND isSystemMessage = 0")
    fun countInConversation(conv: String): Int

    @Query("SELECT DISTINCT senderHash FROM messages WHERE conversationId = :conv AND senderHash != :me " +
           "AND senderHash != '' AND isSystemMessage = 0 AND timestampMs > :after AND timestampMs <= :upto LIMIT 100")
    fun getIncomingSenders(conv: String, me: String, after: Long, upto: Long): List<String>

    @Query("SELECT * FROM messages WHERE conversationId = :conv AND senderHash != :me AND senderHash != '' " +
           "AND isSystemMessage = 0 ORDER BY timestampMs DESC LIMIT :n")
    fun getLastIncoming(conv: String, me: String, n: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE senderHash = :me AND status = '${MessageEntity.STATUS_SENT}' " +
           "AND isSystemMessage = 0 AND timestampMs > :after AND timestampMs <= :before " +
           "AND conversationId NOT LIKE 'g!_%' ESCAPE '!' LIMIT 500")
    fun getSentUndelivered(me: String, after: Long, before: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderHash != :me " +
           "AND senderHash != '' AND isSystemMessage = 0 AND timestampMs > :since")
    fun countIncomingSince(conversationId: String, me: String, since: Long): Int

    @Query("UPDATE messages SET conversationId = :newId WHERE conversationId = :oldId")
    fun renameConversation(oldId: String, newId: String)

    @Query("UPDATE messages SET senderHash = :newHash WHERE senderHash = :oldHash")
    fun renameSender(oldHash: String, newHash: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC LIMIT 1")
    fun getLastMessage(conversationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC LIMIT :limit")
    fun getLatest(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND pinned = 1 AND isSystemMessage = 0 " +
           "ORDER BY timestampMs DESC LIMIT :limit")
    fun getPinned(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND timestampMs < :before " +
           "ORDER BY timestampMs DESC LIMIT :limit")
    fun getBefore(conversationId: String, before: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE senderHash != :me AND senderHash != '' AND isSystemMessage = 0 " +
           "AND timestampMs > :since ORDER BY timestampMs ASC LIMIT 50")
    fun getIncomingSince(me: String, since: Long): List<MessageEntity>
}
