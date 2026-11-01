package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionMatchTest {
    private val cmp = { a: String, b: String -> a.filter { it.isDigit() }.takeLast(8) == b.filter { it.isDigit() }.takeLast(8) }

    /** 시험용 도우미: 묶음마다 항목 목록, "!" 로 시작하는 항목은 제외 항목. */
    private fun rule(vararg groups: List<String>): String =
        Conditions.encode(groups.map { g -> g.map { if (it.startsWith("!")) Term(it.substring(1), exclude = true) else Term(it) } })

    @Test fun encodeDecodeRoundTrip() {
        val s = rule(listOf("a", "!b"), listOf("c"))
        assertEquals(listOf(listOf(Term("a"), Term("b", exclude = true)), listOf(Term("c"))), Conditions.decode(s))
        assertEquals(s, Conditions.encode(Conditions.decode(s)))
        assertEquals(emptyList<List<Term>>(), Conditions.decode(""))
        assertEquals(emptyList<List<Term>>(), Conditions.decode("[]"))
        assertEquals(emptyList<List<Term>>(), Conditions.decode("깨진 값"))
    }

    @Test fun textContainsAndEmptyRulePasses() {
        assertTrue(ConditionMatch.matchText("", "아무거나", false, false))
        assertTrue(ConditionMatch.matchText(rule(listOf("결제")), "카드 결제 완료", false, false))
        assertFalse(ConditionMatch.matchText(rule(listOf("입금")), "카드 결제 완료", false, false))
    }

    @Test fun textAndWithinGroupOrAcrossGroups() {
        val r = rule(listOf("카드", "승인"), listOf("입금"))
        assertTrue(ConditionMatch.matchText(r, "카드 승인 1000원", false, false))
        assertFalse(ConditionMatch.matchText(r, "카드 취소", false, false))
        assertTrue(ConditionMatch.matchText(r, "급여 입금", false, false))
    }

    @Test fun textExclusion() {
        val r = rule(listOf("결제", "!광고"))
        assertTrue(ConditionMatch.matchText(r, "결제 완료", false, false))
        assertFalse(ConditionMatch.matchText(r, "(광고) 결제 안내", false, false))
    }

    @Test fun textIgnoreCase() {
        assertFalse(ConditionMatch.matchText(rule(listOf("OTP")), "otp 1234", false, false))
        assertTrue(ConditionMatch.matchText(rule(listOf("OTP")), "otp 1234", true, false))
    }

    @Test fun textWildcardIsFullMatch() {
        val r = rule(listOf("[Web발신]*승인"))
        // 와일드카드는 전체 일치 정규식. '[' ']' 는 \Q..\E 로 감싸 문자 그대로 비교
        assertTrue(ConditionMatch.matchText(r, "[Web발신]카드 승인", false, true))
        assertFalse(ConditionMatch.matchText(r, "[Web발신]카드 승인 완료", false, true))
        // 와일드카드를 끄면 '*' 는 그냥 문자 → contains 로 비교
        assertFalse(ConditionMatch.matchText(r, "[Web발신]카드 승인", false, false))
        // 줄바꿈도 .* 에 걸린다((?s))
        assertTrue(ConditionMatch.matchText(rule(listOf("a*b")), "a\nx\nb", false, true))
    }

    @Test fun senderPhoneCompareAndDashNormalize() {
        val r = rule(listOf("010-1234-5678"))
        assertTrue(ConditionMatch.matchSender(r, "+821012345678", false, false, 3, cmp))
        assertFalse(ConditionMatch.matchSender(r, "01099998888", false, false, 3, cmp))
        assertTrue(ConditionMatch.matchSender("", "아무", false, false, 3, cmp))
    }

    @Test fun senderTextualAddressUsesEquals() {
        val r = rule(listOf("KB국민"))
        assertTrue(ConditionMatch.matchSender(r, "KB국민", false, false, 3, cmp))
        assertFalse(ConditionMatch.matchSender(r, "KB국민카드", false, false, 3, cmp))
    }

    @Test fun senderExcludeWinsAndIncludesAreOr() {
        val r = rule(listOf("15881234", "16001234", "!16001234"))
        // 제외 토큰에 걸리면 false
        assertFalse(ConditionMatch.matchSender(r, "16001234", false, false, 3, cmp))
        assertTrue(ConditionMatch.matchSender(r, "15881234", false, false, 3, cmp))
        // 포함 목록이 있는데 아무것과도 안 맞으면 false
        assertFalse(ConditionMatch.matchSender(r, "17771234", false, false, 3, cmp))
        // 제외 목록만 있으면 나머지는 통과
        val only = rule(listOf("!15881234"))
        assertTrue(ConditionMatch.matchSender(only, "17771234", false, false, 3, cmp))
    }

    @Test fun senderWildcard() {
        val r = rule(listOf("1588*"))
        assertTrue(ConditionMatch.matchSender(r, "15881234", false, true, 3, cmp))
        assertFalse(ConditionMatch.matchSender(r, "16001588", false, true, 3, cmp))
    }

    @Test fun senderVersion0ExactList() {
        val r = rule(listOf("A사"))
        assertTrue(ConditionMatch.matchSender(r, "A사", false, false, 0, cmp))
        assertFalse(ConditionMatch.matchSender(r, "B사", false, false, 0, cmp))
    }

    @Test fun wildcardRegexConstruction() {
        assertEquals("(?s)a.*b", ConditionMatch.wildcardRegex("a*b"))
        assertEquals("(?s)\\Q(\\E.*\\Q)\\E", ConditionMatch.wildcardRegex("(*)"))
        assertEquals("abc", ConditionMatch.wildcardRegex("abc"))
    }

    @Test fun destinationsFromList() {
        assertEquals(listOf("01011112222", "01033334444"), parseDestinations(Conditions.encodeList(listOf("01011112222", "", "01033334444"))))
        assertEquals(emptyList<String>(), parseDestinations(""))
    }
}
