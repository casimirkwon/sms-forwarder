package io.github.casimirkwon.smsforwarder.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 단어 바꾸기.
 * - 규칙 저장 형식은 JSON 배열 `[{"find":"찾기","to":"바꾸기"}, …]` 이고 배열 순서대로 차례로 적용한다.
 * - 규칙의 "정규식 사용"이 켜져 있으면 찾기 문자열을 그대로 정규식으로 쓰고, 아니면 특수문자 12종만 이스케이프하며
 *   `*` 가 있으면 `(?s)` + `*`→`.*` 로 바꾼다(`.` 와 `\` 는 이스케이프하지 않으므로 정규식 의미가 남는다).
 * - 바꿀 말은 [parseReplacement] 로 먼저 해석한다: `\n`→줄바꿈, `\t`→탭, `\\`→역슬래시(그 밖의 `\x` 는 그대로).
 *   정규식 모드는 그 결과를 replaceAll 의 치환 문자열로 쓰고(`$1` 그룹 참조 유지), 비정규식 모드는 글자 그대로 쓴다.
 * - "매치 뒤 줄바꿈 지우기"는 바꿀 말이 빈 문자열일 때만 적용한다: 맞은 자리 바로 뒤의 개행 한 개를
 *   지운 뒤 DOTALL 로 치환한다. 바꿀 말이 비어 있지 않으면 일반 replaceAll.
 * - 도중에 예외가 나면 그때까지 바뀐 문자열을 그대로 돌려준다(오류는 [apply] 의 onError 로 알린다).
 * - 이 단계는 수신 본문에만 적용하고 그 결과를 템플릿({본문})에 넣는다(SMS 필터: 바꾸기 → 템플릿).
 */
object ReplaceWord {
    private val FIND = arrayOf("$", "(", ")", "{", "}", "^", "[", "]", "\"", "?", "+", "|")
    private val REPL = arrayOf("\\$", "\\(", "\\)", "\\{", "\\}", "\\^", "\\[", "\\]", "\\\"", "\\?", "[+]", "[|]")

    /** 저장된 JSON 배열 `[{"find":"찾기","to":"바꾸기"}, …]` → (찾기, 바꾸기) 쌍, 배열 순서대로. 파싱 실패면 빈 목록. */
    fun parsePairs(json: String?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (json.isNullOrEmpty()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optString("find", "") to o.optString("to", ""))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 쌍 목록 → 저장용 JSON 배열. */
    fun toJson(pairs: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((k, v) in pairs) arr.put(JSONObject().put("find", k).put("to", v))
        return arr.toString()
    }

    /** 규칙 한 개에 대해: 쌍이 없으면 그대로, 아니면 규칙의 정규식·빈줄 지우기 설정에 맞춰 적용. */
    fun forRule(r: io.github.casimirkwon.smsforwarder.db.RuleEntity, text: String): String {
        val pairs = parsePairs(r.replacements)
        if (pairs.isEmpty()) return text
        return apply(text, pairs, regex = r.regexReplace, removeEmptyLine = r.removeEmptyLine)
    }

    fun apply(
        text: String,
        pairs: List<Pair<String, String>>,
        regex: Boolean,
        removeEmptyLine: Boolean,
        onError: (Exception) -> Unit = {},
    ): String {
        var cur = text
        for ((find, repl) in pairs) {
            try {
                val pattern = if (regex) find else wildcardPattern(find)
                cur = replaceOne(cur, pattern, parseReplacement(repl, regex), removeEmptyLine)
            } catch (e: Exception) {
                onError(e)
                return cur
            }
        }
        return cur
    }

    /** 비정규식 모드의 찾기 문자열 → 정규식. */
    fun wildcardPattern(find: String): String {
        var s = find
        for (i in FIND.indices) s = s.replace(FIND[i], REPL[i])
        if (s.contains("*")) s = "(?s)" + s.replace("*", ".*")
        return s
    }

    /**
     * 바꿀 말 해석. `\n`→줄바꿈, `\t`→탭, `\\`→역슬래시이고 이 셋만 두 글자를 한꺼번에 읽는다.
     * 그 밖의 `\x` 는 `\` 를 그대로 두고 다음 글자를 평범하게 읽는다. 끝의 홑 `\` 도 그대로.
     * 정규식 모드는 역슬래시를 치환 문자열에서 글자 그대로 남기도록 `\\\\` 로 겹쳐 쓰고 그대로 돌려주며(`$1` 유지),
     * 비정규식 모드는 [Matcher.quoteReplacement] 로 `$`·`\` 를 글자 그대로 만든다.
     */
    fun parseReplacement(s: String?, regex: Boolean): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
            } else {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i++ }
                    't' -> { sb.append('\t'); i++ }
                    '\\' -> { sb.append(if (regex) "\\\\" else "\\"); i++ }
                    else -> sb.append(c)
                }
            }
            i++
        }
        val out = sb.toString()
        return if (regex) out else Matcher.quoteReplacement(out)
    }

    /** 한 쌍의 치환. [repl] 은 이미 [parseReplacement] 를 거친 치환 문자열이다. */
    fun replaceOne(src: String, pattern: String, repl: String, removeNewlineAfterMatch: Boolean): String {
        if (!removeNewlineAfterMatch || repl.isNotEmpty()) return Pattern.compile(pattern).matcher(src).replaceAll(repl)
        val p = Pattern.compile(pattern, Pattern.DOTALL)
        val m = p.matcher(src)
        val sb = StringBuilder(src)
        val ends = ArrayList<Int>()
        while (m.find()) {
            val end = m.end()
            if (end < sb.length && sb[end] == '\n') ends.add(end)
        }
        ends.reverse()
        for (i in ends) sb.deleteCharAt(i)
        return p.matcher(sb).replaceAll(repl)
    }
}
