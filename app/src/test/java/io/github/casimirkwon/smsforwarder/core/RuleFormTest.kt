package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 규칙 편집 화면의 입력 상태(RuleForm) ↔ RuleEntity 왕복·검증 시험. */
class RuleFormTest {
    private fun roundTrip(r: RuleEntity): RuleEntity {
        val res = RuleForm.of(r).toRule(r, "이름 없음")
        assertTrue(res.toString(), res is RuleForm.Result.Ok)
        return (res as RuleForm.Result.Ok).rule
    }

    private val sms = RuleEntity(
        id = 7, name = "문자 규칙 1", template = "발신: {보낸이}({연락처})\n{본문}",
        recipients = Conditions.encodeList(listOf("01012345678", "01099998888")),
        senderCond = ConditionText.fromLines("1588\n또는\n!광고"),
        bodyCond = ConditionText.fromLines("결제\n승인"),
        replacements = ReplaceWord.toJson(listOf("a*b" to "c\\nd", "x" to "")),
        ignoreCase = true, wildcard = true, regexReplace = true, removeEmptyLine = true,
        simInSlot = 1, simOutSlot = 0, notifyResult = false, saveHistory = false, position = 3,
        schedule = ActiveHours.build(ActiveHours.Schedule(false, ActiveHours.Slot(true, 900, 1800), List(7) { ActiveHours.Slot(false, 0, 0) })),
    )

    @Test fun 문자_규칙_왕복() = assertEquals(sms, roundTrip(sms))

    @Test fun 전부_전달_규칙_왕복() {
        val r = sms.copy(matchAll = true, allowOtp = true, senderCond = "[]", bodyCond = "[]", replacements = "[]")
        assertEquals(r, roundTrip(r))
    }

    @Test fun 알림_v4_자동_왕복() {
        val r = RuleEntity(
            name = "알림 규칙 2", kind = RuleKind.NOTIFICATION, level = 4, template = "{본문}", skipEmptyNoti = true,
            recipients = Conditions.encodeList(listOf("01011112222")), apps = NotiRule.serializePackages(listOf("com.a", "com.b")),
        )
        assertEquals(r, roundTrip(r))
    }

    @Test fun 알림_v4_직접_고르기_왕복() {
        val r = RuleEntity(
            name = "알림", kind = RuleKind.NOTIFICATION, level = 4, template = "{앱}: {본문}",
            recipients = Conditions.encodeList(listOf("010")), composition = NotiCompose.serialize(listOf(NotiPart.EXTRA, NotiPart.TITLE, NotiPart.BIG)),
            bodyCond = ConditionText.fromLines("입금"),
        )
        assertEquals(r, roundTrip(r))
    }

    @Test fun 알림_구형_왕복() {
        val r = RuleEntity(
            name = "구형", kind = RuleKind.NOTIFICATION, level = 3, template = "{보낸이}\n{본문}",
            recipients = Conditions.encodeList(listOf("010")), senderCond = ConditionText.fromLines("제목"),
            useText = true, useBigText = true, useMessageText = true, skipEmptyNoti = true,
        )
        assertEquals(r, roundTrip(r))
    }

    @Test fun 이름은_앞뒤_공백을_자르고_비면_기본_이름() {
        val r = sms.copy(name = "  ")
        val res = RuleForm.of(r).toRule(r, "이름 없음") as RuleForm.Result.Ok
        assertEquals("이름 없음", res.rule.name)
        val r2 = sms.copy(name = " 가 ")
        assertEquals("가", (RuleForm.of(r2).toRule(r2, "x") as RuleForm.Result.Ok).rule.name)
    }

    @Test fun 수신처가_비면_거절() {
        val r = sms.copy(recipients = "[]")
        assertEquals(RuleForm.Result.NoRecipient, RuleForm.of(r).toRule(r, "x"))
        val f = RuleForm.of(sms).copy(recipients = " \n  \n")
        assertEquals(RuleForm.Result.NoRecipient, f.toRule(sms, "x"))
    }

    @Test fun 찾을말이_빈_단어바꾸기는_거절하고_바꿀말도_빈_줄은_허용() {
        val bad = RuleForm.of(sms).copy(replaceLines = "=>새말")
        assertEquals(RuleForm.Result.BadReplace, bad.toRule(sms, "x"))
        val ok = RuleForm.of(sms).copy(replaceLines = "찾기\n\n바꿈=>")
        val r = (ok.toRule(sms, "x") as RuleForm.Result.Ok).rule
        assertEquals(listOf("찾기" to "", "바꿈" to ""), ReplaceWord.parsePairs(r.replacements))
    }

    @Test fun 알림_규칙은_SIM_칸을_바꿔도_원래_값을_유지() {
        val r = RuleEntity(name = "n", kind = RuleKind.NOTIFICATION, level = 4, recipients = Conditions.encodeList(listOf("010")), simInSlot = 1, simOutSlot = 0)
        val out = (RuleForm.of(r).copy(simIn = -1, simOut = -1).toRule(r, "x") as RuleForm.Result.Ok).rule
        assertEquals(1, out.simInSlot)
        assertEquals(0, out.simOutSlot)
    }

    @Test fun 문자_규칙은_알림_전용_칸을_바꿔도_원래_값을_유지() {
        val out = (RuleForm.of(sms).copy(apps = NotiRule.serializePackages(listOf("x")), useText = true, skipEmpty = true, compAuto = false, compParts = listOf(NotiPart.TITLE)).toRule(sms, "x") as RuleForm.Result.Ok).rule
        assertEquals(sms.apps, out.apps)
        assertEquals(sms.useText, out.useText)
        assertEquals(sms.skipEmptyNoti, out.skipEmptyNoti)
        assertEquals(sms.composition, out.composition)
    }

    @Test fun v4_직접_고르기에서_모두_해제하면_자동과_같다() {
        val r = RuleEntity(name = "n", kind = RuleKind.NOTIFICATION, level = 4, recipients = Conditions.encodeList(listOf("010")), composition = NotiCompose.serialize(listOf(NotiPart.TITLE)))
        val out = (RuleForm.of(r).copy(compParts = emptyList()).toRule(r, "x") as RuleForm.Result.Ok).rule
        assertTrue(NotiCompose.parse(out.composition).isEmpty())
    }

    @Test fun 지연_전달_칸은_숨김_상태에서_원래_값을_유지() {
        val r = sms.copy(delayEnabled = true, delayMinutes = 30)
        val out = (RuleForm.of(r).copy(delayOn = false, delayMinutes = 0).toRule(r, "x") as RuleForm.Result.Ok).rule
        assertEquals(DelayPolicy.SHOW_DELAY_UI.not(), out.delayEnabled && out.delayMinutes == 30L)
    }
}
