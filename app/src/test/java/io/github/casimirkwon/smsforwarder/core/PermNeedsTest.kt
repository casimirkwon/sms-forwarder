package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PermNeedsTest {
    private val all = PermNeeds.Have(phoneState = true, smsReceive = true, smsSend = true, contacts = true)
    private val simple = listOf(PermNeeds.FilterNeed(hasDestination = true, usesContactName = false))

    @Test fun noFiltersNeedNothing() {
        assertEquals(0, PermNeeds.firstMissing(PermNeeds.Have(false, false, false, false), emptyList()))
    }

    @Test fun allGrantedIsOk() = assertEquals(0, PermNeeds.firstMissing(all, simple))

    @Test fun reportsFirstMissingInFixedOrder() {
        assertEquals(PermNeeds.SMS_RECEIVE, PermNeeds.firstMissing(PermNeeds.Have(false, false, false, true), simple))
        assertEquals(PermNeeds.SMS_SEND, PermNeeds.firstMissing(PermNeeds.Have(false, true, false, true), simple))
        assertEquals(PermNeeds.PHONE_STATE, PermNeeds.firstMissing(PermNeeds.Have(false, true, true, true), simple))
    }

    @Test fun contactsOnlyRequiredWhenTemplateUsesName() {
        val noContacts = all.copy(contacts = false)
        assertEquals(0, PermNeeds.firstMissing(noContacts, simple))
        val withName = listOf(PermNeeds.FilterNeed(hasDestination = true, usesContactName = true))
        assertEquals(PermNeeds.CONTACTS, PermNeeds.firstMissing(noContacts, withName))
    }

    @Test fun filterWithoutDestinationNeedsNoSendPermission() {
        val f = listOf(PermNeeds.FilterNeed(hasDestination = false, usesContactName = false))
        assertEquals(0, PermNeeds.firstMissing(PermNeeds.Have(false, true, false, false), f))
    }

    @Test fun 알림_규칙은_문자_수신_권한_대신_알림_접근을_본다() {
        val noti = listOf(PermNeeds.FilterNeed(hasDestination = true, usesContactName = false, isNotification = true))
        // 문자 수신 권한이 없어도 알림 규칙은 상관없다
        assertEquals(0, PermNeeds.firstMissing(all.copy(smsReceive = false), noti))
        assertEquals(PermNeeds.NOTIFICATION_ACCESS, PermNeeds.firstMissing(all.copy(notificationAccess = false), noti))
        // 알림 접근 다음에 발송·전화 상태 순서
        assertEquals(PermNeeds.SMS_SEND, PermNeeds.firstMissing(all.copy(smsSend = false), noti))
    }

    @Test fun 문자_규칙은_알림_접근이_없어도_된다() {
        assertEquals(0, PermNeeds.firstMissing(all.copy(notificationAccess = false), simple))
    }

    @Test fun 문자_규칙과_알림_규칙이_같이_있으면_규칙_순서대로_첫_부족_권한() {
        val both = listOf(
            PermNeeds.FilterNeed(hasDestination = true, usesContactName = false, isNotification = true),
            PermNeeds.FilterNeed(hasDestination = true, usesContactName = false),
        )
        assertEquals(PermNeeds.NOTIFICATION_ACCESS, PermNeeds.firstMissing(PermNeeds.Have(true, false, true, true, false), both))
        assertEquals(PermNeeds.SMS_RECEIVE, PermNeeds.firstMissing(PermNeeds.Have(true, false, true, true, true), both))
    }

    // --- 알림 접근이 빠졌을 때: 문자 전달은 계속, 알림 규칙 전달만 멈춤 ---

    private val smsRule = PermNeeds.FilterNeed(hasDestination = true, usesContactName = false)
    private val notiRule = PermNeeds.FilterNeed(hasDestination = true, usesContactName = false, isNotification = true)
    private val noAccess = all.copy(notificationAccess = false)

    @Test fun 알림_접근이_없어도_문자_경로는_통과한다() {
        // 알림 규칙이 규칙 목록 맨 앞에 있어도(예전에는 문자 전달까지 막았다) 문자 경로는 알림 접근을 보지 않는다
        val rules = listOf(notiRule, smsRule)
        assertEquals(0, PermNeeds.firstMissing(noAccess, rules, checkNotificationAccess = false))
        // 알림 경로는 그대로 알림 접근 부족을 알려 준다
        assertEquals(PermNeeds.NOTIFICATION_ACCESS, PermNeeds.firstMissing(noAccess, rules))
    }

    @Test fun 알림_접근을_건너뛰어도_다른_권한_부족은_그대로_알린다() {
        val rules = listOf(notiRule, smsRule)
        assertEquals(PermNeeds.SMS_SEND, PermNeeds.firstMissing(noAccess.copy(smsSend = false), rules, checkNotificationAccess = false))
        assertEquals(PermNeeds.SMS_RECEIVE, PermNeeds.firstMissing(noAccess.copy(smsReceive = false), rules, checkNotificationAccess = false))
    }

    @Test fun 경고는_한_번만_띄우고_권한이_돌아오면_초기화된다() {
        var warned = false
        var shown = 0
        fun event(hasAccess: Boolean, needsAccess: Boolean = true) {
            val d = NotiAccessWarn.decide(hasAccess, needsAccess, warned)
            if (d.show) shown++
            warned = d.warned
        }
        repeat(5) { event(hasAccess = false) }      // 권한 없는 채로 알림·문자 이벤트가 여러 번
        assertEquals(1, shown)
        event(hasAccess = true)                    // 복구 → 상태 초기화, 경고 없음
        assertEquals(1, shown)
        assertFalse(warned)
        repeat(3) { event(hasAccess = false) }      // 다시 빠지면 한 번 더
        assertEquals(2, shown)
    }

    @Test fun 알림_규칙이_없으면_경고하지_않고_상태도_바꾸지_않는다() {
        assertEquals(NotiAccessWarn.Decision(show = false, warned = false), NotiAccessWarn.decide(hasAccess = false, needsAccess = false, warned = false))
        assertEquals(NotiAccessWarn.Decision(show = false, warned = true), NotiAccessWarn.decide(hasAccess = false, needsAccess = false, warned = true))
        // 접근이 있으면 규칙 유무와 상관없이 초기화
        assertEquals(NotiAccessWarn.Decision(show = false, warned = false), NotiAccessWarn.decide(hasAccess = true, needsAccess = false, warned = true))
    }
}
