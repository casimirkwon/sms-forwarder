package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SentResultTest {
    private val consts = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    @Test fun okIsSuccess() {
        assertEquals(SendResult.SUCCESS, SentResult.normalize(-1, consts, true, true))
    }

    @Test fun zeroStaysZero() {
        assertEquals(0, SentResult.normalize(0, consts, true, true))
    }

    @Test fun retryCandidateKeepsRetryWaitWhileRetriesRemain() {
        assertEquals(SendResult.RETRY_WAIT, SentResult.normalize(1, consts, retryOnFailure = true, canRetry = true))
        assertEquals(SendResult.RETRY_WAIT, SentResult.normalize(4, consts, true, true))
        assertEquals(SendResult.RETRY_WAIT, SentResult.normalize(124, consts, true, true))
    }

    @Test fun retryCandidateBecomesPermanentWhenExhaustedOrDisabled() {
        assertEquals(SendResult.PERMANENT_FAIL, SentResult.normalize(1, consts, true, false))
        assertEquals(SendResult.PERMANENT_FAIL, SentResult.normalize(1, consts, false, true))
    }

    @Test fun otherKnownSmsManagerResultIsPermanent() {
        // 3(RESULT_ERROR_NULL_PDU 류) 은 재시도 후보가 아니고 SmsManager 상수 → 영구 실패
        assertEquals(SendResult.PERMANENT_FAIL, SentResult.normalize(3, consts, true, true))
    }

    @Test fun unknownCodeIsKept() {
        assertEquals(SendResult.EMPTY_PARAM, SentResult.normalize(SendResult.EMPTY_PARAM, consts, true, true))
    }

    @Test fun retryCandidateMatchIsExact() {
        // 1 은 후보지만 100 은 아니다
        assertEquals(100, SentResult.normalize(100, emptySet(), true, true))
    }
}
