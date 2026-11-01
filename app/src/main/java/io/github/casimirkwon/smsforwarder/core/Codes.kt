package io.github.casimirkwon.smsforwarder.core

import android.content.Context
import io.github.casimirkwon.smsforwarder.R

/** 전달 건의 결과 코드. 저장되는 정수이며 이 앱 고유 번호다. */
object SendResult {
    const val FAIL = 0
    const val SUCCESS = 1
    const val SENDING = 2
    const val SCHEDULED = 20
    const val NO_SMS_MANAGER = 21
    const val DUPLICATE_BLOCKED = 22
    /** 앱이 꺼져 있어 지연(예약) 전달을 취소함 */
    const val DELAY_CANCELLED = 23
    const val WAITING_NETWORK = 24
    const val NEED_PHONE_STATE = 25
    const val EMPTY_PARAM = 26
    const val SMS_UNSUPPORTED = 27
    const val SMS_LIMIT = 28
    const val REPEAT_BLOCKED = 29
    const val RETRY_WAIT = 30
    const val PERMANENT_FAIL = 40

    fun label(ctx: Context, code: Int): String = when (code) {
        SUCCESS -> ctx.getString(R.string.res_success)
        SENDING -> ctx.getString(R.string.res_sending)
        SCHEDULED -> ctx.getString(R.string.res_scheduled)
        DELAY_CANCELLED -> ctx.getString(R.string.res_delay_cancelled)
        NO_SMS_MANAGER -> ctx.getString(R.string.res_no_sms_manager)
        DUPLICATE_BLOCKED -> ctx.getString(R.string.res_duplicate)
        WAITING_NETWORK -> ctx.getString(R.string.res_waiting_network)
        NEED_PHONE_STATE -> ctx.getString(R.string.res_need_phone_state)
        EMPTY_PARAM -> ctx.getString(R.string.res_empty_param)
        SMS_UNSUPPORTED -> ctx.getString(R.string.res_sms_unsupported)
        SMS_LIMIT -> ctx.getString(R.string.res_sms_limit)
        REPEAT_BLOCKED -> ctx.getString(R.string.res_repeat_blocked)
        RETRY_WAIT -> ctx.getString(R.string.res_retry_wait)
        PERMANENT_FAIL, FAIL -> ctx.getString(R.string.res_fail)
        else -> ctx.getString(R.string.res_unknown, code)
    }
}

/** 앱 이력(규칙 단계 로그)의 건너뜀/전달 코드. */
object StepCode {
    const val OTP_BLOCKED = "otp"
    const val EMPTY_NOTI = "empty-noti"
    const val NOT_MATCH_BOTH = "no-match-both"
    const val NOT_MATCH_SENDER = "no-match-sender"
    const val NOT_MATCH_TEXT = "no-match-text"
    const val SCHEDULE_SKIP = "outside-hours"
    const val PACKAGE_MISMATCH = "app-mismatch"
    const val SPAM_BLOCKED_PREFIX = "spam:"
    const val NOT_MATCH_NOTI_SENDER = "no-match-title"
    const val EMPTY_BODY = "empty-body"
    const val QUEUED_PREFIX = "queued:"
    const val SIM_IN_MISMATCH = "sim-mismatch"
    /** 요약 알림을 전달하지 않도록 설정돼 건너뜀 */
    const val SUMMARY_NOT_FORWARDED = "summary-skipped"

    fun describe(ctx: Context, code: String): String = when {
        code == OTP_BLOCKED -> ctx.getString(R.string.step_otp)
        code == EMPTY_NOTI -> ctx.getString(R.string.step_empty_noti)
        code == NOT_MATCH_BOTH -> ctx.getString(R.string.step_no_match_both)
        code == NOT_MATCH_SENDER -> ctx.getString(R.string.step_no_match_sender)
        code == NOT_MATCH_TEXT -> ctx.getString(R.string.step_no_match_text)
        code == SCHEDULE_SKIP -> ctx.getString(R.string.step_outside_hours)
        code == PACKAGE_MISMATCH -> ctx.getString(R.string.step_app_mismatch)
        code.startsWith(SPAM_BLOCKED_PREFIX) -> ctx.getString(R.string.step_spam, code.removePrefix(SPAM_BLOCKED_PREFIX))
        code == NOT_MATCH_NOTI_SENDER -> ctx.getString(R.string.step_no_match_title)
        code == EMPTY_BODY -> ctx.getString(R.string.step_empty_body)
        code.startsWith(QUEUED_PREFIX) -> ctx.getString(R.string.step_queued, code.removePrefix(QUEUED_PREFIX))
        code == SIM_IN_MISMATCH -> ctx.getString(R.string.step_sim_mismatch)
        code == SUMMARY_NOT_FORWARDED -> ctx.getString(R.string.step_summary_skipped)
        else -> code
    }
}
