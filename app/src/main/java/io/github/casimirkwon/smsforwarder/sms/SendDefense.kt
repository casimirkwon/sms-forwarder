package io.github.casimirkwon.smsforwarder.sms

import android.telephony.PhoneNumberUtils
import android.util.Log
import io.github.casimirkwon.smsforwarder.Settings
import io.github.casimirkwon.smsforwarder.core.SendGuard
import io.github.casimirkwon.smsforwarder.core.SentRecord
import io.github.casimirkwon.smsforwarder.db.AppDb
import io.github.casimirkwon.smsforwarder.db.RepeatBlock
import io.github.casimirkwon.smsforwarder.db.DeliveryEntity
import io.github.casimirkwon.smsforwarder.db.RecentSend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * 전송 직전 방어: 중복 전송 방지·반복(루프) 전송 방지.
 * 건마다 차례로 판정해야 하므로 뮤텍스로 직렬화하고 Room 표(`recent_sends`)를 직접 읽고 쓴다.
 */
class SendDefense(
    private val db: AppDb,
    private val settings: Settings,
    private val phoneCompare: (String, String) -> Boolean = { a, b -> PhoneNumberUtils.compare(a, b) },
) {
    private val mutex = Mutex()

    /**
     * @param fresh 파이프라인에서 막 만든 건이면 true(기록에 처리 전 본문을 넣는다). 재전송 큐(NetworkRetryWorker)에서
     * DB 로 다시 읽은 건이면 false — 이때는 처리 전 본문이 없어 빈 문자열이 기록에 들어간다.
     */
    suspend fun check(row: DeliveryEntity, fresh: Boolean, now: Long = System.currentTimeMillis()): SendGuard.Verdict = mutex.withLock {
        val s = settings.snapshot()
        if (!s.avoidDuplicate && !s.preventRepeated) return SendGuard.Verdict.OK
        val dest = row.recipient

        if (s.preventRepeated) {
            // 1시간이 지난 차단 항목은 지우고, 남은 것 중에 이 수신처가 있으면 바로 차단.
            var blocked = false
            for (b in db.repeatBlocks().all()) {
                if (abs(now - b.time) < SendGuard.BLOCK_MS) {
                    if (SendGuard.blockMatches(b.recipient, dest, phoneCompare)) blocked = true
                } else {
                    db.repeatBlocks().delete(b.id)
                }
            }
            if (blocked) {
                Log.i(TAG, "Recipient is in block list = $dest")
                return SendGuard.Verdict.REPEATED
            }
        }

        db.recentSends().deleteStale(now)
        val history = db.recentSends().all().map { SentRecord(it.content, it.sender, it.recipient, it.receiveTime, it.sendTime) }
        val cur = SentRecord(if (fresh) row.rawBody else "", row.sender, dest, row.recvTime, now)
        val counts = SendGuard.count(history, cur, phoneCompare)
        db.recentSends().insert(RecentSend(receiveTime = cur.receiveTime, sendTime = cur.sendTime, content = cur.content, sender = cur.inNumber, recipient = cur.outNumber))

        val verdict = SendGuard.decide(s.avoidDuplicate, s.preventRepeated, s.stopDuplicateAlways, s.duplicateCount, s.repeatedMaxCount, counts)
        Log.i(TAG, "guard: row=${row.id} similar=${counts.similar} sameDest=${counts.sameDest} -> $verdict")
        if (verdict == SendGuard.Verdict.REPEATED && db.repeatBlocks().all().none { it.recipient == dest }) {
            db.repeatBlocks().insert(RepeatBlock(recipient = dest, time = now))
        }
        verdict
    }

    private companion object {
        const val TAG = "SendDefense"
    }
}
