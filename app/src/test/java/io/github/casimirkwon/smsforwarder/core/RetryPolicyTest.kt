package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun firstRetryIs60sAndBackdated() {
        val p = RetryPolicy.plan(0, now)
        assertEquals(60, p.delaySec)
        assertEquals(now - 121_000, p.lastRetryTime)
        assertEquals(1, p.newCount)
    }

    @Test fun laterRetriesAre180s() {
        val p = RetryPolicy.plan(1, now)
        assertEquals(180, p.delaySec)
        assertEquals(now, p.lastRetryTime)
        assertEquals(2, p.newCount)
        assertEquals(180, RetryPolicy.plan(2, now).delaySec)
    }

    @Test fun canRetryUntilMax() {
        // http_retry=3 → retry_count 0,1,2 는 가능, 3 부터 불가 (총 3회 재시도)
        assertTrue(RetryPolicy.canRetry(0, 3))
        assertTrue(RetryPolicy.canRetry(2, 3))
        assertFalse(RetryPolicy.canRetry(3, 3))
        assertFalse(RetryPolicy.canRetry(0, 0))
    }

    @Test fun scheduleSequenceMatchesThreeRetries() {
        var count = 0
        val delays = ArrayList<Int>()
        while (RetryPolicy.canRetry(count, 3)) {
            val p = RetryPolicy.plan(count, now)
            delays += p.delaySec
            count = p.newCount
        }
        assertEquals(listOf(60, 180, 180), delays)
    }

    @Test fun dueWhenNeverRetried() {
        assertTrue(RetryPolicy.isDue(0, now))
        assertTrue(RetryPolicy.isDue(-5, now))
    }

    @Test fun dueBoundaryIs150sElapsed() {
        // |now-last+30s| < 180s 이면 건너뜀 → 경과 150초 미만은 건너뜀, 150초 이상은 재전송
        assertFalse(RetryPolicy.isDue(now - 149_999, now))
        assertTrue(RetryPolicy.isDue(now - 150_000, now))
        assertTrue(RetryPolicy.isDue(now - 400_000, now))
    }

    @Test fun firstRetryRowIsDueWhenTheDelayedWorkerRuns() {
        // 첫 실패: last = t-121s. 60초 뒤 워커가 돌면 경과 181초 → 재전송
        val t = now
        val last = RetryPolicy.plan(0, t).lastRetryTime
        assertTrue(RetryPolicy.isDue(last, t + 60_000))
        // 같은 시각(네트워크 워커가 즉시 돌 때)에는 경과 121초 → 건너뛰고 약 59초 뒤 재예약
        assertFalse(RetryPolicy.isDue(last, t))
        assertEquals(59, RetryPolicy.rescheduleDelaySec(RetryPolicy.dueAt(last), t))
    }

    @Test fun secondRetryRowWaits180s() {
        val last = RetryPolicy.plan(1, now).lastRetryTime
        assertFalse(RetryPolicy.isDue(last, now + 100_000))
        assertTrue(RetryPolicy.isDue(last, now + 180_000))
    }

    @Test fun rescheduleDelayHasFloorOf30s() {
        assertEquals(30, RetryPolicy.rescheduleDelaySec(now + 5_000, now))
        assertEquals(30, RetryPolicy.rescheduleDelaySec(now - 5_000, now))
        assertEquals(100, RetryPolicy.rescheduleDelaySec(now + 100_000, now))
        assertNull(RetryPolicy.rescheduleDelaySec(Long.MAX_VALUE, now))
    }

    @Test fun enqueueThrottle30s() {
        assertTrue(RetryPolicy.shouldSkipEnqueue(now - 29_999, now))
        assertFalse(RetryPolicy.shouldSkipEnqueue(now - 30_000, now))
        assertFalse(RetryPolicy.shouldSkipEnqueue(0, now))
    }
}
