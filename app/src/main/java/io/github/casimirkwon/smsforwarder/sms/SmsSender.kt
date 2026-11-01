package io.github.casimirkwon.smsforwarder.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.Settings
import io.github.casimirkwon.smsforwarder.core.ConditionMatch
import io.github.casimirkwon.smsforwarder.core.DeliveryReport
import io.github.casimirkwon.smsforwarder.core.SendGuard
import io.github.casimirkwon.smsforwarder.core.SendResult
import io.github.casimirkwon.smsforwarder.core.SmsLimit
import io.github.casimirkwon.smsforwarder.db.AppDb
import io.github.casimirkwon.smsforwarder.db.DeliveryEntity
import io.github.casimirkwon.smsforwarder.util.Notifs

/** SMS 전송. */
class SmsSender(
    private val ctx: Context,
    private val db: AppDb,
    private val settings: Settings,
    private val results: ResultHandler,
    private val defense: SendDefense,
) {
    companion object {
        const val ACTION_SENT = "io.github.casimirkwon.smsforwarder.action.SENT"
        const val ACTION_DELIVERED = DeliveryReport.ACTION

        /**
         * 문자 한 통을 보낸다. divideMessage 로 나눠 1조각이면 sendTextMessage,
         * 여러 조각이면 sendMultipartTextMessage. sentIntent 는 **마지막 조각에만** 붙이고("성공" = 단말 radio 접수까지),
         * [deliveryIntent] 가 있으면 조각마다 도착 보고용 인텐트를 만들어 붙인다(통신사가 지원할 때만 보고가 온다).
         * 본문이 비어 있으면 sentIntent 에 EMPTY_PARAM 결과를 직접 보낸다.
         * @param deliveryIntent (조각 번호, 전체 조각 수) → 도착 보고 인텐트. null 이면 도착 보고를 받지 않는다.
         */
        fun dispatch(
            sm: SmsManager, ctx: Context, dest: String, text: String?, resultIntent: Intent?,
            deliveryIntent: ((Int, Int) -> Intent)? = null,
        ) {
            val pi = resultIntent?.let {
                PendingIntent.getBroadcast(ctx, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            if (text.isNullOrEmpty()) {
                try {
                    pi?.send(SendResult.EMPTY_PARAM)
                } catch (_: PendingIntent.CanceledException) {
                }
                return
            }
            val parts = sm.divideMessage(text)
            Log.i("SmsSender", "send: chars=${text.length} parts=${parts.size} subId=${sm.subscriptionId}")
            // 도착 보고는 시스템이 보고 PDU(pdu, format)를 인텐트에 채워 넣어야 읽을 수 있어서 변경 가능(mutable) PendingIntent 로 만든다.
            // 대상이 이 앱의 수신기로 정해진 명시적 인텐트라 다른 앱이 가로챌 수 없다.
            fun deliveryPi(part: Int): PendingIntent? = deliveryIntent?.let {
                PendingIntent.getBroadcast(ctx, 0, it(part, parts.size), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            }
            if (parts.size <= 1) {
                sm.sendTextMessage(dest, null, text, pi, deliveryPi(0))
                return
            }
            val sent = if (pi != null) ArrayList<PendingIntent?>(parts.size).apply {
                repeat(parts.size - 1) { add(null) }
                add(pi)
            } else null
            val delivered = if (deliveryIntent != null) ArrayList<PendingIntent?>(parts.size).apply {
                for (i in parts.indices) add(deliveryPi(i))
            } else null
            sm.sendMultipartTextMessage(dest, null, parts, sent, delivered)
        }
    }

    /**
     * @param fresh 파이프라인에서 막 만든 건이면 true, 재전송 큐에서 DB 로 다시 읽은 건이면 false(중복 판정 기록의 본문이 달라진다).
     */
    suspend fun send(rowId: Long, fresh: Boolean = true) {
        val row = db.deliveries().get(rowId) ?: return
        val s = settings.snapshot()
        // 본문·수신처가 비어 있으면 10011114
        if (row.recipient.isEmpty()) {
            Log.e("SmsSender", "param null: number empty")
            results.record(rowId, SendResult.EMPTY_PARAM, "empty parameter")
            return
        }
        // 전송 직전 방어(중복·반복 전송). 막히면 결과만 기록하고 보내지 않는다.
        when (defense.check(row, fresh)) {
            SendGuard.Verdict.DUPLICATE -> {
                Log.i("SmsSender", "Avoid sending duplicates")
                results.record(rowId, SendResult.DUPLICATE_BLOCKED, "duplicate msg")
                return
            }
            SendGuard.Verdict.REPEATED -> {
                Log.i("SmsSender", "Avoid repeated sending")
                results.record(rowId, SendResult.REPEAT_BLOCKED, "repeated sending")
                return
            }
            SendGuard.Verdict.OK -> {}
        }
        val dest = ConditionMatch.normalizeNumber(row.recipient)
        val sm = SimUtil.smsManagerForSlot(ctx, row.simOutSlot)
        if (sm == null) {
            results.record(rowId, SendResult.NO_SMS_MANAGER, "smsmanager")
            Notifs.problem(ctx, Notifs.ID_SIM_ERROR, ctx.getString(R.string.n_sim_error))
            return
        }
        // SMS 건수 제한: 달이 바뀌면 0 으로 되돌리고, 켜져 있고 한도에 닿았으면 보내지 않는다.
        settings.resetSmsLimitIfNewMonth(java.util.Calendar.getInstance().get(java.util.Calendar.MONTH))
        val limit = settings.snapshot()
        if (SmsLimit.reached(limit.smsLimitEnabled, limit.smsLimitCurrent, limit.smsLimitCount)) {
            Log.i("SmsSender", "Reach SMS Max Count")
            results.record(rowId, SendResult.SMS_LIMIT, "sms", "")
            return
        }
        // 다시 보내는 건이면 앞선 시도의 도착 기록은 지운다.
        db.deliveries().update(row.copy(sendTime = System.currentTimeMillis(), status = SendResult.SENDING, deliveryTally = "", deliveryTime = 0))
        val wake = if (s.sendWakeEnabled) {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSForwarder::Send").apply { acquire(30_000L) }
        } else null
        try {
            val intent = Intent(ctx, SystemReceiver::class.java).apply {
                action = ACTION_SENT
                data = Uri.parse(rowId.toString())
            }
            dispatch(sm, ctx, dest, row.content, intent) { part, total ->
                Intent(ctx, SystemReceiver::class.java).apply {
                    action = ACTION_DELIVERED
                    data = Uri.parse(DeliveryReport.key(rowId, part, total))
                }
            }
            settings.incrementSmsLimitCurrent()
        } catch (e: Exception) {
            val msg = e.toString()
            val code = when {
                e is UnsupportedOperationException || msg.contains("Sms is not supported") -> SendResult.SMS_UNSUPPORTED
                msg.contains("READ_PHONE_STATE") -> SendResult.NEED_PHONE_STATE
                else -> SendResult.FAIL
            }
            results.record(rowId, code, "sms", msg.lines().take(3).joinToString("\n"))
        } finally {
            try {
                if (wake?.isHeld == true) wake.release()
            } catch (_: Exception) {
            }
        }
    }
}
