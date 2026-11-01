package io.github.casimirkwon.smsforwarder.core

import kotlin.math.abs

/**
 * 실패 재시도·대기열 판정식.
 * - 재시도 가능: retry_count < http_retry
 * - 첫 재시도 60초 / 이후 180초, 첫 재시도는 last_retry_time 을 now-121초로 기록
 * - 재전송 대상 행의 판정: last<=0 이거나 |now-last+30초| >= 180초 (NetworkRetryWorker)
 * - 남은 행이 있으면 max((가장 이른 재시도 시각-now)/1000, 30)초 뒤 재예약
 * - 재시도 워커 enqueue 는 직전 enqueue 로부터 30초 안이면 건너뜀
 */
object RetryPolicy {
    const val FIRST_DELAY_SEC = 60
    const val NEXT_DELAY_SEC = 180
    const val FIRST_BACKDATE_MS = 121_000L
    const val DUE_WINDOW_MS = 180_000L
    const val DUE_SLACK_MS = 30_000L
    const val MIN_RESCHEDULE_SEC = 30
    const val ENQUEUE_THROTTLE_MS = 30_000L

    fun canRetry(retryCount: Int, maxRetry: Int) = retryCount < maxRetry

    /** 재시도 예약 한 건: 워커 지연(초), 행에 기록할 last_retry_time, 새 retry_count. */
    data class Plan(val delaySec: Int, val lastRetryTime: Long, val newCount: Int)

    fun plan(retryCount: Int, now: Long): Plan =
        if (retryCount > 0) Plan(NEXT_DELAY_SEC, now, retryCount + 1)
        else Plan(FIRST_DELAY_SEC, now - FIRST_BACKDATE_MS, retryCount + 1)

    /** NetworkRetryWorker 가 이 행을 지금 다시 보낼지. */
    fun isDue(lastRetryTime: Long, now: Long): Boolean =
        lastRetryTime <= 0 || abs(now - lastRetryTime + DUE_SLACK_MS) >= DUE_WINDOW_MS

    /** 아직 때가 아닌 행이 다시 보내질 시각(재예약 계산용). */
    fun dueAt(lastRetryTime: Long): Long = lastRetryTime + DUE_WINDOW_MS

    /** 재예약 지연(초). 남은 행이 없으면(earliestDueAt == Long.MAX_VALUE) null. */
    fun rescheduleDelaySec(earliestDueAt: Long, now: Long): Int? =
        if (earliestDueAt >= Long.MAX_VALUE) null
        else maxOf(((earliestDueAt - now) / 1000).toInt(), MIN_RESCHEDULE_SEC)

    fun shouldSkipEnqueue(lastEnqueueTime: Long, now: Long): Boolean =
        abs(now - lastEnqueueTime) < ENQUEUE_THROTTLE_MS
}
