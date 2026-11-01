package io.github.casimirkwon.smsforwarder.work

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.core.IncomingNotification
import io.github.casimirkwon.smsforwarder.core.IncomingSms
import io.github.casimirkwon.smsforwarder.core.MmsInbox
import io.github.casimirkwon.smsforwarder.core.RunMode
import io.github.casimirkwon.smsforwarder.db.PendingEvent
import io.github.casimirkwon.smsforwarder.sms.ForwardingService
import io.github.casimirkwon.smsforwarder.util.Notifs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 이벤트를 처리 경로로 보낸다.
 * - 워커 모드(API 31 이상 + "포그라운드 서비스 사용" 꺼짐): 이벤트를 Room(worker_data_item_table)에 JSON 으로 저장하고
 *   행 index 만 expedited DeliveryWorker 에 넘긴다.
 * - 서비스 모드(API 30 이하 또는 스위치 켬): 서비스가 떠 있으면 앱 내부 전달, 아니면 안내 알림을 올리고
 *   startForegroundService.
 */
object Dispatcher {
    private const val TAG = "Dispatcher"

    fun isWorkerMode(useForegroundService: Boolean) = RunMode.isWorkerMode(Build.VERSION.SDK_INT, useForegroundService)

    suspend fun isWorkerMode(graph: Graph) = isWorkerMode(graph.settings.snapshot().useForegroundService)

    /** 서비스 모드 경로. 보내는 데 실패하면 false. */
    fun dispatchToService(ctx: Context, intent: Intent): Boolean {
        return try {
            if (ForwardingService.running) {
                Log.i(TAG, "send event instead of startForegroundService: ${intent.action}")
                ForwardingService.post(intent)
            } else {
                Notifs.startServiceHint(ctx)
                ctx.startForegroundService(intent)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "dispatchToService failed: ${intent.action}", e)
            false
        }
    }

    /** 서비스 기동(ACTION.foreground). 워커 모드에서는 서비스를 쓰지 않으므로 서비스 모드일 때만 의미가 있다. */
    fun startService(ctx: Context): Boolean =
        dispatchToService(ctx, Intent(ctx, ForwardingService::class.java).setAction(ForwardingService.ACTION_START))

    suspend fun dispatchSms(ctx: Context, graph: Graph, messages: List<IncomingSms>, simSlot: Int, delayMs: Long = 0) {
        val json = JSONObject().apply {
            put("type", TYPE_SMS)
            put("simSlot", simSlot)
            put(
                "parts",
                JSONArray().also { arr ->
                    for (m in messages) {
                        arr.put(JSONObject().put("text", m.body).put("inNumber", m.address).put("receiveTime", m.timeMillis))
                    }
                },
            )
        }
        enqueueWorker(ctx, graph, json, delayMs)
    }

    /**
     * 알림 이벤트 처리 요청(알림 리스너 → `ACTION_NOTI` 인텐트, 지연 없음).
     * 워커 모드는 Room 저장 후 expedited 워커, 서비스 모드는 서비스 인텐트(서비스를 띄우지 못하면 워커).
     */
    suspend fun dispatchNotification(ctx: Context, graph: Graph, n: IncomingNotification) {
        if (!isWorkerMode(graph)) {
            val intent = Intent(ctx, ForwardingService::class.java)
                .setAction(ForwardingService.ACTION_NOTI)
                .putExtra("title", n.title)
                .putExtra("text", n.text)
                .putExtra("subText", n.subText)
                .putExtra("bigText", n.bigText)
                .putExtra("messageText", n.messageText)
                .putExtra("oldText", n.oldText)
                .putExtra("time", n.timeMillis)
                .putExtra("pkg", n.packageName)
                .putExtra("images", n.imageCount)
                .putExtra("extra", n.extraContent)
            if (dispatchToService(ctx, intent)) return
        }
        val json = JSONObject().apply {
            put("type", TYPE_NOTI)
            put("title", n.title)
            put("text", n.text)
            put("subText", n.subText)
            put("bigText", n.bigText)
            put("messageText", n.messageText)
            put("oldText", n.oldText)
            put("time", n.timeMillis)
            put("pkg", n.packageName)
            put("images", n.imageCount)
            put("extra", n.extraContent)
        }
        enqueueWorker(ctx, graph, json, 0)
    }

    /** 이벤트 JSON(워커) 또는 인텐트(서비스)에서 읽은 알림 필드로 복원한다. */
    fun notificationFromJson(json: JSONObject) = IncomingNotification(
        title = json.optString("title"),
        text = json.optString("text"),
        subText = json.optString("subText"),
        bigText = json.optString("bigText"),
        messageText = json.optString("messageText"),
        oldText = json.optString("oldText"),
        timeMillis = json.optLong("time", System.currentTimeMillis()),
        packageName = json.optString("pkg"),
        imageCount = json.optInt("images", 0),
        extraContent = json.optString("extra"),
    )

    fun notificationFromIntent(intent: Intent) = IncomingNotification(
        title = intent.getStringExtra("title") ?: "",
        text = intent.getStringExtra("text") ?: "",
        subText = intent.getStringExtra("subText") ?: "",
        bigText = intent.getStringExtra("bigText") ?: "",
        messageText = intent.getStringExtra("messageText") ?: "",
        oldText = intent.getStringExtra("oldText") ?: "",
        timeMillis = intent.getLongExtra("time", System.currentTimeMillis()),
        packageName = intent.getStringExtra("pkg") ?: "",
        imageCount = intent.getIntExtra("images", 0),
        extraContent = intent.getStringExtra("extra") ?: "",
    )

    /**
     * 지연 전달 알람 처리 요청(알람 수신 → `ACTION_DELAY` 인텐트).
     * 워커 모드는 Room 저장 후 워커, 서비스 모드는 서비스 인텐트. 서비스를 띄울 수 없으면 워커로 처리한다.
     */
    suspend fun dispatchDelay(ctx: Context, graph: Graph) {
        if (!isWorkerMode(graph)) {
            val intent = Intent(ctx, ForwardingService::class.java).setAction(ForwardingService.ACTION_DELAY)
            if (dispatchToService(ctx, intent)) return
        }
        enqueueWorker(ctx, graph, JSONObject().put("type", TYPE_DELAY), 0)
    }

    /**
     * 이력 항목의 "필터 다시 적용" 요청(`ACTION_REAPPLY` 인텐트, extra `receivedId`). 서비스 모드는 서비스 인텐트,
     * 워커 모드는 Room 저장 후 워커. 서비스를 띄울 수 없으면 워커로 처리한다.
     */
    suspend fun dispatchReapply(ctx: Context, graph: Graph, historyId: Long) {
        if (!isWorkerMode(graph)) {
            val intent = Intent(ctx, ForwardingService::class.java)
                .setAction(ForwardingService.ACTION_REAPPLY)
                .putExtra("receivedId", historyId)
            if (dispatchToService(ctx, intent)) return
        }
        enqueueWorker(ctx, graph, JSONObject().put("type", TYPE_REAPPLY).put("receivedId", historyId), 0)
    }

    /**
     * WAP push(MMS 알림) 수신 처리 요청(`ACTION_MMS`, 첫 읽기는 3초 뒤).
     * 워커 모드는 3초 초기 지연 워커. 서비스 모드는 지연 없이 인텐트를 바로 보내고
     * 서비스가 3초 뒤에 읽는다.
     */
    suspend fun dispatchMms(ctx: Context, graph: Graph, url: String?, initTime: Long) {
        if (!isWorkerMode(graph)) {
            val intent = Intent(ctx, ForwardingService::class.java)
                .setAction(ForwardingService.ACTION_MMS)
                .putExtra("mmsUrl", url)
                .putExtra("mmsStart", initTime)
            if (dispatchToService(ctx, intent)) return
        }
        val json = JSONObject().put("type", TYPE_MMS).put("mmsUrl", url ?: JSONObject.NULL).put("mmsStart", initTime)
        enqueueWorker(ctx, graph, json, MmsInbox.FIRST_DELAY_MS)
    }

    /** 워커 모드 MMS 재시도: 다음 시도 번호로 워커를 [delayMs] 뒤에 다시 예약한다. */
    suspend fun requeueMms(ctx: Context, graph: Graph, url: String?, initTime: Long, attempt: Int, delayMs: Long) {
        val json = JSONObject().put("type", TYPE_MMS).put("mmsUrl", url ?: JSONObject.NULL)
            .put("mmsStart", initTime).put("mmsAttempt", attempt)
        enqueueWorker(ctx, graph, json, delayMs)
    }

    /** 전송 결과를 처리 경로로 보낸다. 워커 모드는 Room 저장 후 워커, 서비스 모드는 ACTION_RESULT 인텐트. */
    suspend fun dispatchResult(ctx: Context, graph: Graph, rowId: Long, code: Int, detail: String) {
        if (isWorkerMode(graph)) {
            val json = JSONObject().put("type", TYPE_RESULT).put("deliveryId", rowId).put("result", code).put("detail", detail)
            enqueueWorker(ctx, graph, json, 0)
        } else {
            val intent = Intent(ctx, ForwardingService::class.java)
                .setAction(ForwardingService.ACTION_RESULT)
                .putExtra("deliveryId", rowId)
                .putExtra("status", code)
                .putExtra("detail", detail)
            if (!dispatchToService(ctx, intent)) {
                // 서비스를 띄우지 못하면 결과가 사라지지 않도록 직접 기록한다.
                graph.results.record(rowId, code, "sms", detail)
            }
        }
    }

    private suspend fun enqueueWorker(ctx: Context, graph: Graph, json: JSONObject, delayMs: Long) {
        val idx = graph.db.pendingEvents().insert(PendingEvent(json = json.toString(), createdAt = System.currentTimeMillis()))
        val req = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInputData(workDataOf(DeliveryWorker.KEY_INDEX to idx))
            .apply {
                if (delayMs > 0) setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("deliver-$idx", ExistingWorkPolicy.KEEP, req)
    }

    const val TYPE_SMS = "sms"
    const val TYPE_RESULT = "result"
    const val TYPE_DELAY = "delay"
    const val TYPE_MMS = "mms"
    const val TYPE_NOTI = "noti"
    const val TYPE_REAPPLY = "reapply"
}
