package io.github.casimirkwon.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.IncomingSms
import io.github.casimirkwon.smsforwarder.util.Notifs
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 이벤트 처리 워커. Room 에 저장된 이벤트(수신 SMS 또는 전송 결과)를 읽어 처리하고,
 * 전송 큐가 빌 때까지 5초 간격으로 최대 180초 기다린 뒤 끝낸다.
 */
class DeliveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_INDEX = "eventId"
        private const val WAIT_STEP_MS = 5_000L
        private const val WAIT_MAX_MS = 180_000L
    }

    override suspend fun doWork(): Result {
        val graph = (applicationContext as App).graph
        val idx = inputData.getLong(KEY_INDEX, -1)
        val item = graph.db.pendingEvents().get(idx)
        if (item == null) {
            Log.w("DeliveryWorker", "no data for index=$idx")
            return Result.success()
        }
        try {
            val json = JSONObject(item.json)
            when (json.optString("type", Dispatcher.TYPE_SMS)) {
                Dispatcher.TYPE_RESULT ->
                    EventProcessor.processResult(graph, json.getLong("deliveryId"), json.getInt("result"), json.optString("detail"))
                Dispatcher.TYPE_DELAY -> EventProcessor.processDelay(applicationContext, graph)
                Dispatcher.TYPE_REAPPLY -> EventProcessor.processReapply(graph, json.optLong("receivedId", -1))
                Dispatcher.TYPE_NOTI -> EventProcessor.processNotification(applicationContext, graph, Dispatcher.notificationFromJson(json))
                // 워커는 3초 초기 지연 뒤에 돌므로 첫 시도 전 추가 대기는 없다. 읽기는 워커마다 한 번이고, 더 읽어야 하면 다음 워커를 예약한다.
                Dispatcher.TYPE_MMS -> {
                    val url = if (json.isNull("mmsUrl")) null else json.optString("mmsUrl")
                    val initTime = json.optLong("mmsStart", System.currentTimeMillis())
                    val attempt = json.optInt("mmsAttempt", 1)
                    val next = EventProcessor.processMmsAttempt(applicationContext, graph, url, initTime, attempt)
                    if (next != null) Dispatcher.requeueMms(applicationContext, graph, url, initTime, attempt + 1, next)
                }
                else -> {
                    val slot = json.optInt("simSlot", -1)
                    val arr = json.getJSONArray("parts")
                    val messages = (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        IncomingSms(o.getString("inNumber"), o.getString("text"), o.getLong("receiveTime"))
                    }
                    EventProcessor.processSms(applicationContext, graph, messages, slot)
                }
            }
            var waited = 0L
            while (graph.queue.pendingCount > 0 && waited < WAIT_MAX_MS) {
                delay(WAIT_STEP_MS)
                waited += WAIT_STEP_MS
            }
        } catch (e: Exception) {
            Log.e("DeliveryWorker", "failed", e)
        } finally {
            graph.db.pendingEvents().delete(idx)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifs.ID_WORKER, Notifs.workerNotification(applicationContext))
}
