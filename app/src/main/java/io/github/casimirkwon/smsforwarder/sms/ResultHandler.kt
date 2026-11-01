package io.github.casimirkwon.smsforwarder.sms

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.Settings
import io.github.casimirkwon.smsforwarder.core.DeliveryReport
import io.github.casimirkwon.smsforwarder.core.DeliveryTally
import io.github.casimirkwon.smsforwarder.core.RetryPolicy
import io.github.casimirkwon.smsforwarder.core.SendResult
import io.github.casimirkwon.smsforwarder.core.SentResult
import io.github.casimirkwon.smsforwarder.db.AppDb
import io.github.casimirkwon.smsforwarder.db.DeliveryEntity
import io.github.casimirkwon.smsforwarder.util.Notifs
import io.github.casimirkwon.smsforwarder.work.RetryScheduler
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Modifier

/** 전송 결과(sentIntent) 처리: 결과 코드 정규화와 결과 기록. */
class ResultHandler(
    private val ctx: Context,
    private val db: AppDb,
    private val settings: Settings,
) {
    private val tag = "ResultHandler"

    /** SmsManager.RESULT_* 상수 값 집합(리플렉션으로 모은다). */
    private val smsResultConstants: Set<Int> by lazy {
        SmsManager::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("RESULT_") }
            .mapNotNull { runCatching { it.getInt(null) }.getOrNull() }
            .toSet()
    }

    /**
     * sentIntent 결과 코드 → 저장 코드. 수신기에서 바로 부른다.
     * 10011126 이 되면(재시도 설정이 켜져 있고 retry_count < http_retry) 재시도를 예약한다:
     * 첫 재시도는 60초 뒤·last_retry_time = 지금-121초, 이후는 180초 뒤·last_retry_time = 지금, retry_count + 1.
     */
    suspend fun normalize(rowId: Long, resultCode: Int): Int {
        val s = settings.snapshot()
        val row = db.deliveries().get(rowId)
        val canRetry = row != null && RetryPolicy.canRetry(row.retryCount, s.retryMax)
        val code = SentResult.normalize(resultCode, smsResultConstants, s.retrySmsOnFailure, canRetry)
        Log.i(tag, "sent result: row=$rowId raw=$resultCode -> $code (retryCount=${row?.retryCount}, max=${s.retryMax})")
        if (code == SendResult.RETRY_WAIT && row != null) scheduleRetry(row)
        return code
    }

    private suspend fun scheduleRetry(row: DeliveryEntity) {
        val plan = RetryPolicy.plan(row.retryCount, System.currentTimeMillis())
        Log.i(tag, "retry scheduled: row=${row.id} in ${plan.delaySec}s (count ${row.retryCount} -> ${plan.newCount})")
        db.deliveries().updateRetry(row.id, plan.lastRetryTime, plan.newCount)
        RetryScheduler.enqueueRetryWorker(ctx, plan.delaySec)
    }

    /**
     * 결과 기록. 성공이면 "전송 기록 저장"(설정 + 필터 비트 32)일 때만 행을 남기고 아니면 지운다.
     * 실패면 결과 코드와 errormsg 를 행에 남긴다. 재시도 대기(10011126)·네트워크 대기(10000017)이면
     * 네트워크 제약 워커를 예약하고 알림은 내지 않는다. 결과 알림은 설정과 필터 비트 4 가 모두 켜졌을 때만.
     */
    suspend fun record(rowId: Long, code: Int, errType: String, errMsg: String? = null) {
        val s = settings.snapshot()
        val row = db.deliveries().get(rowId) ?: return
        val notify = s.resultNotification && row.notifyResult
        if (code == SendResult.SUCCESS) {
            if (s.saveSendData && row.saveHistory) {
                db.deliveries().update(row.copy(status = SendResult.SUCCESS, detail = ""))
            } else {
                db.deliveries().delete(rowId)
            }
            if (notify) Notifs.result(ctx, ctx.getString(R.string.n_result_done, row.ruleName))
            return
        }
        val err = JSONObject().put("type", errType).put("msg", errMsg ?: "").toString()
        db.deliveries().update(row.copy(status = code, detail = err))
        val waiting = code == SendResult.RETRY_WAIT || code == SendResult.WAITING_NETWORK
        if (waiting) RetryScheduler.enqueueNetworkWorker(ctx)
        if (notify && !waiting) Notifs.result(ctx, ctx.getString(R.string.n_result_fail, row.ruleName))
    }

    private val deliveryLock = Mutex()

    /**
     * 도착 보고(수신 확인) 한 건을 해당 전달 건에 더한다. 표시 정보만 바꾸고 발송 접수 판정·재시도·결과 알림은 건드리지 않는다.
     * 전달 건이 이미 지워졌으면(기록 보관 안 함·기간 만료) 무시한다. 부분 문자의 보고가 동시에 와도 하나씩 더한다.
     * @param status 보고 PDU 에서 읽은 상태 값(읽을 수 없으면 null)
     */
    suspend fun recordDeliveryReport(key: DeliveryReport.Key, resultCode: Int, status: Int?) {
        val part = DeliveryReport.classify(resultCode, status)
        Log.i(tag, "delivery report: row=${key.rowId} part=${key.part}/${key.total} resultCode=$resultCode status=$status -> $part")
        if (part != DeliveryReport.Part.DELIVERED && part != DeliveryReport.Part.FAILED) return
        deliveryLock.withLock {
            val row = db.deliveries().get(key.rowId) ?: return
            val before = DeliveryTally.decode(row.deliveryTally)
            val after = before.with(part, key.part, key.total)
            if (after == before) return
            db.deliveries().setDeliveryTally(key.rowId, after.encode(), System.currentTimeMillis())
        }
    }
}
