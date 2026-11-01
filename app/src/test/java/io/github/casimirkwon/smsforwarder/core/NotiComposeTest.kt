package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알림 필터 v4 "보낼 내용" 구성·추가 내용·v4 판정 순서. */
class NotiComposeTest {
    private fun noti(
        title: String = "", text: String = "", sub: String = "", big: String = "", msg: String = "", old: String = "", extra: String = "",
    ) = IncomingNotification(title, text, sub, big, msg, old, 1000L, "com.x", 0, extra)

    private fun auto(n: IncomingNotification) = NotiCompose.auto(n)

    // --- 자동 합성

    @Test fun 자동_메시지_본문이_있으면_그것을_쓰고_text_big은_보지_않는다() {
        assertEquals("T\n마지막 메시지", auto(noti(title = "T", text = "요약", big = "긴", msg = "마지막 메시지")))
    }

    @Test fun 자동_메시지_없으면_text와_big_한쪽만_있으면_그쪽() {
        assertEquals("T\n본문", auto(noti(title = "T", text = "본문")))
        assertEquals("T\n긴 본문", auto(noti(title = "T", big = "긴 본문")))
    }

    @Test fun 자동_text가_big을_포함하면_text_big이_text를_포함하면_big() {
        assertEquals("abcdef", auto(noti(text = "abcdef", big = "cde")))
        assertEquals("abcdef", auto(noti(text = "abcdef", big = "abcdef"))) // 길이 같고 포함 → text
        assertEquals("hello world", auto(noti(text = "world", big = "hello world")))
    }

    @Test fun 자동_서로_포함하지_않으면_text_줄바꿈_big() {
        assertEquals("abcdef\nxy", auto(noti(text = "abcdef", big = "xy")))
        assertEquals("zz\nhello world", auto(noti(text = "zz", big = "hello world")))
    }

    @Test fun 자동_공백만_있으면_빈_것으로_본다() {
        assertEquals("본문", auto(noti(text = "본문", big = "   ")))
        assertEquals("긴", auto(noti(text = " \t", big = "긴")))
        assertEquals("추가", auto(noti(text = "  ", msg = " ", extra = "추가")))
    }

    @Test fun 자동_모두_비면_추가_내용() {
        assertEquals("요약문", auto(noti(extra = "요약문")))
        assertEquals("T\n요약문\n대화방", auto(noti(title = "T", extra = "요약문\n대화방")))
        // 본문이 있으면 추가 내용은 쓰지 않는다
        assertEquals("본문", auto(noti(text = "본문", extra = "요약문")))
    }

    @Test fun 자동_제목이_본문_줄과_같으면_생략() {
        assertEquals("홍길동\n안녕", auto(noti(title = "홍길동", text = "안녕")))
        assertEquals("다른\n홍길동: 안녕\n두번째", auto(noti(title = "다른", msg = "홍길동: 안녕\n두번째")))
        // 본문의 어느 줄과 같으면(첫 줄이 아니어도) 제목을 뺀다
        assertEquals("안녕\n홍길동", auto(noti(title = "홍길동", text = "안녕\n홍길동")))
        // 정규화(격리 문자 U+2066~2069 제거 + 공백 자르기) 후 비교
        assertEquals("안녕\n홍길동", auto(noti(title = "⁨ 홍길동 ⁩", text = "안녕\n홍길동")))
        assertEquals("본문", auto(noti(title = "   ", text = "본문")))
    }

    @Test fun 자동_제목은_다르면_원문_그대로_붙는다() {
        assertEquals("⁨홍길동⁩\n안녕", auto(noti(title = "⁨홍길동⁩", text = "안녕")))
    }

    @Test fun 자동_제목만_있으면_제목만() {
        assertEquals("제목뿐", auto(noti(title = "제목뿐")))
        assertEquals("", auto(noti()))
    }

    // --- 직접 선택·파싱

    @Test fun 구성_파싱은_알_수_없는_키를_버리고_중복은_처음만_남긴다() {
        assertEquals(listOf(NotiPart.TEXT, NotiPart.TITLE, NotiPart.EXTRA), NotiCompose.parse("[\" text \",\"bogus\",\"title\",\"text\",\"\",\" extra \"]"))
        assertTrue(NotiCompose.parse("").isEmpty())
        assertTrue(NotiCompose.parse("   ").isEmpty())
        assertTrue(NotiCompose.parse(null).isEmpty())
        assertTrue(NotiCompose.parse("nothing").isEmpty())
        assertTrue(NotiCompose.parse("[]").isEmpty())
        assertEquals("[\"title\",\"extra\",\"text\",\"sub\",\"big\",\"message\"]", NotiCompose.serialize(NotiPart.entries.toList()))
        assertEquals(NotiPart.entries.toList(), NotiCompose.parse(NotiCompose.serialize(NotiPart.entries.toList())))
    }

    private fun comp(keys: String) = NotiCompose.serialize(keys.split(",").mapNotNull { NotiPart.fromKey(it) })

    @Test fun 직접_선택은_고른_순서대로_빈_항목을_빼고_줄바꿈으로_잇는다() {
        val n = noti(title = "T", text = "X", sub = "", big = "B", msg = "M", extra = "E")
        assertEquals("X\nT", NotiCompose.compose(comp("text,title"), n))
        assertEquals("T\nX", NotiCompose.compose(NotiCompose.DEFAULT_DIRECT, n))
        assertEquals("E\nT\nX\nB\nM", NotiCompose.compose(comp("extra,title,text,sub,big,message"), n))
        assertEquals("T\nB", NotiCompose.compose(comp("title,sub,big"), n)) // sub 가 비어 건너뜀
        assertEquals("T", NotiCompose.compose(comp("title,title"), n))
        // 값은 그대로 쓴다(정규화하지 않음), 공백뿐인 항목은 빈 것으로 본다
        assertEquals(" X \nT", NotiCompose.compose(comp("text,sub,title"), noti(title = "T", text = " X ", sub = "  ")))
    }

    @Test fun 구성이_비거나_알_수_없는_키뿐이면_자동() {
        val n = noti(title = "T", text = "본문")
        assertEquals(auto(n), NotiCompose.compose("", n))
        assertEquals(auto(n), NotiCompose.compose(comp("zzz"), n))
    }

    // --- 추가 내용 겹침 제거

    private fun extra(
        summary: String? = null, bigTitle: String? = null, conv: String? = null, sender: String? = null, lines: List<String> = emptyList(),
        title: String = "", text: String = "", sub: String = "", big: String = "", msg: String = "", app: String = "앱",
    ) = NotiCompose.extraContent(summary, bigTitle, conv, sender, lines, title, text, sub, big, msg) { app }

    @Test fun 추가_내용은_항목_순서대로_줄바꿈으로_잇는다() {
        assertEquals("요약\n큰제목\n대화방\n보낸이\n줄1\n줄2", extra("요약", "큰제목", "대화방", "보낸이", listOf("줄1", "줄2")))
    }

    @Test fun 추가_내용은_제목_본문_서브_긴본문_메시지_앱이름과_겹치면_뺀다() {
        assertEquals("남는것", extra(summary = "제목", bigTitle = "본문", conv = "서브", sender = "긴", lines = listOf("메시지", "앱", "남는것"),
            title = "제목", text = "본문", sub = "서브", big = "긴", msg = "메시지", app = "앱"))
    }

    @Test fun 추가_내용은_본문_안의_줄과_같아도_뺀다() {
        assertEquals("c", extra(lines = listOf("a", "b", "c"), text = "a\nb"))
        assertEquals("", extra(lines = listOf("a"), big = "x\na\ny"))
    }

    @Test fun 추가_내용은_중복을_제거하고_정규화한_값으로_쓴다() {
        assertEquals("x\ny", extra(summary = " x ", bigTitle = "⁨x⁩", lines = listOf("y", "x", "y")))
        assertEquals("", extra(summary = "  ", bigTitle = null, lines = listOf("", "   ")))
    }

    @Test fun 추가_내용_앱_이름은_다른_항목이_남을_때만_조회한다() {
        var asked = 0
        val r = NotiCompose.extraContent("본문", null, null, null, emptyList(), "", "본문", "", "", "") { asked++; "앱" }
        assertEquals("", r)
        assertEquals(0, asked)
        assertEquals("요약", NotiCompose.extraContent("요약", null, null, null, emptyList(), "", "", "", "", "") { asked++; "앱" })
        assertEquals(1, asked)
    }

    // --- v4 판정(RuleEntity 단위)

    /** 시험용 도우미: [composition] 은 쉼표로 이은 항목 키, [from]·[text] 는 한 줄짜리 조건. */
    private fun rule(
        level: Int,
        kind: RuleKind = RuleKind.NOTIFICATION,
        matchAll: Boolean = false,
        skipEmpty: Boolean = false,
        allowOtp: Boolean = false,
        useText: Boolean = false,
        useSub: Boolean = false,
        composition: String = "",
        from: String = "",
        text: String = "",
        replace: String = "",
        template: String = "",
    ) = RuleEntity(
        kind = kind, level = level, matchAll = matchAll, skipEmptyNoti = skipEmpty, allowOtp = allowOtp, useText = useText, useSubText = useSub,
        composition = if (composition.isEmpty()) "" else NotiCompose.serialize(composition.split(",").mapNotNull { NotiPart.fromKey(it) }),
        senderCond = ConditionText.fromLines(from), bodyCond = ConditionText.fromLines(text), replacements = replace, template = template,
    )

    private val noSpam: (String, String) -> String? = { _, _ -> null }

    @Test fun v4_판정은_알림_규칙이고_버전_4이상() {
        assertTrue(rule(4).isV4)
        assertTrue(rule(5).isV4)
        assertFalse(rule(3).isV4)
        assertFalse(rule(4, kind = RuleKind.SMS).isV4)
        assertFalse(rule(0).isV4)
    }

    @Test fun v4는_제목_조건을_보지_않는다_코드_11이_안_생긴다() {
        val n = noti(title = "다른 제목", text = "본문")
        val g = NotiRule.evaluate(rule(4, from = "카카오"), n, noSpam)
        assertNull(g.skip)
        assertEquals("다른 제목\n본문", g.body)
        // 같은 규칙을 v3 로 두면 제목 조건이 걸려 11
        val g3 = NotiRule.evaluate(rule(3, from = "카카오"), n, noSpam)
        assertEquals(StepCode.NOT_MATCH_NOTI_SENDER, g3.skip)
    }

    @Test fun v4는_본문_조건만_구성된_본문에_건다() {
        val n = noti(title = "T", text = "배송 완료")
        assertNull(NotiRule.evaluate(rule(4, text = "배송"), n, noSpam).skip)
        assertEquals(StepCode.NOT_MATCH_TEXT, NotiRule.evaluate(rule(4, text = "주문"), n, noSpam).skip)
        // 제목에만 있는 단어는 구성에 제목이 들어가 있으면 본문으로 보인다
        assertNull(NotiRule.evaluate(rule(4, text = "T"), n, noSpam).skip)
        // 구성에서 제목을 뺀 직접 선택이면 제목 단어는 본문에 없다
        assertEquals(StepCode.NOT_MATCH_TEXT, NotiRule.evaluate(rule(4, composition = "text", text = "T"), n, noSpam).skip)
        // 모두 전달하기
        assertNull(NotiRule.evaluate(rule(4, matchAll = true, text = "없는말"), n, noSpam).skip)
    }

    @Test fun v4는_빈_본문이면_빈_알림_비트와_무관하게_2() {
        val n = noti(title = "", text = "")
        assertEquals(StepCode.EMPTY_NOTI, NotiRule.evaluate(rule(4), n, noSpam).skip)
        val noBit = rule(4)
        assertFalse(noBit.skipEmptyNoti)
        assertEquals(StepCode.EMPTY_NOTI, NotiRule.evaluate(noBit, n, noSpam).skip)
        // 직접 선택에서 고른 항목이 모두 비어도 2
        assertEquals(StepCode.EMPTY_NOTI, NotiRule.evaluate(rule(4, composition = "sub,big"), noti(title = "T", text = "X"), noSpam).skip)
    }

    @Test fun v3는_빈_본문일_때_비트가_켜진_경우만_2_그대로() {
        val n = noti(title = "T")
        val off = rule(3)
        assertNull(NotiRule.evaluate(off, n, noSpam).skip)
        val on = rule(3, skipEmpty = true)
        assertEquals(StepCode.EMPTY_NOTI, NotiRule.evaluate(on, n, noSpam).skip)
    }

    @Test fun v3는_기존_비트_구성_그대로_자동_승격하지_않는다() {
        val n = noti(title = "T", text = "본문", sub = "서브", big = "긴", msg = "메시지", old = "옛 본문")
        // 비트가 없으면 oldText
        assertEquals("옛 본문", NotiRule.evaluate(rule(3), n, noSpam).body)
        // composition 값이 있어도 v3 에서는 쓰지 않는다
        assertEquals("옛 본문", NotiRule.evaluate(rule(3, composition = "title"), n, noSpam).body)
        assertEquals("본문\n서브", NotiRule.evaluate(rule(3, useText = true, useSub = true), n, noSpam).body)
    }

    @Test fun v3는_제목_조건과_본문_조건_코드_3_11_5() {
        val n = noti(title = "카톡", text = "본문", old = "본문")
        val r = { from: String, text: String -> NotiRule.evaluate(rule(3, from = from, text = text), n, noSpam).skip }
        assertNull(r("카톡", "본문"))
        assertEquals(StepCode.NOT_MATCH_NOTI_SENDER, r("라인", "본문"))
        assertEquals(StepCode.NOT_MATCH_TEXT, r("카톡", "없음"))
        assertEquals(StepCode.NOT_MATCH_BOTH, r("라인", "없음"))
    }

    @Test fun OTP는_구성_본문으로_검사하고_허용_비트로_푼다() {
        val n = noti(title = "은행", text = "인증번호 OTP 123456")
        assertEquals(StepCode.OTP_BLOCKED, NotiRule.evaluate(rule(4), n, noSpam).skip)
        val allowed = rule(4, allowOtp = true)
        assertNull(NotiRule.evaluate(allowed, n, noSpam).skip)
        // OTP(1)가 빈 본문(2)보다 먼저지만, 본문이 비면 OTP 검사는 하지 않는다
        assertEquals(StepCode.EMPTY_NOTI, NotiRule.evaluate(rule(4), noti(), noSpam).skip)
    }

    @Test fun 스팸은_OTP와_빈본문_다음_조건_앞() {
        val n = noti(title = "광고봇", text = "싸게 팝니다")
        val spam: (String, String) -> String? = { t, b -> if (b.contains("싸게")) "싸게" else null }
        val g = NotiRule.evaluate(rule(4, text = "없는말"), n, spam)
        assertEquals(StepCode.SPAM_BLOCKED_PREFIX + "싸게", g.skip)
    }

    // --- 바꾸기·템플릿 순서

    private val replace: (String) -> String = { it.replace("A", "B") }
    private val template: (String) -> String = { "[A]$it" }

    @Test fun v4는_바꾸기_후_템플릿() {
        // 바꾸기 먼저: 본문 A→B, 템플릿 머리말 [A] 는 그대로
        assertEquals("[A]xBx", NotiRule.render(rule(4), "xAx", replace, template))
    }

    @Test fun v2_v3_알림_규칙은_템플릿_후_바꾸기() {
        assertEquals("[B]xBx", NotiRule.render(rule(3), "xAx", replace, template))
        assertEquals("[B]xBx", NotiRule.render(rule(2), "xAx", replace, template))
    }

    @Test fun v0_v1_알림_규칙은_바꾸기_후_템플릿_그대로() {
        assertEquals("[A]xBx", NotiRule.render(rule(1), "xAx", replace, template))
        assertEquals("[A]xBx", NotiRule.render(rule(0), "xAx", replace, template))
    }

    @Test fun 마지막에_CRLF를_LF로() {
        assertEquals("a\nb", NotiRule.render(rule(4), "a\r\nb", { it }, { it }))
    }
}
