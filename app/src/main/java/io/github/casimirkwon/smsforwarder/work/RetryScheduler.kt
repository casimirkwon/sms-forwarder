package io.github.casimirkwon.smsforwarder.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.casimirkwon.smsforwarder.core.RetryPolicy
import java.util.concurrent.TimeUnit

/** NetworkRetryWorker 예약: 지연 재시도, 네트워크 연결 대기, 재시작 뒤 처리. */
object RetryScheduler {
    private const val TAG = "RetryScheduler"
    const val KEY_ACTION = "action"
    const val ACTION_RETRY = "retry"
    const val UNIQUE_NETWORK = "retry-when-online"
    private const val PREFS = "retry_state"
    private const val KEY_LAST_ENQUEUE = "last_enqueued_at"

    /**
     * 지연 재시도 워커(제약 없음, key_action=action_retry). 직전 enqueue 로부터 30초 안이면 건너뛴다.
     * 지연 워커는 유일 작업이 아니라 그냥 enqueue 한다.
     */
    fun enqueueRetryWorker(ctx: Context, sec: Int) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (RetryPolicy.shouldSkipEnqueue(sp.getLong(KEY_LAST_ENQUEUE, 0L), now)) {
            Log.i(TAG, "skip enqueueRetryWorker")
            return
        }
        Log.i(TAG, "enqueueRetryWorker ${sec}s")
        sp.edit().putLong(KEY_LAST_ENQUEUE, now).apply()
        val req = OneTimeWorkRequestBuilder<NetworkRetryWorker>()
            .setInputData(workDataOf(KEY_ACTION to ACTION_RETRY))
            .setInitialDelay(sec.toLong(), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    /**
     * 네트워크 연결 제약이 붙은 유일 작업 "retry-when-online"(기존 것이 있으면 KEEP).
     * 재전송 대기(10011126)·네트워크 대기(10000017) 결과가 기록될 때와, 활성 네트워크가 없을 때 호출된다.
     */
    fun enqueueNetworkWorker(ctx: Context) {
        Log.i(TAG, "enqueueWorker")
        val req = OneTimeWorkRequestBuilder<NetworkRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NETWORK, ExistingWorkPolicy.KEEP, req)
    }

    /** 부팅·앱 업데이트 후: 밀린 재전송을 처리하는 워커 1회(expedited, 쿼터 초과 시 일반 작업). */
    fun enqueueOnRestart(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<NetworkRetryWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}
