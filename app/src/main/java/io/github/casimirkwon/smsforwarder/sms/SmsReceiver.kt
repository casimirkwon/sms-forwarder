package io.github.casimirkwon.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.SmsMerge
import io.github.casimirkwon.smsforwarder.core.WapPushParser
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMS 수신기. 매니페스트 등록(우선순위 최대)과 서비스의 동적 등록(byService = true) 두 형태.
 * 서비스가 동적으로 등록해 둔 동안에는 매니페스트 쪽 수신기가 중복 처리하지 않는다(ForwardingService.registeredByService).
 * 기본 문자 앱이 아니며 SMS_RECEIVED 브로드캐스트만 받는다.
 */
open class SmsReceiver(private val byService: Boolean = false) : BroadcastReceiver() {
    companion object {
        const val MMS_MIME = "application/vnd.wap.mms-message"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!byService && ForwardingService.registeredByService) return
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> onSmsReceived(context, intent)
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> onWapPush(context, intent)
        }
    }

    /**
     * MMS 알림(WAP push) 수신: data 를 PDU 로 읽어 m-notification-ind 이면
     * content-location 을 얻고(실패해도 url 없이 계속), 처리 경로로 보낸다. 실제 MMS 는 3초 뒤에 content://mms 에서 읽는다.
     */
    private fun onWapPush(context: Context, intent: Intent) {
        if (intent.type != MMS_MIME) return
        val graph = (context.applicationContext as App).graph
        val initTime = System.currentTimeMillis()
        val url = try {
            WapPushParser.contentLocation(intent.getByteArrayExtra("data"))
        } catch (_: Exception) {
            null
        }
        Log.i("SmsReceiver", "Receive MMS, hasUrl=${url != null}")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!graph.settings.snapshot().appEnable) return@launch
                Dispatcher.dispatchMms(context, graph, url, initTime)
            } catch (e: Exception) {
                Log.e("SmsReceiver", "mms dispatch failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun onSmsReceived(context: Context, intent: Intent) {
        val graph = (context.applicationContext as App).graph
        val pending = goAsync()
        // 서비스 모드는 인텐트 그대로(pdus 포함) 서비스에 넘긴다. 워커 모드는 여기서 풀어 Room 에 저장한다.
        val copy = Intent(intent)
        val parts = SmsIntentParser.parts(intent)
        val extras = intent.extras
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = graph.settings.snapshot()
                Log.i("SmsReceiver", "onReceive: byService=$byService enable=${s.appEnable} workerMode=${Dispatcher.isWorkerMode(s.useForegroundService)}")
                if (!s.appEnable) return@launch
                if (!Dispatcher.isWorkerMode(s.useForegroundService)) {
                    copy.setAction(ForwardingService.ACTION_SMS)
                    copy.setClass(context, ForwardingService::class.java)
                    if (Dispatcher.dispatchToService(context, copy)) return@launch
                    // 서비스를 띄울 수 없으면 문자를 놓치지 않도록 워커 경로로 처리한다(안전 장치).
                }
                val slot = SimUtil.slotFromExtras(context, extras)
                val messages = SmsMerge.mergeParts(parts)
                Log.i("SmsReceiver", "SMS received: parts=${parts.size} messages=${messages.size} simSlot=$slot")
                if (messages.isNotEmpty()) Dispatcher.dispatchSms(context, graph, messages, slot)
            } catch (e: Exception) {
                Log.e("SmsReceiver", "dispatch failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * MMS 알림(WAP_PUSH_RECEIVED) 매니페스트 수신기. 안드로이드는 수신기마다 권한을 하나만 걸 수 있어 SMS 수신기(BROADCAST_SMS)와
 * 나눴다. 처리는 [SmsReceiver] 와 같다.
 */
class WapPushReceiver : SmsReceiver()
