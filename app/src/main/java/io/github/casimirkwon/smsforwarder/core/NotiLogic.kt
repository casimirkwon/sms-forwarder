package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity
import kotlin.math.abs

/**
 * 알림 한 건에서 꺼낸 필드(알림 수집 → ACTION_NOTI 인텐트의 extras).
 * [packageName] 은 요약 알림이면 [NotiText.SUMMARY_PREFIX] 가 앞에 붙어 있다.
 * [imageCount] 는 알림에 그림(android.picture 등)이 있는지(SMS 로는 보낼 수 없어 개수만 센다).
 */
data class IncomingNotification(
    val title: String,
    val text: String,
    val subText: String,
    val bigText: String,
    val messageText: String,
    val oldText: String,
    val timeMillis: Long,
    val packageName: String,
    val imageCount: Int = 0,
    /** 알림 추가 내용: 요약문·큰 제목·대화방 이름·보낸이·InboxStyle 줄 중 다른 필드와 겹치지 않는 것을 줄바꿈으로 이은 문자열 */
    val extraContent: String = "",
)

/** 알림 필드 추출 규칙. */
object NotiText {
    /** 요약 알림 표식: 알림 패키지 이름 앞에 붙는다. */
    const val SUMMARY_PREFIX = "[summary]"

    /** Notification.FLAG_FOREGROUND_SERVICE(64) */
    const val FLAG_FOREGROUND_SERVICE = 64

    /** Notification.FLAG_GROUP_SUMMARY(512) */
    const val FLAG_GROUP_SUMMARY = 512

    /** 비었거나 개행만 있으면 true(공백 문자는 비었다고 보지 않는다). */
    fun isBlank(s: String?): Boolean = s.isNullOrEmpty() || s.replace("\n", "").isEmpty()

    /**
     * 제목 정리: `String.valueOf(extras.get("android.title"))` 가 "null" 이면 빈 문자열, 그다음 양방향 격리 문자
     * U+2068·U+2069 를 지운다(문자열 "null" 제목도 빈 값으로 취급한다).
     */
    fun cleanTitle(valueOfTitle: String): String {
        val t = if (valueOfTitle == "null") "" else valueOfTitle
        return t.replace("⁨", "").replace("⁩", "")
    }

    /**
     * 이 알림을 아예 무시하는가: 자기 앱, `com.android.systemui`, 진행 중(ongoing) 알림,
     * 포그라운드 서비스 알림(플래그 64).
     */
    fun shouldIgnore(packageName: String, ownPackage: String, ongoing: Boolean, flags: Int): Boolean =
        packageName == ownPackage || packageName == "com.android.systemui" || ongoing || (flags and FLAG_FOREGROUND_SERVICE) != 0

    /** 제목·본문·bigText·messageText·oldText 가 모두 비었으면 버린다(subText 는 보지 않는다). */
    fun allBlank(title: String, text: String, bigText: String, messageText: String, oldText: String): Boolean =
        isBlank(title) && isBlank(text) && isBlank(bigText) && isBlank(messageText) && isBlank(oldText)

    /**
     * 기본 본문(oldText) 고르기. [template] 은 `android.template` extra(없으면 null).
     * - BigTextStyle: text·bigText 중 긴 쪽을 쓰고, 한쪽이 다른 쪽을 포함하지 않으면 줄바꿈으로 이어 붙인다.
     * - MessagingStyle: `android.messages` 의 마지막 항목 `text`([lastMessageText], extra 가 없으면 null,
     *   항목에 text 가 없으면 `String.valueOf` 결과인 "null").
     * - InboxStyle: 없음(빈 문자열). 그 밖의 스타일과 template 가 없는 경우: android.text.
     */
    fun oldText(template: String?, text: String, bigText: String, lastMessageText: String?): String {
        val result: String? = if (template != null) {
            when {
                template.contains("BigTextStyle") -> {
                    val sb = StringBuilder()
                    if (text.length > bigText.length) {
                        sb.append(text)
                        if (!text.contains(bigText)) {
                            sb.append("\n")
                            sb.append(bigText)
                        }
                    } else {
                        if (!bigText.contains(text)) {
                            sb.append(text)
                            sb.append("\n")
                        }
                        sb.append(bigText)
                    }
                    sb.toString()
                }
                template.contains("MessagingStyle") -> lastMessageText
                !template.contains("InboxStyle") -> text
                else -> null
            }
        } else {
            text
        }
        return if (result.isNullOrEmpty()) "" else result
    }
}

/** 알림 필터 v4 "보낼 내용" 구성에서 고를 수 있는 항목(저장 키 `title,extra,text,sub,big,message`). */
enum class NotiPart(val key: String) {
    TITLE("title"),
    EXTRA("extra"),
    TEXT("text"),
    SUB("sub"),
    BIG("big"),
    MESSAGE("message"),
    ;

    companion object {
        fun fromKey(key: String): NotiPart? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 알림 추가 내용 만들기·v4 "보낼 내용" 구성.
 */
object NotiCompose {
    /** 직접 선택의 기본 구성(제목 + 본문) */
    val DEFAULT_DIRECT: String get() = serialize(listOf(NotiPart.TITLE, NotiPart.TEXT))

    private val ISOLATES = Regex("[\\u2066-\\u2069]")

    /** 비교용 정규화: 양방향 격리 문자 U+2066~2069 를 지우고 앞뒤 공백을 자른다(null 이면 빈 문자열). */
    fun normalize(s: CharSequence?): String = if (s == null) "" else ISOLATES.replace(s.toString(), "").trim()

    /** 비었거나 공백만 있으면 true(정규화는 하지 않는다). */
    private fun blank(s: String): Boolean = s.isBlank()

    // --- 추가 내용

    /**
     * 추가 내용 줄 고르기. 후보는 요약문·큰 제목·대화방 이름·보낸이·[textLines] 순서이고,
     * 정규화한 뒤 비었거나 [exclude] 의 (통째·줄 단위) 정규화 값이나 앞서 고른 줄과 같으면 뺀다.
     */
    fun extraLines(
        summary: CharSequence?,
        bigTitle: CharSequence?,
        conversationTitle: CharSequence?,
        sender: CharSequence?,
        textLines: List<CharSequence?>,
        exclude: List<String>,
    ): List<String> {
        val seen = HashSet<String>()
        for (e in exclude) {
            seen.add(normalize(e))
            for (line in e.split("\n")) seen.add(normalize(line))
        }
        val out = ArrayList<String>()
        val candidates = listOf(summary, bigTitle, conversationTitle, sender) + textLines
        for (c in candidates) {
            val d = normalize(c)
            if (d.isNotEmpty() && seen.add(d)) out.add(d)
        }
        return out
    }

    /**
     * 추가 내용 문자열. 제목·본문·서브·긴 본문·메시지와 겹치는 것을 뺀 결과가 비면 빈 문자열,
     * 아니면 앱 이름([appLabel], 설치 앱 라벨 조회는 필요할 때만 부른다)도 겹침 제외 목록에 넣고 다시 골라 줄바꿈으로 잇는다.
     */
    fun extraContent(
        summary: CharSequence?,
        bigTitle: CharSequence?,
        conversationTitle: CharSequence?,
        sender: CharSequence?,
        textLines: List<CharSequence?>,
        title: String,
        text: String,
        subText: String,
        bigText: String,
        messageText: String,
        appLabel: () -> String,
    ): String {
        val exclude = listOf(title, text, subText, bigText, messageText)
        if (extraLines(summary, bigTitle, conversationTitle, sender, textLines, exclude).isEmpty()) return ""
        return extraLines(summary, bigTitle, conversationTitle, sender, textLines, exclude + appLabel()).joinToString("\n")
    }

    // --- 구성 문자열

    /**
     * 구성 문자열 → 항목 목록: 비었거나 공백뿐이면 빈 목록(= 자동). 쉼표로 나눠 앞뒤 공백을 자르고,
     * 모르는 키는 버리며, 중복은 처음 것만 남겨 순서를 지킨다.
     */
    fun parse(composition: String?): List<NotiPart> =
        Conditions.decodeList(composition).mapNotNull { NotiPart.fromKey(it.trim()) }.distinct()

    fun serialize(parts: List<NotiPart>): String = Conditions.encodeList(parts.map { it.key })

    private fun value(part: NotiPart, n: IncomingNotification): String = when (part) {
        NotiPart.TITLE -> n.title
        NotiPart.EXTRA -> n.extraContent
        NotiPart.TEXT -> n.text
        NotiPart.SUB -> n.subText
        NotiPart.BIG -> n.bigText
        NotiPart.MESSAGE -> n.messageText
    }

    /** 직접 선택: 고른 순서대로 값을 모아 비거나 공백뿐인 것은 빼고 줄바꿈으로 잇는다(값은 그대로, 정규화하지 않는다). */
    fun direct(parts: List<NotiPart>, n: IncomingNotification): String =
        parts.distinct().map { value(it, n) }.filter { !blank(it) }.joinToString("\n")

    /**
     * 자동(규칙 1~4):
     * 1. 메시지 본문이 있으면 그것. 2. 없으면 text·bigText — 한쪽만 있으면 그쪽, text 가 bigText 보다 길거나 같고 bigText 를 포함하면 text,
     * bigText 가 text 를 포함하면 bigText, 아니면 `text\nbigText`. 3. 그래도 비면 추가 내용.
     * 4. 제목이 비어 있지 않고 본문의 어느 줄(정규화)과도 같지 않으면 맨 앞에 제목 줄을 붙인다.
     */
    fun auto(n: IncomingNotification): String {
        var body = n.messageText
        if (blank(body)) {
            body = n.text
            val big = n.bigText
            if (!blank(big)) {
                if (blank(body)) {
                    body = big
                } else if (body.length < big.length || !body.contains(big)) {
                    body = if (big.contains(body)) big else body + "\n" + big
                }
            }
        }
        if (blank(body)) body = n.extraContent
        var title = n.title
        if (blank(title)) {
            title = ""
        } else {
            val t = normalize(title)
            if (body.split("\n").any { normalize(it) == t }) title = ""
        }
        return listOf(title, body).filter { !blank(it) }.joinToString("\n")
    }

    /** 구성 문자열로 보낼 본문 만들기: 비어 있으면 자동, 아니면 직접 선택. */
    fun compose(composition: String?, n: IncomingNotification): String {
        val parts = parse(composition)
        return if (parts.isEmpty()) auto(n) else direct(parts, n)
    }
}

/** 알림 규칙의 전달 내용 구성·대상 앱 판정. */
object NotiRule {
    /** 요약 알림 표식을 떼어 낸 패키지 이름. */
    fun strippedPackage(packageName: String): String = packageName.removePrefix(NotiText.SUMMARY_PREFIX)

    /** 규칙의 앱 목록(JSON 배열) → 집합(빈 항목 제외). */
    fun parsePackages(packages: String?): Set<String> = Conditions.decodeList(packages).toSet()

    fun serializePackages(packages: Collection<String>): String = Conditions.encodeList(packages)

    /** 목록이 비어 있으면 모든 앱, 아니면 목록에 있는 앱만. */
    fun packageMatches(packages: String?, packageName: String): Boolean {
        val set = parsePackages(packages)
        return set.isEmpty() || set.contains(packageName)
    }

    /**
     * 전달 본문 구성. 규칙이 고른 필드(text 4096 · subText 134217728 · bigText 8192 ·
     * messageText 16384)를 이 순서로 줄바꿈(앞에 쓴 내용이 있을 때만)으로 이어 붙이고,
     * 하나도 고르지 않았으면 oldText 를 쓴다.
     */
    fun compose(r: RuleEntity, n: IncomingNotification): String {
        val useText = r.useText
        val useSub = r.useSubText
        val useBig = r.useBigText
        val useMessage = r.useMessageText
        if (!useText && !useSub && !useBig && !useMessage) return n.oldText
        val sb = StringBuilder()
        if (useText) sb.append(n.text)
        if (useSub) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(n.subText)
        }
        if (useBig) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(n.bigText)
        }
        if (useMessage) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(n.messageText)
        }
        return sb.toString()
    }

    /** [evaluate] 결과: [skip] 이 null 이 아니면 그 단계 코드로 건너뛰고, 아니면 [body] 를 단어 바꾸기·템플릿에 넘긴다. */
    data class Gate(val body: String, val skip: String?)

    /**
     * 규칙 하나가 알림을 통과시키는지 본문 조건까지 판정한다.
     * [spamMatch] 는 (알림 제목, 본문) → 걸린 스팸 규칙 데이터(없으면 null).
     *
     * - v3 이하: 본문 = 고른 비트 필드 이어 붙임(없으면 oldText) → OTP(1) → 빈 알림(2, "빈 알림 보내지 않기" 비트가 켜졌을 때만) →
     *   스팸(10_) → 제목 조건 + 본문 조건(11/5/3).
     * - v4: 본문 = 구성 문자열([RuleEntity.notiComposition], 비면 자동) → OTP(1) → 빈 본문이면 비트와 무관하게 2 → 스팸(10_) →
     *   **제목 조건은 보지 않고** 본문 조건만(5).
     */
    fun evaluate(f: RuleEntity, n: IncomingNotification, spamMatch: (String, String) -> String?): Gate {
        val v4 = f.isV4
        val composed = if (v4) NotiCompose.compose(f.composition, n) else compose(f, n)
        if (composed.isNotEmpty() && !f.allowOtp && OtpBlock.isBlocked(composed)) return Gate(composed, StepCode.OTP_BLOCKED)
        if (composed.isEmpty() && (v4 || f.skipEmptyNoti)) return Gate(composed, StepCode.EMPTY_NOTI)
        val spam = spamMatch(n.title, composed)
        if (spam != null) return Gate(composed, StepCode.SPAM_BLOCKED_PREFIX + spam)
        if (!f.matchAll) {
            val ignoreCase = f.ignoreCase
            val wildcard = f.wildcard
            val textOk = ConditionMatch.matchText(f.bodyCond, composed, ignoreCase, wildcard)
            if (v4) {
                if (!textOk) return Gate(composed, StepCode.NOT_MATCH_TEXT)
            } else {
                val titleOk = ConditionMatch.matchText(f.senderCond, n.title, ignoreCase, wildcard)
                if (!titleOk || !textOk) {
                    val code = if (!titleOk && !textOk) StepCode.NOT_MATCH_BOTH else if (!titleOk) StepCode.NOT_MATCH_NOTI_SENDER else StepCode.NOT_MATCH_TEXT
                    return Gate(composed, code)
                }
            }
        }
        return Gate(composed, null)
    }

    /**
     * 단어 바꾸기와 템플릿 적용 순서: 알림 규칙 version 2·3 은 템플릿 → 바꾸기,
     * 그 밖(version 0·1 알림 규칙, v4)은 바꾸기 → 템플릿. 마지막에 `\r\n` → `\n`.
     */
    fun render(f: RuleEntity, composed: String, replace: (String) -> String, template: (String) -> String): String {
        val version = f.level
        val templateFirst = version in 2..3
        val out = if (templateFirst) replace(template(composed)) else template(replace(composed))
        return out.replace("\r\n", "\n")
    }

    /**
     * 요약 알림 전달 여부: 설정 `summary_all_apps` 가 켜져 있으면 전달, 아니면 허용 목록(`summary_apps`, 접두어 없는
     * 패키지 이름의 JSON 배열)에 [packageName] 이 있을 때만 전달한다. [packageName] 은 요약 표식이 붙지 않은 값이다.
     */
    fun summaryAllowed(forwardAll: Boolean, storedPackages: String, packageName: String): Boolean =
        forwardAll || parsePackages(storedPackages).contains(strippedPackage(packageName))
}

/** 중복 알림 거르기에 쓰는 이력 한 건(표 `noti_seen`). */
data class NotiHistoryItem(
    val id: Long,
    val time: Long,
    val text: String,
    val bigText: String,
    val messageText: String,
    val isRcs: Boolean,
    val key: String,
) {
    /**
     * 같은 알림인지 비교한다(this = 새 알림, [other] = 이미 있는 이력). 새 알림이 RCS 가 아니므로:
     * - [textOnly] 이면 text 만 같으면 일치(요약 알림용)
     * - 아니면 text·bigText·messageText 가 같아야 하고, [strict] 이면 알림 key 도 같아야 한다.
     */
    fun sameAs(other: NotiHistoryItem, strict: Boolean, textOnly: Boolean): Boolean {
        if (textOnly) return text == other.text
        if (isRcs) return text == other.text && key == other.key && time == other.time
        return text == other.text && bigText == other.bigText && messageText == other.messageText && (!strict || key == other.key)
    }
}

data class DedupeConfig(
    /** setting_ignore_duplicate_noti */
    val ignoreDuplicates: Boolean,
    /** check_duplicate_strictly */
    val strict: Boolean,
    /** summary_noti_only_text */
    val summaryOnlyText: Boolean,
    /** pref_ignore_interval(분) → 밀리초 */
    val intervalMs: Long,
)

/**
 * 중복 알림 거르기. 이력 목록은 시간순으로 쌓이며 마지막부터 거꾸로 훑는다.
 * - 중복 판정(켜져 있을 때만): RCS 가 아닌 이력 중 `새 시각 − 이력 시각 < 간격` 인 것과 내용이 같으면 중복.
 *   요약 알림(플래그 512)이고 "요약 알림은 본문만 비교"가 켜져 있으면 text 만 같아도 중복.
 * - 정리(켜짐 여부와 무관): 시각 차이의 절댓값이 간격(RCS 이력은 48시간) 이상이면 목록과 DB 에서 지운다.
 * - 중복이 아니면 새 알림을 이력에 넣는다(중복이면 넣지 않으므로 창은 처음 알림 시각 기준이다).
 */
object NotiDedupe {
    const val RCS_KEEP_MS = 48L * 3600_000L

    data class Outcome(val duplicate: Boolean, val toDelete: List<NotiHistoryItem>)

    /** [list] 는 직접 고친다(오래된 항목 제거). 새 항목 추가는 호출자가 DB id 를 받은 뒤 한다. */
    fun check(list: MutableList<NotiHistoryItem>, item: NotiHistoryItem, isSummary: Boolean, cfg: DedupeConfig): Outcome {
        val now = item.time
        val toDelete = ArrayList<NotiHistoryItem>()
        var duplicate = false
        var i = list.size - 1
        while (i >= 0) {
            val e = list[i]
            if (cfg.ignoreDuplicates && !e.isRcs && now - e.time < cfg.intervalMs) {
                if (cfg.summaryOnlyText && isSummary) {
                    if (item.sameAs(e, strict = false, textOnly = true)) duplicate = true
                    else if (item.sameAs(e, cfg.strict, textOnly = false)) duplicate = true
                } else if (item.sameAs(e, cfg.strict, textOnly = false)) {
                    duplicate = true
                }
            }
            val stale = if (e.isRcs) abs(now - e.time) >= RCS_KEEP_MS else abs(now - e.time) >= cfg.intervalMs
            if (stale) {
                list.removeAt(i)
                toDelete.add(e)
            }
            i--
        }
        return Outcome(duplicate, toDelete)
    }
}
