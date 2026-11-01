package io.github.casimirkwon.smsforwarder.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 템플릿 치환에 쓰는 값. 연락처 조회·번호 서식은 Android 의존이라 바깥에서 넣는다. */
class TemplateInput(
    val body: String?,
    val address: String?,
    val simName: String?,
    val timeMillis: Long,
    val appLabel: String? = "",
    val subText: String? = "",
    /** [Template.EXTRA] 치환값(알림 추가 내용). 없으면 빈 문자열. */
    val extraContent: String? = "",
    val filterName: String? = "",
    /** [Template.CONTACT] 치환값(연락처 이름). 없으면 빈 문자열. 템플릿에 토큰이 있을 때만 호출된다. */
    val contactName: () -> String? = { null },
    /** [Template.SENDER_FORMATTED] 용 서식 번호(PhoneNumberUtils.formatNumber 에 해당). */
    val formattedAddress: () -> String? = { address },
    val locale: Locale = Locale.getDefault(),
)

/**
 * 전달 형식(템플릿)의 자리표시자 치환.
 *
 * 토큰은 `{이름}` 꼴이다. 치환은 아래 [TOKENS] 순서대로 `String.replace` 를 한 번씩 하는 단순 순차 방식이라,
 * 앞서 넣은 값(본문 등) 속에 뒤쪽 토큰 꼴 문자열이 있으면 그 차례에 한 번 더 바뀐다.
 */
object Template {
    const val CONTACT = "{연락처}"
    /** 문자는 보낸 번호, 알림은 알림 제목. */
    const val SENDER = "{보낸이}"
    const val SENDER_FORMATTED = "{보낸이(서식)}"
    const val RULE = "{규칙}"
    const val SIM = "{SIM}"
    const val RECEIVED_AT = "{시각}"
    const val BODY = "{본문}"
    const val YEAR = "{연}"
    const val MONTH = "{월}"
    const val DAY = "{일}"
    const val AM_PM = "{오전오후}"
    const val HOUR12 = "{시12}"
    const val HOUR24 = "{시24}"
    const val MINUTE = "{분}"
    const val WEEKDAY = "{요일}"
    const val APP = "{앱}"
    const val SUB = "{서브}"
    const val EXTRA = "{추가}"

    /** 치환 순서. 연락처는 따로 맨 앞에서 처리한다. */
    val TOKENS = listOf(
        CONTACT, SENDER, SENDER_FORMATTED, RULE, SIM, RECEIVED_AT, BODY,
        YEAR, MONTH, DAY, AM_PM, HOUR12, HOUR24, MINUTE, WEEKDAY, APP, SUB, EXTRA,
    )

    /** 문자 규칙 편집창에서 보여 줄 토큰(삽입 버튼 순서). */
    val SMS_TOKENS = listOf(SENDER, CONTACT, BODY, RULE, SIM, RECEIVED_AT)

    /** 알림 규칙 편집창에서 보여 줄 토큰(삽입 버튼 순서). */
    val NOTI_TOKENS = listOf(SENDER, BODY, APP, SUB, EXTRA, RULE, RECEIVED_AT)

    /** 템플릿이 비어 있으면 본문만 돌려준다(머리말 없음). */
    fun apply(template: String?, input: TemplateInput): String? {
        if (template.isNullOrEmpty()) return input.body
        var s: String = template
        val body = input.body ?: ""
        val simName = input.simName ?: ""
        val filterName = input.filterName ?: ""
        if (s.contains(CONTACT)) {
            val ct = input.contactName()
            s = s.replace(CONTACT, if (ct.isNullOrEmpty()) "" else ct)
        }
        val formatted = input.formattedAddress()
        val d = Date(input.timeMillis)
        fun fmt(p: String) = SimpleDateFormat(p, input.locale).format(d)
        val pairs = listOf(
            SENDER to input.address,
            SENDER_FORMATTED to formatted,
            RULE to filterName,
            SIM to simName,
            RECEIVED_AT to fmt("MM/dd a h:mm"),
            BODY to body,
            YEAR to fmt("yyyy"),
            MONTH to fmt("MM"),
            DAY to fmt("dd"),
            AM_PM to fmt("a"),
            HOUR12 to fmt("h"),
            HOUR24 to fmt("HH"),
            MINUTE to fmt("mm"),
            WEEKDAY to fmt("EEE"),
            APP to input.appLabel,
            SUB to input.subText,
            EXTRA to input.extraContent,
        )
        for ((token, value) in pairs) {
            // 값이 null 이면 그 토큰은 치환하지 않고 그대로 둔다.
            if (value != null) s = s.replace(token, value)
        }
        return s
    }
}
