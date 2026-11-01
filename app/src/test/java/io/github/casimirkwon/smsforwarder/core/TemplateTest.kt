package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TemplateTest {
    // 2026-10-31 14:05 (KST) 고정
    private val time: Long = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
        clear(); set(2026, Calendar.OCTOBER, 31, 14, 5, 0)
    }.timeInMillis

    private fun input(body: String? = "본문입니다", address: String? = "01012345678", sim: String? = "SIM1") = TemplateInput(
        body = body, address = address, simName = sim, timeMillis = time,
        filterName = "내규칙", contactName = { "홍길동" }, formattedAddress = { "010-1234-5678" },
        appLabel = "카톡", subText = "부제", locale = Locale.KOREA,
    )

    @Test fun emptyTemplateReturnsBodyOnly() {
        assertEquals("본문입니다", Template.apply("", input()))
        assertEquals("본문입니다", Template.apply(null, input()))
    }

    @Test fun defaultTemplate() {
        assertEquals(
            "보낸사람 : 01012345678(홍길동)\n본문입니다",
            Template.apply("보낸사람 : {보낸이}({연락처})\n{본문}", input()),
        )
    }

    @Test fun contactNameMissingBecomesEmpty() {
        val i = TemplateInput(body = "b", address = "1", simName = "", timeMillis = time, contactName = { null })
        assertEquals("1()b", Template.apply("{보낸이}({연락처}){본문}", i))
    }

    @Test fun simFilterAndFormattedNumber() {
        assertEquals("내규칙|SIM1|010-1234-5678|010-1234-5678", Template.apply("{규칙}|{SIM}|{보낸이(서식)}|{보낸이(서식)}", input()))
    }

    @Test fun timeTokens() {
        val tz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            assertEquals("2026/10/31 오후 2:05 14시 05분 토", Template.apply("{연}/{월}/{일} {오전오후} {시12}:{분} {시24}시 {분}분 {요일}", input()))
            assertEquals("10/31 오후 2:05", Template.apply("{시각}", input()))
        } finally {
            TimeZone.setDefault(tz)
        }
    }

    @Test fun notificationTokens() {
        assertEquals("카톡/부제", Template.apply("{앱}/{서브}", input()))
    }

    @Test fun nullBodyAndSimTreatedAsEmpty() {
        assertEquals("[][]", Template.apply("[{본문}][{SIM}]", input(body = null, sim = null)))
    }

    @Test fun replacementOrderQuirkIsPreserved() {
        // {본문} 을 먼저 치환한 뒤 {일} 같은 뒤쪽 토큰을 치환하므로 본문 속 "{일}" 도 바뀐다.
        val r = Template.apply("{본문}", input(body = "50{일}"))
        assertEquals("5031", r) // 치환된 본문 "50{일}" 의 {일} 이 일(31)로 다시 바뀐다
    }

    @Test fun 추가_토큰은_추가_내용으로_치환되고_없으면_빈_문자열() {
        val withExtra = TemplateInput(body = "본문", address = "T", simName = "", timeMillis = time, extraContent = "대화방\n보낸이", locale = Locale.KOREA)
        assertEquals("본문|대화방\n보낸이", Template.apply("{본문}|{추가}", withExtra))
        val without = TemplateInput(body = "본문", address = "T", simName = "", timeMillis = time, locale = Locale.KOREA)
        assertEquals("본문|", Template.apply("{본문}|{추가}", without))
    }

    @Test fun 추가_토큰은_서브_토큰_다음_순서라_서브_값_안의_추가_토큰도_바뀐다() {
        val i = TemplateInput(body = "b", address = "T", simName = "", timeMillis = time, subText = "{추가}", extraContent = "E", locale = Locale.KOREA)
        assertEquals("E", Template.apply("{서브}", i))
    }
}
