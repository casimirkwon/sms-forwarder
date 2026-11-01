package io.github.casimirkwon.smsforwarder.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 규칙별 시간대(전달할 시간). JSON 으로 저장한다:
 * `{"always":true,"daily":{"on":false,"from":0,"to":0},"days":[{"on":…,"from":…,"to":…} × 7(일~토)]}` — 시각은 HHMM 정수.
 * `always` 가 true 이면 언제나 전달하고, 아니면 `daily` 가 켜져 있으면 매일 그 시간대, 꺼져 있으면 켜진 요일의 시간대에만 전달한다.
 * 끝 시각이 시작보다 작거나 같으면 자정을 넘기는 시간대로 본다.
 */
object ActiveHours {
    /** 한 칸(매일 또는 요일 하나): 사용 여부와 시작·끝(HHMM). */
    data class Slot(val on: Boolean, val start: Int, val end: Int)

    /** 편집 상태: always=true 면 항상(나머지 칸은 보존만), days 는 일~토 순서 7칸. */
    data class Schedule(val always: Boolean, val daily: Slot, val days: List<Slot>)

    private val OFF = Slot(false, 0, 0)

    val DEFAULT: String = build(Schedule(always = true, daily = OFF, days = List(7) { OFF }))

    private fun slotJson(s: Slot) = JSONObject().put("on", s.on).put("from", s.start).put("to", s.end)

    private fun slotOf(o: JSONObject?): Slot? {
        if (o == null || !o.has("on") || !o.has("from") || !o.has("to")) return null
        val on = o.opt("on") as? Boolean ?: return null
        val from = (o.opt("from") as? Number)?.toInt() ?: return null
        val to = (o.opt("to") as? Number)?.toInt() ?: return null
        return Slot(on, from, to)
    }

    /** JSON → 편집 상태. 형식이 맞지 않으면 null. */
    fun parseOrNull(spec: String?): Schedule? {
        if (spec.isNullOrBlank()) return null
        return try {
            val o = JSONObject(spec)
            val always = o.opt("always") as? Boolean ?: return null
            val daily = slotOf(o.optJSONObject("daily")) ?: return null
            val arr = o.optJSONArray("days") ?: return null
            if (arr.length() != 7) return null
            val days = (0 until 7).map { slotOf(arr.optJSONObject(it)) ?: return null }
            Schedule(always, daily, days)
        } catch (_: Exception) {
            null
        }
    }

    fun isValid(spec: String?): Boolean = parseOrNull(spec) != null

    /** JSON → 편집 상태. 형식이 틀리면 기본값(항상). */
    fun parse(spec: String?): Schedule = parseOrNull(spec) ?: parseOrNull(DEFAULT)!!

    /** 편집 상태 → JSON. */
    fun build(sc: Schedule): String {
        val days = if (sc.days.size == 7) sc.days else List(7) { OFF }
        val arr = JSONArray()
        for (d in days) arr.put(slotJson(d))
        return JSONObject().put("always", sc.always).put("daily", slotJson(sc.daily)).put("days", arr).toString()
    }

    private fun inWindow(start: Int, end: Int, now: Int): Boolean =
        if (start < end) now in start..end else (now in start..2400) || (now in 0..end)

    /** 지금이 전달할 시간대인지. 형식이 틀리면 기본값(항상)으로 본다. */
    fun isActive(spec: String?, cal: Calendar = Calendar.getInstance()): Boolean {
        val sc = parse(spec)
        if (sc.always) return true
        val now = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        if (sc.daily.on) return inWindow(sc.daily.start, sc.daily.end, now)
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 일=1
        val today = sc.days[dow - 1]
        return today.on && inWindow(today.start, today.end, now)
    }

    /** "HHMM" 정수(시 0~23, 분 0~59)가 맞는지. */
    fun isValidHhmm(v: Int) = v / 100 in 0..23 && v % 100 in 0..59
}
