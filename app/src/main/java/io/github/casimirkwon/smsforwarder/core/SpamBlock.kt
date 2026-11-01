package io.github.casimirkwon.smsforwarder.core

import androidx.annotation.StringRes
import io.github.casimirkwon.smsforwarder.R

/** 전역 스팸 차단 규칙 한 줄(type 0 = 전화번호, 1 = 알림 제목, 2 = 메시지 본문). */
data class SpamRule(val id: Long, val type: Int, val data: String) {
    companion object {
        const val PHONE = 0
        const val NOTI_TITLE = 1
        const val BODY = 2
    }
}

/**
 * 전역 스팸 차단. 일치하면 모든 필터가 그 메시지를 건너뛴다.
 * 규칙은 id 순으로 보고 처음 맞은 규칙의 data 를 돌려준다(없으면 null).
 * - 본문 규칙: 항상 검사, 본문에 data 가 들어 있으면(대소문자 무시) 일치
 * - 번호 규칙: [phoneOrRcs] 가 true 일 때만, PhoneNumberUtils.compare(data, 발신번호)
 * - 알림 제목 규칙: [phoneOrRcs] 가 false 일 때만, 제목에 data 가 들어 있으면(대소문자 구분) 일치
 * 호출부 플래그는 SMS·RCS 에서 true, 알림에서 false 다 — SMS 에는 번호·본문 규칙이 적용되고 제목 규칙은 알림에만 적용된다.
 */
object SpamBlock {
    fun match(
        rules: List<SpamRule>,
        address: String,
        body: String,
        phoneOrRcs: Boolean,
        phoneCompare: (String, String) -> Boolean,
    ): String? {
        for (r in rules.sortedBy { it.id }) {
            when (r.type) {
                SpamRule.BODY -> if (body.contains(r.data, ignoreCase = true)) return r.data
                SpamRule.PHONE -> if (phoneOrRcs && phoneCompare(r.data, address)) return r.data
                SpamRule.NOTI_TITLE -> if (!phoneOrRcs && address.contains(r.data, ignoreCase = false)) return r.data
            }
        }
        return null
    }

    @StringRes
    fun typeLabel(type: Int) = when (type) {
        SpamRule.PHONE -> R.string.spam_type_phone
        SpamRule.NOTI_TITLE -> R.string.spam_type_noti_title
        else -> R.string.spam_type_body
    }
}
