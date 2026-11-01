package io.github.casimirkwon.smsforwarder.core

import org.json.JSONArray
import org.json.JSONObject

/** 조건의 낱개 항목: [text] 를 포함해야 하는 항목, [exclude] 이면 포함하면 안 되는 항목. */
data class Term(val text: String, val exclude: Boolean = false)

/**
 * 조건 묶음의 저장 형식. 묶음(그룹)의 목록이고 묶음 안의 항목은 모두 만족해야(AND) 하며, 묶음 중 하나만 만족해도(OR) 통과한다.
 *
 * ```
 * [ [ {"text":"승인"}, {"text":"(5746)","exclude":true} ], [ {"text":"취소"} ] ]
 * ```
 * 비어 있는 묶음([])은 항상 만족한다. 이 JSON 은 발신자·본문 조건, 전달 대상, 알림 앱 목록(수신처·앱은 항목 하나가 한 묶음의 한 항목이
 * 아니라 단순 문자열 배열)에서 쓰는 조건 형식과 다르다 — 수신처·앱은 [encodeList]/[decodeList].
 */
object Conditions {
    const val EMPTY = "[]"

    fun decode(json: String?): List<List<Term>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { gi ->
                val g = arr.optJSONArray(gi) ?: JSONArray()
                (0 until g.length()).mapNotNull { ti ->
                    val o = g.optJSONObject(ti) ?: return@mapNotNull null
                    Term(o.optString("text", ""), o.optBoolean("exclude", false))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(groups: List<List<Term>>): String {
        val arr = JSONArray()
        for (g in groups) {
            val ga = JSONArray()
            for (t in g) {
                val o = JSONObject().put("text", t.text)
                if (t.exclude) o.put("exclude", true)
                ga.put(o)
            }
            arr.put(ga)
        }
        return arr.toString()
    }

    /** 문자열 목록(수신처·알림 앱)을 JSON 배열 문자열로. 빈 항목은 뺀다. */
    fun encodeList(items: Collection<String>): String {
        val arr = JSONArray()
        for (s in items) if (s.isNotEmpty()) arr.put(s)
        return arr.toString()
    }

    fun decodeList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
