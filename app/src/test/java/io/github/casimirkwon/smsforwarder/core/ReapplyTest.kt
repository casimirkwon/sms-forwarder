package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.ReceivedEntity
import io.github.casimirkwon.smsforwarder.db.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.junit.Test

/** 이력 "필터 다시 적용": 이력 행 ↔ 이벤트 복원, 복원한 이벤트의 판정, 반환값. */
class ReapplyTest {
    private val noti = IncomingNotification(
        title = "제목", text = "본문", subText = "서브", bigText = "긴 본문", messageText = "메시지", oldText = "옛",
        timeMillis = 1234L, packageName = "com.x.app", imageCount = 2, extraContent = "대화방\n보낸이",
    )

    @Test fun 알림_이력_왕복_필드가_그대로_복원된다_이미지는_빠진다() {
        val h = Reapply.fromNotification(noti, now = 9999L, appVersionCode = 42L)
        assertEquals("noti", h.kind)
        assertEquals(42L, h.appVersionCode)
        assertEquals(noti.copy(imageCount = 0), Reapply.toNotification(h))
    }

    @Test fun 알림_이력_목록_본문은_접두어를_떼고_첫_비지않은_필드() {
        val n = noti.copy(packageName = NotiText.SUMMARY_PREFIX + "com.x.app", text = "\n", bigText = "")
        val h = Reapply.fromNotification(n, 1L, null)
        assertEquals("[com.x.app] 메시지", h.body)
        // 접두어는 행에 그대로 남겨 두고 복원해도 같다(규칙 평가 때 NotiRule.strippedPackage 로 뗀다)
        assertEquals(NotiText.SUMMARY_PREFIX + "com.x.app", h.packageName)
        assertEquals(n.copy(imageCount = 0), Reapply.toNotification(h))
        assertNull(h.appVersionCode)
    }

    @Test fun SMS_이력_왕복_발신번호_본문_수신시각_SIM() {
        val sms = IncomingSms("01012345678", "안녕\n하세요", 5000L)
        val h = Reapply.fromSms(sms, now = 6000L, simSlot = 1)
        assertEquals(1, h.simSlot)
        assertEquals("sms", h.kind)
        assertEquals(sms, Reapply.toSms(h))
    }

    @Test fun 스키마5_이하에서_올라온_행() {
        // 새 열이 기본값(빈 값)인 SMS 행은 body 로 복원, 알림 행은 패키지가 없어 복원할 수 없다
        val oldSms = ReceivedEntity(id = 3, time = 1, receiveTime = 2, address = "1588", body = "옛 본문", simSlot = 0)
        assertTrue(Reapply.canReapply(oldSms))
        assertEquals(IncomingSms("1588", "옛 본문", 2), Reapply.toSms(oldSms))
        val oldNoti = ReceivedEntity(id = 4, time = 1, receiveTime = 2, address = "t", body = "[p] x", kind = "noti")
        assertFalse(Reapply.canReapply(oldNoti))
        assertTrue(Reapply.canReapply(Reapply.fromNotification(noti, 1, null)))
    }

    @Test fun 반환값은_대기열에_넣은_규칙이_하나라도_있을_때만_true() {
        assertFalse(Reapply.anyQueued(emptyList()))
        assertFalse(Reapply.anyQueued(listOf(false, false)))
        assertTrue(Reapply.anyQueued(listOf(false, true, false)))
    }

    private fun notiFilter(level: Int, composition: String = "", bodyText: String = "", senderText: String = "") =
        RuleEntity(
            name = "f", senderCond = ConditionText.fromLines(senderText), bodyCond = ConditionText.fromLines(bodyText), level = level,
            composition = if (composition.isEmpty()) "" else NotiCompose.serialize(composition.split(",").mapNotNull { NotiPart.fromKey(it) }),
            kind = RuleKind.NOTIFICATION,
        )

    private fun evalBody(f: RuleEntity, n: IncomingNotification) = NotiRule.evaluate(f, n) { _, _ -> null }

    @Test fun 복원한_알림도_수신_때와_같이_v4_v3_판정된다() {
        val v4 = notiFilter(4, composition = "title,extra,text")
        val original = evalBody(v4, noti)
        val restored = evalBody(v4, Reapply.toNotification(Reapply.fromNotification(noti, 1, null)))
        assertNull(original.skip)
        assertEquals(original, restored)
        assertEquals("제목\n대화방\n보낸이\n본문", restored.body)

        // v4: 규칙을 바꾼 뒤 다시 적용하면 바뀐 규칙으로 판정 — 본문 조건이 맞지 않으면 5 로 건너뜀(통과 못 함)
        val changed = notiFilter(4, composition = "title,text", bodyText = "없는말")
        assertEquals(StepCode.NOT_MATCH_TEXT, evalBody(changed, Reapply.toNotification(Reapply.fromNotification(noti, 1, null))).skip)
        // v3: 제목 조건도 본다 — 제목이 달라 불통과
        val v3 = notiFilter(3, senderText = "다른제목")
        assertEquals(evalBody(v3, noti), evalBody(v3, Reapply.toNotification(Reapply.fromNotification(noti, 1, null))))
        assertEquals(StepCode.NOT_MATCH_NOTI_SENDER, evalBody(v3, noti).skip)
    }
}
