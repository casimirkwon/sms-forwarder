package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImportTest {
    private fun cardRule(level: Int = 3) = RuleEntity(
        id = 7,
        name = "카드승인 알림전달",
        kind = RuleKind.NOTIFICATION,
        level = level,
        senderCond = ConditionText.fromLines("현대카드\n또는\n삼성카드\n또는\n1588-8900"),
        bodyCond = ConditionText.fromLines("승인\n!(5746)\n!(2162)\n또는\n취소\n!(2162)\n!(5746)"),
        recipients = Conditions.encodeList(listOf("01012345678")),
        apps = NotiRule.serializePackages(listOf("com.shcard.smartpay", "com.hyundaicard.appcard")),
        useBigText = true,
        useMessageText = true,
        skipEmptyNoti = true,
        replacements = "[]",
        template = "{본문}",
        simInSlot = -1,
        simOutSlot = 1,
        position = 3,
        composition = if (level == 4) NotiCompose.serialize(listOf(NotiPart.TITLE, NotiPart.BIG)) else "",
    )

    private fun roundTrip(f: RuleEntity): RuleEntity {
        val json = ExportImport.build(listOf(f), emptyMap(), emptyList(), "1.1.0", 1000L)
        val p = ExportImport.parse(json)
        assertTrue(p.toString(), p is ExportImport.Parsed.Ok)
        val d = (p as ExportImport.Parsed.Ok).data
        assertEquals(1, d.rules.size)
        return d.rules[0]
    }

    @Test fun 왕복_level_2_3_4_모든_필드_보존() {
        for (v in listOf(2, 3, 4)) {
            val f = cardRule(v)
            val g = roundTrip(f)
            // id 와 position 은 가져올 때 새로 매긴다
            assertEquals(f.copy(id = 0, position = 0), g)
            assertEquals(v, g.level)
        }
    }

    @Test fun 문자_규칙도_왕복() {
        val f = RuleEntity(
            name = "문자", senderCond = ConditionText.fromLines("010"), recipients = Conditions.encodeList(listOf("01099998888")),
            ignoreCase = true, replacements = ReplaceWord.toJson(listOf("가" to "나", "x" to "")), regexReplace = true, removeEmptyLine = true,
            template = "보낸사람 : {보낸이}\n{본문}", level = 3, simInSlot = 0, delayEnabled = true, delayMinutes = 15,
            schedule = ActiveHours.build(ActiveHours.Schedule(false, ActiveHours.Slot(true, 900, 1800), List(7) { ActiveHours.Slot(it == 2, 0, 600) })),
            allowOtp = true, matchAll = false, wildcard = true, notifyResult = false, saveHistory = false,
        )
        assertEquals(f, roundTrip(f))
    }

    @Test fun 종류와_켜짐_여부_왕복() {
        for (k in RuleKind.entries) assertEquals(k, roundTrip(RuleEntity(name = "k", kind = k)).kind)
        assertFalse(roundTrip(cardRule().copy(enabled = false)).enabled)
        assertTrue(roundTrip(cardRule()).enabled)
    }

    @Test fun 알수없는_필드는_무시() {
        val o = JSONObject(ExportImport.build(listOf(cardRule()), emptyMap(), emptyList(), "1.1.0", 1L))
        o.put("future", "x")
        o.getJSONArray("rules").getJSONObject(0).put("newField", 5)
        val p = ExportImport.parse(o.toString())
        assertTrue(p is ExportImport.Parsed.Ok)
    }

    private fun rejected(text: String): String {
        val p = ExportImport.parse(text)
        assertTrue("거절돼야 함: $text", p is ExportImport.Parsed.Rejected)
        return (p as ExportImport.Parsed.Rejected).reason
    }

    private fun base(): JSONObject = JSONObject(ExportImport.build(listOf(cardRule()), emptyMap(), emptyList(), "1.1.0", 1L))

    private fun JSONObject.rule0(): JSONObject = getJSONArray("rules").getJSONObject(0)

    @Test fun 잘못된_파일_거절() {
        rejected("")
        rejected("hello")
        rejected("[1,2]")
        rejected("{\"a\":")
        rejected("{}")
        // format 불일치
        rejected(base().put("format", "other").toString())
        // schema 불일치·누락
        assertTrue(rejected(base().put("schema", 3).toString()).contains("schema"))
        rejected(base().apply { remove("schema") }.toString())
        // 규칙의 필수 필드 누락
        for (k in listOf("name", "kind")) {
            val o = base()
            o.rule0().remove(k)
            assertTrue(rejected(o.toString()).contains(k))
        }
        // 형식 틀림
        val o1 = base(); o1.rule0().put("enabled", "yes"); rejected(o1.toString())
        val o2 = base(); o2.rule0().put("level", "v3"); rejected(o2.toString())
        val o3 = base(); o3.rule0().put("schedule", JSONObject().put("always", "x")); rejected(o3.toString())
        val o4 = base(); o4.put("rules", "nope"); rejected(o4.toString())
        val o5 = base(); o5.rule0().put("kind", "email"); rejected(o5.toString())
        val o6 = base(); o6.rule0().put("senderCond", org.json.JSONArray().put("a")); rejected(o6.toString())
        val o7 = base(); o7.rule0().put("recipients", org.json.JSONArray().put(5)); rejected(o7.toString())
        val o8 = base(); o8.rule0().put("level", 100); rejected(o8.toString())
        // 가져올 것이 하나도 없음
        rejected(base().put("rules", org.json.JSONArray()).put("settings", JSONObject()).put("spamRules", org.json.JSONArray()).toString())
    }

    @Test fun 설정_왕복과_내부_키_제외() {
        val settings = mapOf<String, Any>(
            "forwarding_enabled" to false,
            "retry_limit" to 5,
            "noti_dedupe_minutes" to 90L,
            "sim_label_1" to "회사",
            "monthly_used" to 42, // 내부 상태 — 내보내지 않는다
        )
        val json = ExportImport.build(emptyList(), settings, emptyList(), "1.1.0", 5L)
        assertFalse(json.contains("monthly_used"))
        val d = (ExportImport.parse(json) as ExportImport.Parsed.Ok).data
        assertEquals(false, d.settings!!["forwarding_enabled"])
        assertEquals(5, d.settings!!["retry_limit"])
        assertEquals(90L, d.settings!!["noti_dedupe_minutes"])
        assertEquals("회사", d.settings!!["sim_label_1"])
        assertNull(d.settings!!["monthly_used"])
        assertEquals(5L, d.exportedAt)
        assertEquals("1.1.0", d.appVersion)
    }

    @Test fun 설정_형식이_틀리면_거절_내부키와_모르는_키는_무시() {
        val o = base()
        o.put("settings", JSONObject().put("retry_limit", "3"))
        assertTrue(rejected(o.toString()).contains("retry_limit"))
        val o2 = base()
        o2.put("settings", JSONObject().put("monthly_used", 9).put("unknown_key", true).put("retry_limit", 4))
        val d = (ExportImport.parse(o2.toString()) as ExportImport.Parsed.Ok).data
        assertEquals(mapOf<String, Any>("retry_limit" to 4), d.settings)
    }

    @Test fun 스팸_규칙_왕복과_검증() {
        val json = ExportImport.build(listOf(cardRule()), emptyMap(), listOf(ExportImport.SpamItem(1, "광고"), ExportImport.SpamItem(2, "대출")), "1.1.0", 1L)
        val d = (ExportImport.parse(json) as ExportImport.Parsed.Ok).data
        assertEquals(listOf(ExportImport.SpamItem(1, "광고"), ExportImport.SpamItem(2, "대출")), d.spamRules)
        rejected(JSONObject(json).put("spamRules", org.json.JSONArray().put(JSONObject().put("type", 9).put("data", "x"))).toString())
    }

    @Test fun 이름_충돌은_뒤에_번호를_붙인다() {
        val a = RuleEntity(name = "A")
        val out = ExportImport.uniqueNames(listOf("A", "A (2)", "B"), listOf(a, a, RuleEntity(name = "C")))
        assertEquals(listOf("A (3)", "A (4)", "C"), out.map { it.name })
        // 충돌이 없으면 그대로
        assertEquals(listOf("A"), ExportImport.uniqueNames(emptyList(), listOf(a)).map { it.name })
        // 다른 필드는 그대로
        val b = RuleEntity(name = "A", level = 3, apps = NotiRule.serializePackages(listOf("p")))
        assertEquals(b.copy(name = "A (2)"), ExportImport.uniqueNames(listOf("A"), listOf(b))[0])
    }

    /** 가져온 level 3 알림 규칙은 v4 가 아니고(제목 조건 평가), 기존 level 3 경로 그대로 동작한다. */
    @Test fun 가져온_level3_규칙은_제목_조건을_평가한다() {
        val f = roundTrip(cardRule(3))
        assertEquals(3, f.level)
        assertFalse(f.isV4)
        fun noti(title: String, big: String) = IncomingNotification(title = title, text = "", subText = "", bigText = big, messageText = "", oldText = "", timeMillis = 0, packageName = "com.hyundaicard.appcard")
        val none: (String, String) -> String? = { _, _ -> null }
        // 제목·내용 모두 통과
        assertNull(NotiRule.evaluate(f, noti("현대카드", "현대카드 승인 12,000원"), none).skip)
        assertNull(NotiRule.evaluate(f, noti("삼성카드", "취소 3,000원"), none).skip)
        // 제목이 안 맞으면 제목 불일치
        assertEquals(StepCode.NOT_MATCH_NOTI_SENDER, NotiRule.evaluate(f, noti("광고", "승인 12,000원"), none).skip)
        // 내용의 제외 단어 → 본문 불일치
        assertEquals(StepCode.NOT_MATCH_TEXT, NotiRule.evaluate(f, noti("현대카드", "승인 (5746) 12,000원"), none).skip)
        // 같은 규칙을 level 4 로 올리면 제목 조건이 사라진다(가져오기가 올리지 않음을 대비 확인)
        val v4 = f.copy(level = 4)
        assertNull(NotiRule.evaluate(v4, noti("광고", "승인 12,000원"), none).skip)
    }
}
