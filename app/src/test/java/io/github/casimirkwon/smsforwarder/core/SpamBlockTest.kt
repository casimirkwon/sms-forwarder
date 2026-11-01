package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpamBlockTest {
    private val phone = { a: String, b: String -> a.replace("-", "") == b.replace("-", "") }
    private val rules = listOf(
        SpamRule(3, SpamRule.BODY, "광고"),
        SpamRule(1, SpamRule.PHONE, "010-1111-2222"),
        SpamRule(2, SpamRule.NOTI_TITLE, "홍보"),
    )

    @Test fun SMS_는_번호_규칙으로_차단된다() {
        assertEquals("010-1111-2222", SpamBlock.match(rules, "01011112222", "안녕", true, phone))
    }

    @Test fun SMS_는_본문_규칙으로_대소문자_무시하고_차단된다() {
        assertEquals("AD", SpamBlock.match(listOf(SpamRule(1, SpamRule.BODY, "AD")), "x", "this is an ad!", true, phone))
        assertEquals("광고", SpamBlock.match(rules, "02", "(광고) 세일", true, phone))
    }

    @Test fun SMS_에는_알림_제목_규칙을_쓰지_않는다() {
        assertNull(SpamBlock.match(rules, "홍보팀", "안녕", true, phone))
    }

    @Test fun 알림에는_번호_규칙을_쓰지_않고_제목_규칙은_대소문자를_구분한다() {
        assertNull(SpamBlock.match(rules, "01011112222", "안녕", false, phone))
        assertEquals("홍보", SpamBlock.match(rules, "홍보팀", "안녕", false, phone))
        assertNull(SpamBlock.match(listOf(SpamRule(1, SpamRule.NOTI_TITLE, "Ad")), "ad", "x", false, phone))
    }

    @Test fun 알림에도_본문_규칙은_항상_적용된다() {
        assertEquals("광고", SpamBlock.match(rules, "아무개", "광고입니다", false, phone))
    }

    @Test fun 규칙은_id_순으로_보고_처음_맞은_규칙을_돌려준다() {
        // id 1(번호)이 id 3(본문)보다 먼저
        assertEquals("010-1111-2222", SpamBlock.match(rules, "010-1111-2222", "광고", true, phone))
    }

    @Test fun 규칙이_없으면_null() {
        assertNull(SpamBlock.match(emptyList(), "a", "b", true, phone))
    }
}
