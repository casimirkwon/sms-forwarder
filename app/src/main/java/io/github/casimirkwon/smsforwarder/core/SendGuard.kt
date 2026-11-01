package io.github.casimirkwon.smsforwarder.core

import java.util.Locale

/** 최근 전송 시도 기록 한 건. content 는 템플릿 적용 전 본문. */
data class SentRecord(
    val content: String,
    val inNumber: String,
    val outNumber: String,
    val receiveTime: Long,
    val sendTime: Long,
)

/**
 * 전송 직전 방어의 판정.
 * - 기록은 60초(`abs(now - sendTime) > 60000` 이면 제거)까지만 유지한다.
 * - 중복: 같은 수신처이고 본문이 서로 포함 관계(대소문자 구분)인 기록 중, 원 발신번호가 수신처와 같거나(전화번호는 compare)
 *   본문 유사도(소문자 편집거리 기준) ≥ 0.94 인 것의 수 `similar`. 설정이 켜져 있고 (항상 차단 && similar > 0) 또는 similar ≥ 기준(5)이면 차단(10000011).
 * - 반복: 같은 수신처 기록 수 `sameDest` 가 기준(15)을 **넘으면**(>) 그 수신처를 1시간 차단 목록에 올리고 차단(10011123).
 * - 현재 전송 건은 센 다음에 기록에 추가되므로 자기 자신은 세지 않는다(차단된 시도도 기록된다).
 */
object SendGuard {
    const val WINDOW_MS = 60_000L
    const val SIMILARITY = 0.94
    const val BLOCK_MS = 3_600_000L

    enum class Verdict { OK, DUPLICATE, REPEATED }

    class Counts(val similar: Int, val sameDest: Int)

    /** 전화번호 판정: `#숫자`·`*숫자` 이거나 Patterns.PHONE 에 전체 일치. */
    fun isPhone(s: CharSequence?): Boolean {
        if (s == null) return false
        if (s.length > 1 && (s[0] == '#' || s[0] == '*')) {
            return try {
                s.substring(1).toDouble()
                true
            } catch (_: NumberFormatException) {
                false
            }
        }
        return ConditionMatch.isPhoneFormat(s.toString())
    }

    /** 문자열 유사도: (긴 쪽 길이 - 편집거리) / 긴 쪽 길이, 소문자로 비교. 긴 쪽이 비어 있으면 1.0. */
    fun similarity(a: String, b: String, locale: Locale = Locale.getDefault()): Double {
        var longer = a
        var shorter = b
        if (longer.length < shorter.length) {
            longer = b
            shorter = a
        }
        val length = longer.length
        if (length == 0) return 1.0
        return (length - editDistance(longer.lowercase(locale), shorter.lowercase(locale))).toDouble() / length.toDouble()
    }

    private fun editDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1)
        for (i in 0..s1.length) {
            var lastValue = i
            for (j in 0..s2.length) {
                if (i == 0) {
                    costs[j] = j
                } else if (j > 0) {
                    var newValue = costs[j - 1]
                    if (s1[i - 1] != s2[j - 1]) newValue = minOf(minOf(newValue, lastValue), costs[j]) + 1
                    costs[j - 1] = lastValue
                    lastValue = newValue
                }
            }
            if (i > 0) costs[s2.length] = lastValue
        }
        return costs[s2.length]
    }

    /** 기록 [history](현재 건 제외)를 훑어 중복 수와 같은 수신처 수를 센다. */
    fun count(history: List<SentRecord>, cur: SentRecord, phoneCompare: (String, String) -> Boolean): Counts {
        var similar = 0
        var sameDest = 0
        for (h in history) {
            if (isSimilar(h, cur, phoneCompare)) similar++
            if (cur.outNumber == h.outNumber) sameDest++
        }
        return Counts(similar, sameDest)
    }

    private fun isSimilar(h: SentRecord, cur: SentRecord, phoneCompare: (String, String) -> Boolean): Boolean {
        if (h.outNumber != cur.outNumber) return false
        if (!(h.content.contains(cur.content) || cur.content.contains(h.content))) return false
        if (cur.inNumber == cur.outNumber) return true
        if (isPhone(cur.outNumber) && isPhone(cur.inNumber) && phoneCompare(cur.inNumber, cur.outNumber)) return true
        val sim = try {
            similarity(h.content, cur.content)
        } catch (_: Exception) {
            0.0
        }
        return sim >= SIMILARITY
    }

    fun decide(
        avoidDuplicate: Boolean,
        preventRepeated: Boolean,
        alwaysStopDuplicate: Boolean,
        duplicateCount: Int,
        repeatedMax: Int,
        counts: Counts,
    ): Verdict = when {
        avoidDuplicate && ((alwaysStopDuplicate && counts.similar > 0) || counts.similar >= duplicateCount) -> Verdict.DUPLICATE
        preventRepeated && counts.sameDest > repeatedMax -> Verdict.REPEATED
        else -> Verdict.OK
    }

    /** 차단 목록 일치: 전화번호 꼴이면 PhoneNumberUtils.compare, 아니면(또는 그와 별개로) 문자열 일치. */
    fun blockMatches(entry: String, dest: String, phoneCompare: (String, String) -> Boolean): Boolean =
        (isPhone(dest) && phoneCompare(entry, dest)) || dest == entry
}

/** 전역 근무시간·로밍 게이트(DeliveryWorker.doWork 의 앞단). */
object GlobalGate {
    /**
     * 근무시간 설정이 꺼져 있으면 항상 통과. 켜져 있으면 HHMM 정수 [now] 가
     * 시작 < 끝 이면 시작 ≤ now ≤ 끝, 시작 ≥ 끝(자정 넘김)이면 시작 ≤ now ≤ 2400 또는 0 ≤ now ≤ 끝.
     * 시작·끝 기본값은 -1 이라 둘 다 기본값이면 항상 통과한다.
     */
    fun timeAllowed(enabled: Boolean, start: Int, end: Int, now: Int): Boolean {
        if (!enabled) return true
        return if (start >= end) (now in start..2400) || (now in 0..end) else now in start..end
    }

    /** `setting_roaming_enable || !isNetworkRoaming()` — 로밍 중 전달을 끈 경우에만 로밍 여부를 본다. */
    inline fun roamingAllowed(roamingEnable: Boolean, isRoaming: () -> Boolean): Boolean = roamingEnable || !isRoaming()

    fun hhmm(hour: Int, minute: Int) = hour * 100 + minute
}

/** SMS 건수 제한. 달(월 번호 0~11)이 바뀌면 현재 건수를 0 으로 되돌린다. */
object SmsLimit {
    fun needsReset(currentMonth: Int, lastResetMonth: Int) = currentMonth != lastResetMonth

    /** 켜져 있고 현재 건수 ≥ 한도(기본 100)면 전송하지 않고 10011121. */
    fun reached(enabled: Boolean, current: Int, limit: Int) = enabled && current >= limit
}
