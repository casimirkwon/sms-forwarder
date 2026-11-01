package io.github.casimirkwon.smsforwarder.sms

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.MmsInbox
import io.github.casimirkwon.smsforwarder.core.SmsMerge
import io.github.casimirkwon.smsforwarder.util.Notifs
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import io.github.casimirkwon.smsforwarder.work.EventProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 서비스 모드의 상주 포그라운드 서비스(foregroundServiceType=remoteMessaging, 상주 알림은 [Notifs.ID_WORKER]).
 * - onCreate: 곧바로 startForeground, 전용 HandlerThread("MsgServiceThread"), SMS_RECEIVED 수신기를 동적 등록
 *   (등록해 둔 동안 매니페스트 수신기는 중복 처리하지 않는다), 서비스 시작 안내 알림([Notifs.ID_HINT]) 지움.
 * - onStartCommand: START_STICKY. 인텐트는 서비스 스레드에서 차례로 처리한다.
 *   ACTION_STOP(설정 스위치를 끌 때)를 받으면 3초 뒤부터 처리 큐가 빌 때까지 5초마다 확인해 stopSelf.
 */
class ForwardingService : Service() {
    companion object {
        private const val TAG = "ForwardingService"
        const val ACTION_START = "io.github.casimirkwon.smsforwarder.action.START"
        const val ACTION_STOP = "io.github.casimirkwon.smsforwarder.action.STOP"
        const val ACTION_SMS = "io.github.casimirkwon.smsforwarder.action.SMS"
        const val ACTION_RESULT = "io.github.casimirkwon.smsforwarder.action.RESULT"
        const val ACTION_MMS = "io.github.casimirkwon.smsforwarder.action.MMS"
        const val ACTION_NOTI = "io.github.casimirkwon.smsforwarder.action.NOTI"
        const val ACTION_DELAY = "io.github.casimirkwon.smsforwarder.action.DELAY"

        /** 이력 항목의 "필터 다시 적용" */
        const val ACTION_REAPPLY = "io.github.casimirkwon.smsforwarder.action.REAPPLY"

        /** 서비스가 SMS 수신기를 동적 등록했는지 */
        @Volatile var registeredByService = false

        /** 서비스가 떠 있는지 */
        @Volatile var running = false

        @Volatile private var instance: ForwardingService? = null

        /** 앱 내부 이벤트 전달: startForegroundService 대신 인텐트를 직접 넘긴다. */
        fun post(intent: Intent) {
            instance?.events?.trySend(intent)
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var scope: CoroutineScope
    private val events = Channel<Intent>(Channel.UNLIMITED)
    private val active = AtomicInteger(0)

    /** 3초 대기·재시도 중인 MMS 읽기 수(stopSelf 점검에 쓴다) */
    private val mmsActive = AtomicInteger(0)
    private val receiver = SmsReceiver(byService = true)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        thread = HandlerThread("MsgServiceThread").also { it.start() }
        handler = Handler(thread.looper)
        scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
        showForeground()
        if (!registeredByService) {
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            // RECEIVER_EXPORTED 로 등록한다. phone 프로세스(uid 1001)가 보내는 브로드캐스트라
            // NOT_EXPORTED 로 등록하면 배달되지 않는다. 매니페스트 수신기와 같이 BROADCAST_SMS 를 요구해 시스템만 보낼 수 있게 한다.
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Manifest.permission.BROADCAST_SMS, null, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter, Manifest.permission.BROADCAST_SMS, null)
            }
            // MMS 알림(WAP push)도 같이 동적 등록한다.
            val wap = IntentFilter(Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION).apply { addDataType(SmsReceiver.MMS_MIME) }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, wap, Manifest.permission.BROADCAST_WAP_PUSH, null, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, wap, Manifest.permission.BROADCAST_WAP_PUSH, null)
            }
            registeredByService = true
        }
        instance = this
        running = true
        Notifs.cancel(this, Notifs.ID_HINT)
        scope.launch {
            for (intent in events) {
                try {
                    handle(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "handle failed: ${intent.action}", e)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        running = false
        instance = null
        if (registeredByService) {
            registeredByService = false
            unregisterReceiver(receiver)
        }
        events.close()
        thread.quitSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            showForeground()
            return START_STICKY
        }
        enqueue(intent)
        return START_STICKY
    }

    private fun enqueue(intent: Intent) {
        active.incrementAndGet()
        if (events.trySend(intent).isFailure) active.decrementAndGet()
    }

    private fun showForeground() {
        try {
            startForeground(Notifs.ID_WORKER, Notifs.serviceNotification(this))
            handler.removeCallbacks(stopCheck)
            Log.i(TAG, "register foreground Notification")
        } catch (e: Exception) {
            Log.e(TAG, "fail foreground Notification", e)
        }
    }

    private val stopCheck = Runnable {
        val graph = (application as App).graph
        val busy = active.get() > 0 || mmsActive.get() > 0 || graph.queue.pendingCount > 0
        Log.i(TAG, "UNREGISTER_NOTI: busy=$busy")
        if (busy) rescheduleStopCheck(5000) else try { stopSelf() } catch (_: Exception) { }
    }

    private fun rescheduleStopCheck(delayMs: Long) {
        handler.removeCallbacks(stopCheck)
        handler.postDelayed(stopCheck, delayMs)
    }

    /** onStartCommand 로 들어온 인텐트 한 건의 처리. */
    private suspend fun handle(intent: Intent) {
        val graph = (application as App).graph
        Log.i(TAG, "onStartCommand: ${intent.action}")
        when (intent.action) {
            ACTION_START -> {
                showForeground()
                return
            }
            ACTION_STOP -> {
                rescheduleStopCheck(3000)
                return
            }
        }
        if (Build.VERSION.SDK_INT < 31) showForeground()
        when (intent.action) {
            ACTION_RESULT -> {
                val rowId = intent.getLongExtra("deliveryId", -1)
                if (rowId != -1L) {
                    EventProcessor.processResult(graph, rowId, intent.getIntExtra("status", 0), intent.getStringExtra("detail") ?: "")
                }
            }
            ACTION_DELAY -> EventProcessor.processDelay(this, graph)
            ACTION_REAPPLY -> EventProcessor.processReapply(graph, intent.getLongExtra("receivedId", -1))
            ACTION_MMS -> {
                if (!graph.settings.snapshot().appEnable) {
                    Log.i(TAG, "app disabled, drop")
                    return
                }
                val url = intent.getStringExtra("mmsUrl")
                val initTime = intent.getLongExtra("mmsStart", System.currentTimeMillis())
                // 3초 뒤 읽고 못 찾으면 6초마다 다시 읽는다. 다른 이벤트를 막지 않도록 따로 돌린다.
                mmsActive.incrementAndGet()
                scope.launch {
                    try {
                        EventProcessor.processMms(this@ForwardingService, graph, url, initTime, firstDelayMs = MmsInbox.FIRST_DELAY_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "mms failed", e)
                    } finally {
                        mmsActive.decrementAndGet()
                    }
                }
            }
            ACTION_NOTI -> EventProcessor.processNotification(this, graph, Dispatcher.notificationFromIntent(intent))
            ACTION_SMS -> {
                if (!graph.settings.snapshot().appEnable) {
                    Log.i(TAG, "app disabled, drop")
                    return
                }
                val parts = SmsIntentParser.parts(intent)
                val messages = SmsMerge.mergeParts(parts)
                val slot = SimUtil.slotFromExtras(this, intent.extras)
                Log.i(TAG, "SMS event: parts=${parts.size} messages=${messages.size} simSlot=$slot")
                if (messages.isEmpty()) {
                    Log.e(TAG, "onStartCommand: msgs is null")
                    return
                }
                EventProcessor.processSms(this, graph, messages, slot)
            }
        }
    }
}
