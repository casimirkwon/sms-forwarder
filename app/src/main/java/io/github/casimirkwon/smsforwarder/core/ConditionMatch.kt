package io.github.casimirkwon.smsforwarder.core

import java.util.regex.Pattern

/**
 * 규칙 조건(발신자·본문) 판정. 조건은 [Conditions] 형식(묶음의 목록)으로 저장돼 있다.
 * 묶음 안의 항목은 AND, 묶음끼리는 OR 로 합성하며 발신자 항목은 포함 여부로 판정한다.
 */
object ConditionMatch {
    /** android.util.Patterns.PHONE 과 같은 정규식. */
    private val PHONE = Pattern.compile("(\\+[0-9]+[\\- \\.]*)?(\\([0-9]+\\)[\\- \\.]*)?([0-9][0-9\\- \\.]+[0-9])")
    private val ESCAPED = listOf("+", "$", "|", "(", ")", "{", "}", "^", "[", "]", "\"", "?")

    fun isPhoneFormat(s: String): Boolean = PHONE.matcher(s).matches()

    /** 전화번호 꼴이면 '-' 제거. */
    fun normalizeNumber(s: String): String = if (isPhoneFormat(s)) s.replace("-", "") else s

    /** 와일드카드 → 정규식: 일부 특수문자를 \Q..\E 로 감싸고 '*' 를 ".*" 로 바꿔 (?s) 정규식을 만든다('.' 는 이스케이프하지 않음). */
    fun wildcardRegex(token: String): String {
        var s = token
        for (c in ESCAPED) s = s.replace(c, Pattern.quote(c))
        if (!s.contains("*")) return s
        return "(?s)" + s.replace("*", ".*")
    }

    private fun matchesWildcard(text: String, token: String): Boolean = try {
        text.matches(Regex(wildcardRegex(token)))
    } catch (e: Exception) {
        false
    }

    /**
     * 본문 조건. 조건이 비면 통과. 묶음 중 하나라도 만족하면 통과하며,
     * 한 묶음 안에서는 일반 항목은 contains, 제외 항목은 포함하면 불통과.
     * 와일드카드가 켜져 있고 항목에 '*' 가 있으면 전체 일치 정규식으로 검사한다.
     */
    fun matchText(rule: String?, text: String, ignoreCase: Boolean, wildcard: Boolean): Boolean {
        val groups = Conditions.decode(rule)
        if (groups.isEmpty()) return true
        val subject = if (ignoreCase) text.lowercase() else text
        return groups.any { groupSatisfied(it, subject, ignoreCase, wildcard) }
    }

    private fun groupSatisfied(group: List<Term>, subject: String, ignoreCase: Boolean, wildcard: Boolean): Boolean {
        for (term in group) {
            if (!term.exclude && term.text.isEmpty()) continue
            val t = if (ignoreCase) term.text.lowercase() else term.text
            val hit = if (wildcard && t.contains("*")) matchesWildcard(subject, t) else subject.contains(t)
            if (term.exclude == hit) return false
        }
        return true
    }

    /**
     * 발신자 조건(SMS 수신 규칙). level >= 1 이면 제외 항목에 하나라도 맞으면 불통과,
     * 포함 항목이 있으면 그중 하나와 맞아야 통과(묶음 구분과 상관없이 모든 항목을 한데 모은다). 맞음 판정은 와일드카드(전체 일치 정규식) →
     * 전화번호 꼴이면 phoneCompare → 그 외 문자열 일치. level 0 은 정확 문자열 목록 비교.
     * 알림 규칙은 제목 contains 방식이라 matchText 를 쓴다.
     */
    fun matchSender(
        rule: String?,
        address: String,
        ignoreCase: Boolean,
        wildcard: Boolean,
        level: Int,
        phoneCompare: (String, String) -> Boolean,
    ): Boolean {
        val terms = Conditions.decode(rule).flatten()
        val subject = normalizeNumber(if (ignoreCase) address.lowercase() else address)
        val excludes = ArrayList<String>()
        val includes = ArrayList<String>()
        for (term in terms) {
            if (!term.exclude && term.text.isEmpty()) continue
            val t = normalizeNumber(if (ignoreCase) term.text.lowercase() else term.text)
            if (term.exclude) excludes.add(t) else includes.add(t)
        }
        if (level < 1) {
            return !(excludes.contains(subject) || (includes.isNotEmpty() && !includes.contains(subject)))
        }
        fun hit(tok: String): Boolean = when {
            wildcard && tok.contains("*") -> matchesWildcard(subject, tok)
            isPhoneFormat(tok) -> phoneCompare(tok, subject)
            else -> subject == tok
        }
        if (excludes.any { hit(it) }) return false
        if (includes.isNotEmpty()) return includes.any { hit(it) }
        return true
    }
}

/** 수신처 JSON → 수신처 목록. */
fun parseDestinations(recipients: String?): List<String> = Conditions.decodeList(recipients)
