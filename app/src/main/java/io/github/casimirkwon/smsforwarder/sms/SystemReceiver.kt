package io.github.casimirkwon.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.DelayPolicy
import io.github.casimirkwon.smsforwarder.core.DeliveryReport
import io.github.casimirkwon.smsforwarder.core.RunMode
import io.github.casimirkwon.smsforwarder.util.Notifs
import io.github.casimirkwon.smsforwarder.util.Perms
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import io.github.casimirkwon.smsforwarder.work.RetryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 전송 결과(MESSAGE_SENT)·도착 보고(DELIVERED) 처리와 부팅·앱 업데이트 후 처리.
 * exported=false 로 두어 외부 앱이 결과 인텐트를 위조할 수 없게 했다
 * (시스템의 BOOT_COMPLETED·MY_PACKAGE_REPLACED 는 시스템 uid 가 보내므로 exported=false 여도 들어온다).
 */
class SystemReceiver : BroadcastReceiver() {
    private val tag = "SystemReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(tag, "onReceive: ${intent.action}")
        val graph = (context.applicationContext as App).graph
        when (intent.action) {
            SmsSender.ACTION_SENT -> {
                val rowId = intent.dataString?.toLongOrNull() ?: return
                val resultCode = resultCode
                val errorCode = if (intent.hasExtra("errorCode")) intent.getIntExtra("errorCode", 0) else null
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        // 결과 코드 정규화(+ 재시도 예약)는 수신기에서, 기록은 서비스/워커 경로에서 한다.
                        val code = graph.results.normalize(rowId, resultCode)
                        val detail = "resultCode=$resultCode" + (errorCode?.let { ", errorCode=$it" } ?: "")
                        Dispatcher.dispatchResult(context, graph, rowId, code, detail)
                    } catch (e: Exception) {
                        Log.e(tag, "result handling failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            SmsSender.ACTION_DELIVERED -> {
                // 상대 폰 도착 보고(통신사가 지원할 때만 온다). 이력 표시용 기록만 하고 다른 처리는 하지 않는다.
                val key = DeliveryReport.parseKey(intent.dataString) ?: return
                val resultCode = resultCode
                val status = deliveryStatus(intent)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        graph.results.recordDeliveryReport(key, resultCode, status)
                    } catch (e: Exception) {
                        Log.e(tag, "delivery report handling failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            DelayAlarm.ACTION -> {
                // 지연 전달 알람이 울리면 지연 전송 처리를 돌린다.
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        Dispatcher.dispatchDelay(context, graph)
                    } catch (e: Exception) {
                        Log.e(tag, "delay alarm failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> restart(context, graph, "boot completed", recoverDelay = true)
            // 앱 업데이트 때 알람 복구는 지연 전달을 모든 API 에서 켠 경우(LEGACY_SDK_GATE=false)에만 한다.
            Intent.ACTION_MY_PACKAGE_REPLACED -> restart(context, graph, "package replaced", recoverDelay = !DelayPolicy.LEGACY_SDK_GATE)
        }
    }

    /** 도착 보고 인텐트의 PDU(pdu, format)에서 상태 값을 읽는다. 읽을 수 없으면 null. */
    private fun deliveryStatus(intent: Intent): Int? = try {
        val pdu = intent.getByteArrayExtra("pdu")
        if (pdu == null) null else SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))?.status
    } catch (e: Exception) {
        Log.w(tag, "delivery report pdu unreadable: ${e.javaClass.simpleName}")
        null
    }

    /**
     * 예약된 지연 전달 알람 복구(부팅 후): 예약 행(10000003)의 전송 시각 중
     * 지금+60초보다 먼 것은 알람을 다시 걸고, 이미 지났거나 1분 안인 건이 있으면 지연 전송 처리를 곧바로 돌린다.
     * 지연 전달이 적용되는 API(기본 30 이하, [DelayPolicy.appliesOnSdk])에서만 한다.
     */
    private suspend fun recoverDelayAlarms(context: Context, graph: io.github.casimirkwon.smsforwarder.Graph) {
        if (!DelayPolicy.appliesOnSdk(android.os.Build.VERSION.SDK_INT)) return
        val times = graph.db.deliveries().scheduledRows().map { it.sendTime }
        Log.i(tag, "bootComplete - times=$times")
        if (times.isEmpty()) return
        val plan = DelayPolicy.recover(times, System.currentTimeMillis())
        for (t in plan.alarms) {
            try {
                DelayAlarm.register(context, t)
            } catch (e: Exception) {
                Log.e(tag, "re-register failed: $t", e)
            }
        }
        if (plan.runNow) Dispatcher.dispatchDelay(context, graph)
    }

    /**
     * 부팅·앱 업데이트 뒤 재시작 처리. 앱 사용이 켜져 있을 때만:
     * 서비스 모드면 규칙이 하나라도 있을 때 서비스를 기동하고(실패했는데 배터리 최적화 예외도 아니면 경고),
     * 워커 모드면 규칙이 있는데 배터리 최적화 예외가 아닐 때 경고한다. 그리고 규칙 유무와 상관없이
     * 밀린 재전송을 처리하는 NetworkRetryWorker 를 1회 enqueue 한다.
     * 지연 전달 알람 복구는 앱 사용 여부와 상관없이 먼저 한다.
     */
    private fun restart(context: Context, graph: io.github.casimirkwon.smsforwarder.Graph, reason: String, recoverDelay: Boolean) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (recoverDelay) recoverDelayAlarms(context, graph)
                val s = graph.settings.snapshot()
                Log.i(tag, "restart: reason=$reason enable=${s.appEnable}")
                if (!s.appEnable) return@launch
                val workerMode = Dispatcher.isWorkerMode(s.useForegroundService)
                val hasFilters = graph.db.rules().count() > 0
                val ignoring = Perms.ignoringBatteryOptimizations(context)
                var plan = RunMode.restartPlan(workerMode, hasFilters, ignoring)
                if (plan.startService) {
                    val ok = Dispatcher.startService(context)
                    Log.i(tag, "restart: service start ok=$ok")
                    plan = RunMode.restartPlan(workerMode, hasFilters, ignoring, serviceStartFailed = !ok)
                }
                if (plan.warnBatteryOrPermission) Notifs.startupWarning(context)
                RetryScheduler.enqueueOnRestart(context)
            } catch (e: Exception) {
                Log.e(tag, "restart failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
