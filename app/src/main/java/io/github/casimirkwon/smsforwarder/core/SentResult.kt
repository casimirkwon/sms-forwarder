package io.github.casimirkwon.smsforwarder.core

object SentResult {
    /** 재시도할 만한 전송 실패 코드(GENERIC_FAILURE, NO_SERVICE, 네트워크·모뎀 오류, RIL 101/124 등). */
    val DEFAULT_RETRY_RESULTS: Set<Int> = setOf(1, 4, 10, 16, 17, 32, 101, 124)

    const val RESULT_OK = -1
    const val RESULT_CANCELED = 0

    /**
     * sentIntent 결과 코드 → 저장 코드.
     * -1(RESULT_OK)=성공, 0=실패(0), 재시도 후보 집합에 있으면 재시도 대기, SmsManager.RESULT_* 상수면 영구 실패.
     * 재시도 대기는 "실패 재시도" 설정이 켜져 있고 재시도 횟수가 남았을 때만 유지하고, 아니면 영구 실패.
     */
    fun normalize(
        resultCode: Int,
        knownSmsResultConstants: Set<Int>,
        retryOnFailure: Boolean,
        canRetry: Boolean,
        retryResults: Set<Int> = DEFAULT_RETRY_RESULTS,
    ): Int {
        var r = resultCode
        if (r == RESULT_OK) {
            r = SendResult.SUCCESS
        } else if (r == RESULT_CANCELED) {
            r = SendResult.FAIL
        } else if (r in retryResults) {
            r = SendResult.RETRY_WAIT
        } else if (r in knownSmsResultConstants) {
            r = SendResult.PERMANENT_FAIL
        }
        if (r == SendResult.RETRY_WAIT) {
            return if (retryOnFailure && canRetry) SendResult.RETRY_WAIT else SendResult.PERMANENT_FAIL
        }
        return r
    }
}
