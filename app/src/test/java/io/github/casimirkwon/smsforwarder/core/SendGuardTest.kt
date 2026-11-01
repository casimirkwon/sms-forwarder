package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SendGuardTest {
    private val phone = { a: String, b: String -> a.replace("-", "") == b.replace("-", "") }
    private fun rec(content: String, inN: String = "01011112222", out: String = "01099998888", send: Long = 0) =
        SentRecord(content, inN, out, 0, send)

    @Test fun 유사도는_긴쪽_길이로_나눈_소문자_편집거리() {
        assertEquals(1.0, SendGuard.similarity("", ""), 1e-9)
        assertEquals(1.0, SendGuard.similarity("Hello", "hello", Locale.ENGLISH), 1e-9)
        assertEquals(0.8, SendGuard.similarity("hello", "hallo", Locale.ENGLISH), 1e-9) // 거리 1 / 길이 5
        assertEquals(0.0, SendGuard.similarity("", "abc"), 1e-9)
        assertEquals(0.5, SendGuard.similarity("abcd", "ab"), 1e-9)
        // 인자 순서와 무관
        assertEquals(SendGuard.similarity("kitten", "sitting"), SendGuard.similarity("sitting", "kitten"), 1e-9)
        assertEquals(1.0 - 3.0 / 7.0, SendGuard.similarity("kitten", "sitting"), 1e-9)
    }

    @Test fun 전화번호_판정() {
        assertTrue(SendGuard.isPhone("010-1234-5678"))
        assertTrue(SendGuard.isPhone("+821012345678"))
        assertTrue(SendGuard.isPhone("#123"))
        assertTrue(SendGuard.isPhone("*123"))
        assertFalse(SendGuard.isPhone("#abc"))
        assertFalse(SendGuard.isPhone("abc"))
        assertFalse(SendGuard.isPhone(null))
    }

    @Test fun 같은_수신처_같은_본문_발신자가_다르면_유사도로_센다() {
        val hist = listOf(rec("인증 안내 1234"), rec("인증 안내 1235"), rec("전혀 다른 긴 문장입니다"))
        val c = SendGuard.count(hist, rec("인증 안내 1234"), phone)
        // 첫째는 포함, 둘째는 포함 아님(1235 ≠ 1234) → 포함 관계가 아니라 제외, 셋째도 제외
        assertEquals(1, c.similar)
        assertEquals(3, c.sameDest) // 수신처 같은 기록 전부
    }

    @Test fun 포함_관계이면서_유사도_094_미만이면_세지_않는다() {
        val hist = listOf(rec("안녕"))
        val c = SendGuard.count(hist, rec("안녕하세요 오늘 회의는 3시입니다"), phone)
        assertEquals(0, c.similar)
        assertEquals(1, c.sameDest)
    }

    @Test fun 포함_관계이고_유사도_094_이상이면_센다() {
        val longText = "가".repeat(50)
        val c = SendGuard.count(listOf(rec(longText)), rec(longText + "나"), phone) // 50/51 ≈ 0.98
        assertEquals(1, c.similar)
    }

    @Test fun 발신자가_수신처와_같으면_본문_유사도와_상관없이_센다() {
        val hist = listOf(rec("안녕"))
        val c = SendGuard.count(hist, rec("안녕하세요 오늘 회의는 3시입니다", inN = "01099998888", out = "01099998888"), phone)
        assertEquals(1, c.similar)
        // 전화번호 꼴이고 compare 가 같다고 하면 마찬가지
        val c2 = SendGuard.count(hist, rec("안녕하세요 오늘 회의는 3시입니다", inN = "010-9999-8888", out = "01099998888"), phone)
        assertEquals(1, c2.similar)
    }

    @Test fun 수신처가_다르면_세지_않는다() {
        val c = SendGuard.count(listOf(rec("같은 본문", out = "01000000000")), rec("같은 본문"), phone)
        assertEquals(0, c.similar)
        assertEquals(0, c.sameDest)
    }

    @Test fun 본문_포함은_대소문자를_구분한다() {
        // contains 가 대소문자 구분이라 "ABC" 와 "abc" 는 포함 관계가 아니다
        val c = SendGuard.count(listOf(rec("ABC")), rec("abc"), phone)
        assertEquals(0, c.similar)
    }

    @Test fun 빈_본문_기록은_모든_본문과_포함_관계다() {
        // 재전송 큐에서 다시 읽은 건은 기록 본문이 "" 라 포함 판정은 통과하지만 유사도 0 이라 세지 않는다
        val c = SendGuard.count(listOf(rec("")), rec("안녕하세요"), phone)
        assertEquals(0, c.similar)
        assertEquals(1, c.sameDest)
    }

    @Test fun 중복_판정은_기준_횟수_이상이면_차단() {
        fun d(sim: Int, always: Boolean = false, n: Int = 5, avoid: Boolean = true) =
            SendGuard.decide(avoid, true, always, n, 15, SendGuard.Counts(sim, 0))
        assertEquals(SendGuard.Verdict.OK, d(4))
        assertEquals(SendGuard.Verdict.DUPLICATE, d(5))
        assertEquals(SendGuard.Verdict.DUPLICATE, d(6))
        assertEquals(SendGuard.Verdict.OK, d(0, always = true))
        assertEquals(SendGuard.Verdict.DUPLICATE, d(1, always = true))
        assertEquals(SendGuard.Verdict.OK, d(10, avoid = false))
    }

    @Test fun 반복_판정은_기준을_넘을_때만_차단한다() {
        fun d(same: Int, prevent: Boolean = true, max: Int = 15) =
            SendGuard.decide(true, prevent, false, 5, max, SendGuard.Counts(0, same))
        assertEquals(SendGuard.Verdict.OK, d(15))
        assertEquals(SendGuard.Verdict.REPEATED, d(16))
        assertEquals(SendGuard.Verdict.OK, d(100, prevent = false))
        assertEquals(SendGuard.Verdict.REPEATED, d(4, max = 3))
    }

    @Test fun 중복이_반복보다_먼저_판정된다() {
        assertEquals(SendGuard.Verdict.DUPLICATE, SendGuard.decide(true, true, false, 5, 15, SendGuard.Counts(5, 20)))
    }

    @Test fun 차단_목록_일치는_전화번호_비교_또는_문자열_일치() {
        assertTrue(SendGuard.blockMatches("010-9999-8888", "01099998888", phone))
        assertTrue(SendGuard.blockMatches("abc", "abc", phone))
        assertFalse(SendGuard.blockMatches("abc", "abd", phone))
        assertFalse(SendGuard.blockMatches("01011112222", "01099998888", phone))
    }

    @Test fun 기준값_상수() {
        assertEquals(60_000L, SendGuard.WINDOW_MS)
        assertEquals(3_600_000L, SendGuard.BLOCK_MS)
        assertEquals(0.94, SendGuard.SIMILARITY, 0.0)
    }
}

class GlobalGateTest {
    @Test fun 꺼져_있으면_언제나_통과() {
        assertTrue(GlobalGate.timeAllowed(false, 900, 1800, 2000))
        assertTrue(GlobalGate.timeAllowed(false, -1, -1, 0))
    }

    @Test fun 시작이_끝보다_이르면_양끝_포함_구간() {
        assertTrue(GlobalGate.timeAllowed(true, 900, 1800, 900))
        assertTrue(GlobalGate.timeAllowed(true, 900, 1800, 1800))
        assertTrue(GlobalGate.timeAllowed(true, 900, 1800, 1200))
        assertFalse(GlobalGate.timeAllowed(true, 900, 1800, 859))
        assertFalse(GlobalGate.timeAllowed(true, 900, 1800, 1801))
    }

    @Test fun 시작이_끝보다_늦거나_같으면_자정을_넘는_구간() {
        assertTrue(GlobalGate.timeAllowed(true, 2200, 600, 2300))
        assertTrue(GlobalGate.timeAllowed(true, 2200, 600, 2200))
        assertTrue(GlobalGate.timeAllowed(true, 2200, 600, 0))
        assertTrue(GlobalGate.timeAllowed(true, 2200, 600, 600))
        assertFalse(GlobalGate.timeAllowed(true, 2200, 600, 1200))
        assertFalse(GlobalGate.timeAllowed(true, 2200, 600, 601))
        // 시작 == 끝 도 자정 넘김 분기: 시작 이후 또는 끝 이전만 통과
        assertTrue(GlobalGate.timeAllowed(true, 900, 900, 2359))
        assertTrue(GlobalGate.timeAllowed(true, 900, 900, 100))
        // 시작 == 끝 이면 (시작..2400) ∪ (0..끝) 이 하루 전체를 덮는다
        assertTrue(GlobalGate.timeAllowed(true, 900, 900, 899))
        assertTrue(GlobalGate.timeAllowed(true, 900, 900, 1000))
    }

    @Test fun 시작_끝_기본값_마이너스1_이면_켜도_항상_통과() {
        assertTrue(GlobalGate.timeAllowed(true, -1, -1, 0))
        assertTrue(GlobalGate.timeAllowed(true, -1, -1, 1234))
        assertTrue(GlobalGate.timeAllowed(true, -1, -1, 2359))
    }

    @Test fun 로밍은_설정이_꺼졌을_때만_로밍_여부를_본다() {
        var asked = 0
        assertTrue(GlobalGate.roamingAllowed(true) { asked++; true })
        assertEquals(0, asked)
        assertFalse(GlobalGate.roamingAllowed(false) { true })
        assertTrue(GlobalGate.roamingAllowed(false) { false })
    }

    @Test fun HHMM_변환() {
        assertEquals(930, GlobalGate.hhmm(9, 30))
        assertEquals(0, GlobalGate.hhmm(0, 0))
        assertEquals(2359, GlobalGate.hhmm(23, 59))
    }
}

class SmsLimitTest {
    @Test fun 달이_다르면_초기화() {
        assertTrue(SmsLimit.needsReset(10, -1))
        assertTrue(SmsLimit.needsReset(0, 11))
        assertFalse(SmsLimit.needsReset(10, 10))
    }

    @Test fun 켜져_있고_한도에_닿으면_막는다() {
        assertFalse(SmsLimit.reached(false, 500, 100))
        assertFalse(SmsLimit.reached(true, 99, 100))
        assertTrue(SmsLimit.reached(true, 100, 100))
        assertTrue(SmsLimit.reached(true, 101, 100))
    }
}
