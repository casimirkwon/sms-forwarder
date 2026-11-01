package io.github.casimirkwon.smsforwarder.work

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.RetryPolicy
import io.github.casimirkwon.smsforwarder.core.SendResult
import io.github.casimirkwon.smsforwarder.util.Notifs
import kotlinx.coroutines.delay

/**
 * 재전송 워커. 오프라인 큐와 SMS 실패 재시도를 같은 워커가 처리한다.
 * 1) setting_waiting_network 가 켜져 있고 활성 네트워크가 없으면 네트워크 제약 워커를 예약하고 끝낸다.
 * 2) 결과가 10000017(네트워크 대기)·10011126(재시도 대기)인 행을 오래된 것부터 읽는다.
 *    last_retry_time 이 없거나 마지막 시도 후 약 150초(180초-30초) 지났으면 전송 중(2)으로 되돌려 다시 보낸다.
 * 3) 아직 때가 아닌 행이 있으면 가장 이른 시각까지 남은 시간(최소 30초) 뒤로 재예약한다.
 */
class NetworkRetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        private const val TAG = "NetworkRetryWorker"
        private const val WAIT_STEP_MS = 5_000L
        private const val WAIT_MAX_MS = 180_000L
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val graph = (ctx as App).graph
        Log.i(TAG, "doWork: ${inputData.getString(RetryScheduler.KEY_ACTION)}")
        if (graph.settings.snapshot().waitingNetwork) {
            val network = ctx.getSystemService(ConnectivityManager::class.java).activeNetwork
            Log.i(TAG, "isConnected: $network")
            if (network == null) {
                RetryScheduler.enqueueNetworkWorker(ctx)
                return Result.success()
            }
        }
        val now = System.currentTimeMillis()
        var earliestDue = Long.MAX_VALUE
        var resent = 0
        for (row in graph.db.deliveries().retryRows()) {
            Log.i(TAG, "doWork: row=${row.id} result=${row.status} retry=${row.retryCount} last=${row.lastRetryTime}")
            if (RetryPolicy.isDue(row.lastRetryTime, now)) {
                Log.i(TAG, "doWork: retry=${row.retryCount} row=${row.id}")
                graph.db.deliveries().updateStatus(row.id, SendResult.SENDING, System.currentTimeMillis())
                graph.queue.enqueue(row.id, fresh = false)
                resent++
            } else {
                earliestDue = minOf(earliestDue, RetryPolicy.dueAt(row.lastRetryTime))
                Log.i(TAG, "doWork: skip remain=${(earliestDue - now) / 1000}s")
            }
        }
        RetryPolicy.rescheduleDelaySec(earliestDue, now)?.let { RetryScheduler.enqueueRetryWorker(ctx, it) }
        // 전송 호출이 끝날 때까지 프로세스를 붙잡아 둔다(DeliveryWorker 와 같은 방식, 최대 180초).
        var waited = 0L
        while (resent > 0 && graph.queue.pendingCount > 0 && waited < WAIT_MAX_MS) {
            delay(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifs.ID_WORKER, Notifs.workerNotification(applicationContext))
}
