package io.github.casimirkwon.smsforwarder.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.casimirkwon.smsforwarder.core.SendResult
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY position ASC, id ASC")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM rules")
    suspend fun nextOrder(): Int

    @Insert
    suspend fun insert(f: RuleEntity): Long

    @Update
    suspend fun update(f: RuleEntity)

    @Delete
    suspend fun delete(f: RuleEntity)

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    /** 가져오기 "모두 바꾸기"에서만 쓴다. */
    @Query("DELETE FROM rules")
    suspend fun deleteAll()
}

@Dao
interface DeliveryDao {
    @Insert
    suspend fun insert(s: DeliveryEntity): Long

    @Update
    suspend fun update(s: DeliveryEntity)

    @Query("SELECT * FROM deliveries WHERE id = :id")
    suspend fun get(id: Long): DeliveryEntity?

    @Query("DELETE FROM deliveries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM deliveries ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DeliveryEntity>>

    @Query("SELECT * FROM deliveries ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE status IN (:results)")
    suspend fun byResults(results: List<Int>): List<DeliveryEntity>

    /** 재전송 대기 행(네트워크 대기·재시도 대기), 오래된 것부터 */
    @Query("SELECT * FROM deliveries WHERE status IN (${SendResult.WAITING_NETWORK}, ${SendResult.RETRY_WAIT}) ORDER BY id ASC")
    suspend fun retryRows(): List<DeliveryEntity>

    /** 예약(지연 전달) 행. id 오름차순 */
    @Query("SELECT * FROM deliveries WHERE status = ${SendResult.SCHEDULED} ORDER BY id ASC")
    suspend fun scheduledRows(): List<DeliveryEntity>

    /** 결과 코드만 바꾼다 */
    @Query("UPDATE deliveries SET status = :result WHERE id = :id")
    suspend fun setResult(id: Long, result: Int)

    /** 재시도 예약 기록: lastRetryTime 과 retryCount */
    @Query("UPDATE deliveries SET lastRetryTime = :lastRetryTime, retryCount = :retryCount WHERE id = :id")
    suspend fun updateRetry(id: Long, lastRetryTime: Long, retryCount: Int)

    /** 도착 보고 기록: 모은 값과 시각만 바꾼다(다른 열은 건드리지 않는다) */
    @Query("UPDATE deliveries SET deliveryTally = :tally, deliveryTime = :time WHERE id = :id")
    suspend fun setDeliveryTally(id: Long, tally: String, time: Long)

    /** 재전송 직전 행 상태: 전송 중으로 되돌리고 전송 시각 갱신 */
    @Query("UPDATE deliveries SET status = :result, sendTime = :sendTime WHERE id = :id")
    suspend fun updateStatus(id: Long, result: Int, sendTime: Long)
}

@Dao
interface ReceivedDao {
    @Insert
    suspend fun insert(h: ReceivedEntity): Long

    @Insert
    suspend fun insertStep(s: ReceivedStepEntity): Long

    @Query("SELECT * FROM received WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ReceivedEntity?

    @Query("SELECT * FROM received ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReceivedEntity>>

    @Query("SELECT * FROM received_step ORDER BY id DESC LIMIT :limit")
    fun observeRecentSteps(limit: Int): Flow<List<ReceivedStepEntity>>

    @Query("SELECT * FROM received_step WHERE receivedId = :historyId ORDER BY id ASC")
    suspend fun steps(historyId: Long): List<ReceivedStepEntity>

    @Query("DELETE FROM received WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM received_step WHERE time < :before")
    suspend fun deleteStepsOlderThan(before: Long)
}

@Dao
interface PendingEventDao {
    @Insert
    suspend fun insert(item: PendingEvent): Long

    @Query("SELECT * FROM pending_events WHERE id = :idx")
    suspend fun get(idx: Long): PendingEvent?

    @Query("DELETE FROM pending_events WHERE id = :idx")
    suspend fun delete(idx: Long)

    @Query("DELETE FROM pending_events WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface SpamDao {
    @Query("SELECT * FROM spam_rules ORDER BY id ASC")
    suspend fun all(): List<SpamRuleEntity>

    @Query("SELECT * FROM spam_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<SpamRuleEntity>>

    @Insert
    suspend fun insert(r: SpamRuleEntity): Long

    @Update
    suspend fun update(r: SpamRuleEntity)

    @Query("DELETE FROM spam_rules WHERE id = :id")
    suspend fun delete(id: Long)

    /** 가져오기 "모두 바꾸기"에서만 쓴다. */
    @Query("DELETE FROM spam_rules")
    suspend fun deleteAll()
}

@Dao
interface RecentSendDao {
    /** 60초보다 오래된 기록 제거(`abs(now - sendTime) > 60000`). */
    @Query("DELETE FROM recent_sends WHERE ABS(:now - sendTime) > 60000")
    suspend fun deleteStale(now: Long)

    @Query("SELECT * FROM recent_sends ORDER BY id ASC")
    suspend fun all(): List<RecentSend>

    @Insert
    suspend fun insert(d: RecentSend): Long
}

@Dao
interface RepeatBlockDao {
    @Query("SELECT * FROM repeat_blocks ORDER BY id ASC")
    suspend fun all(): List<RepeatBlock>

    @Query("SELECT * FROM repeat_blocks ORDER BY id ASC")
    fun observeAll(): Flow<List<RepeatBlock>>

    @Insert
    suspend fun insert(b: RepeatBlock): Long

    @Query("DELETE FROM repeat_blocks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM repeat_blocks WHERE recipient = :recipient")
    suspend fun deleteByRecipient(recipient: String)
}

@Dao
interface NotiSeenDao {
    @Query("SELECT * FROM noti_seen ORDER BY id ASC")
    suspend fun all(): List<NotiSeenRow>

    @Insert
    suspend fun insert(r: NotiSeenRow): Long

    @Query("DELETE FROM noti_seen WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
