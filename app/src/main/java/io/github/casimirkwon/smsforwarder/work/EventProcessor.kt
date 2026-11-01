package io.github.casimirkwon.smsforwarder.work

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.core.GlobalGate
import io.github.casimirkwon.smsforwarder.core.DelayPolicy
import io.github.casimirkwon.smsforwarder.core.IncomingNotification
import io.github.casimirkwon.smsforwarder.core.IncomingSms
import io.github.casimirkwon.smsforwarder.core.MmsInbox
import io.github.casimirkwon.smsforwarder.core.SendResult
import io.github.casimirkwon.smsforwarder.core.PermNeeds
import io.github.casimirkwon.smsforwarder.core.Template
import io.github.casimirkwon.smsforwarder.core.parseDestinations
import io.github.casimirkwon.smsforwarder.util.NotiAccessWarning
import io.github.casimirkwon.smsforwarder.util.Notifs
import io.github.casimirkwon.smsforwarder.util.Perms
import kotlinx.coroutines.delay
import java.util.Calendar

/** 워커(DeliveryWorker)와 서비스(ForwardingService)가 같이 쓰는 이벤트 처리. */
object EventProcessor {
    private const val TAG = "EventProcessor"

    /** 수신 SMS 이벤트: 권한을 점검한 뒤 메시지마다 파이프라인을 돌린다. */
    suspend fun processSms(ctx: Context, graph: Graph, messages: List<IncomingSms>, simSlot: Int) {
        warnIfNotiAccessMissing(ctx, graph)
        val missing = missingPermission(ctx, graph, checkNotificationAccess = false)
        if (missing != 0) {
            Log.e(TAG, "permission error=$missing")
            Notifs.permissionError(ctx)
            return
        }
        if (!gateOpen(ctx, graph)) return
        for (m in messages) graph.pipeline.onSms(m, simSlot)
    }

    /**
     * 알림 이벤트(`ACTION_NOTI`): 수신 SMS 와 같은 권한 점검·전역 게이트를 지난 뒤
     * 알림 규칙 평가 파이프라인을 돌린다.
     */
    suspend fun processNotification(ctx: Context, graph: Graph, n: IncomingNotification) {
        // 알림 접근이 없으면 알림 규칙 전달만 멈춘다(경고는 한 번만, 문자 경로에는 영향 없음).
        if (!NotiAccessWarning.check(ctx, needsAccess = true)) {
            Log.w(TAG, "notification access missing, notification rules skipped")
            return
        }
        val missing = missingPermission(ctx, graph)
        if (missing != 0) {
            Log.e(TAG, "permission error=$missing")
            Notifs.permissionError(ctx)
            return
        }
        if (!gateOpen(ctx, graph)) return
        graph.pipeline.onNotification(n)
    }

    /**
     * 이력 항목의 "필터 다시 적용"(`ACTION_REAPPLY`). 서비스/워커가 이 동작을 전역 게이트(근무시간·로밍)와 권한 점검
     * 앞에서 처리하므로 여기서도 거치지 않는다. 앱 사용이 꺼져 있으면 파이프라인이 아무것도 하지 않는다.
     */
    suspend fun processReapply(graph: Graph, historyId: Long) {
        if (historyId < 0) {
            Log.w(TAG, "retry app history: no rowid")
            return
        }
        val passed = graph.pipeline.reapply(historyId)
        Log.i(TAG, "Resend AppHistory rowId=$historyId done, anyQueued=$passed")
    }

    /**
     * 지연 전달 처리(`ACTION_DELAY`). 권한 점검 뒤, 예약 행(10000003) 중 `지금+60초 ≥ 전송 시각`인 것을
     * id 오름차순으로: 앱 사용이 켜져 있으면 전송 중(2)·전송 시각 지금으로 바꿔 곧바로 전송 호출(큐의
     * 건 사이 무작위 대기를 거치지 않는다), 꺼져 있으면 10000012 로 취소한다. 전역 근무시간·로밍 게이트는 거치지 않는다.
     */
    suspend fun processDelay(ctx: Context, graph: Graph) {
        val missing = missingPermission(ctx, graph, checkNotificationAccess = false)
        if (missing != 0) {
            Log.e(TAG, "delay: permission error=$missing")
            Notifs.permissionError(ctx)
            return
        }
        val now = System.currentTimeMillis()
        val due = graph.db.deliveries().scheduledRows().filter { DelayPolicy.isDue(it.sendTime, now) }
        for (row in due) {
            Log.i(TAG, "checkDelayedMessage: row=${row.id} out=${row.recipient} sendTime=${row.sendTime}")
            if (graph.settings.snapshot().appEnable) {
                graph.db.deliveries().updateStatus(row.id, SendResult.SENDING, System.currentTimeMillis())
                graph.sender.send(row.id, fresh = false)
            } else {
                graph.db.deliveries().setResult(row.id, SendResult.DELAY_CANCELLED)
            }
        }
    }

    /**
     * MMS(WAP push) 처리 — 서비스 모드(`ACTION_MMS`): 권한·전역 게이트를 지난 뒤
     * [firstDelayMs] 만큼 기다렸다가(서비스 모드 3초) content://mms/inbox 에서 새 MMS 를 읽어 SMS 와 같은 경로로 넘긴다.
     * 아직 준비되지 않았으면 수신 후 30초 안은 6초, 이후는 15초 간격으로 다시 읽고 약 120초에서 마지막 시도로 끝낸다
     * ([MmsInbox.nextDelayMs]).
     */
    suspend fun processMms(ctx: Context, graph: Graph, url: String?, initTime: Long, firstDelayMs: Long) {
        warnIfNotiAccessMissing(ctx, graph)
        val missing = missingPermission(ctx, graph, checkNotificationAccess = false)
        if (missing != 0) {
            Log.e(TAG, "mms: permission error=$missing")
            Notifs.permissionError(ctx)
            return
        }
        if (!gateOpen(ctx, graph)) return
        if (firstDelayMs > 0) delay(firstDelayMs)
        var attempt = 1
        while (true) {
            Log.i(TAG, "delay mms DelayCount=$attempt")
            val next = graph.mms.readOnce(attempt, initTime, url) ?: return
            Log.i(TAG, "Retry MMS")
            attempt++
            delay(next)
        }
    }

    /**
     * MMS 처리 — 워커 모드의 시도 한 번. 워커 한 번에 읽기 한 번만 하고,
     * 더 읽어야 하면 다음 시도 번호로 새 워커를 지연 예약한다(게이트는 시도마다 다시 확인한다).
     * @return 다음 시도까지 기다릴 시간(ms). 끝났거나 게이트에 막혔으면 null.
     */
    suspend fun processMmsAttempt(ctx: Context, graph: Graph, url: String?, initTime: Long, attempt: Int): Long? {
        warnIfNotiAccessMissing(ctx, graph)
        val missing = missingPermission(ctx, graph, checkNotificationAccess = false)
        if (missing != 0) {
            Log.e(TAG, "mms: permission error=$missing")
            Notifs.permissionError(ctx)
            return null
        }
        if (!gateOpen(ctx, graph)) return null
        Log.i(TAG, "delay mms DelayCount=$attempt")
        val next = graph.mms.readOnce(attempt, initTime, url)
        if (next != null) Log.i(TAG, "Retry MMS")
        return next
    }

    /**
     * 전역 게이트: 앱 사용이 켜져 있고, 전역 근무시간 안이며,
     * 로밍 중 전달이 허용된 경우에만 수신 문자를 처리한다. 하나라도 막히면 조용히 버린다.
     */
    private suspend fun gateOpen(ctx: Context, graph: Graph): Boolean {
        val s = graph.settings.snapshot()
        val cal = Calendar.getInstance()
        val now = GlobalGate.hhmm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val timeOk = GlobalGate.timeAllowed(s.workTimeEnable, s.workStartTime, s.workEndTime, now)
        val roamingOk = GlobalGate.roamingAllowed(s.roamingEnable) {
            ctx.getSystemService(TelephonyManager::class.java)?.isNetworkRoaming == true
        }
        Log.i(TAG, "gate: Enable=${s.appEnable}, Roaming=$roamingOk, timeEnable=$timeOk (start=${s.workStartTime} end=${s.workEndTime} cur=$now)")
        return s.appEnable && timeOk && roamingOk
    }

    /** 전송 결과 이벤트. */
    suspend fun processResult(graph: Graph, rowId: Long, code: Int, detail: String) {
        graph.results.record(rowId, code, "sms", detail)
    }

    /**
     * 문자 경로(SMS·MMS)에서 부르는 알림 접근 점검: 켜진 알림 규칙이 있는데 접근이 없으면 경고를 한 번만 띄운다.
     * 문자 전달은 막지 않는다(반환값 없음).
     */
    private suspend fun warnIfNotiAccessMissing(ctx: Context, graph: Graph) {
        val needsAccess = graph.db.rules().all().any { it.enabled && it.isNotification }
        NotiAccessWarning.check(ctx, needsAccess)
    }

    /**
     * 첫 부족 권한 코드를 구한다. 꺼진 규칙도 훑는다(알림 규칙은 알림 접근, 그 밖의 규칙은 문자 수신 권한).
     */
    private suspend fun missingPermission(ctx: Context, graph: Graph, checkNotificationAccess: Boolean = true): Int {
        val needs = graph.db.rules().all()
            .map {
                PermNeeds.FilterNeed(
                    hasDestination = parseDestinations(it.recipients).isNotEmpty(),
                    usesContactName = it.template.contains(Template.CONTACT),
                    isNotification = it.isNotification,
                )
            }
        val have = PermNeeds.Have(
            phoneState = Perms.hasAll(ctx, Perms.PHONE_STATE),
            smsReceive = Perms.hasAll(ctx, Perms.SMS_RECEIVE),
            smsSend = Perms.hasAll(ctx, Perms.SMS_SEND),
            contacts = Perms.hasAll(ctx, Perms.CONTACTS),
            notificationAccess = Perms.hasNotificationAccess(ctx),
        )
        return PermNeeds.firstMissing(have, needs, checkNotificationAccess)
    }
}
