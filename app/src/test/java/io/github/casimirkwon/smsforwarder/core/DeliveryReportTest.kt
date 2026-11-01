package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.core.DeliveryReport.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryReportTest {
    private fun tally(total: Int, vararg reports: Pair<Int, Part>): DeliveryTally =
        reports.fold(DeliveryTally()) { t, (i, p) -> t.with(p, i, total) }

    // --- 보고 한 건 해석 ---

    @Test fun 상태_값으로_도착_시도중_실패를_가른다() {
        assertEquals(Part.DELIVERED, DeliveryReport.classify(-1, 0))
        assertEquals(Part.DELIVERED, DeliveryReport.classify(-1, 1))      // 전달했지만 확인 못 함도 도착으로 본다
        assertEquals(Part.PENDING, DeliveryReport.classify(-1, 32))
        assertEquals(Part.PENDING, DeliveryReport.classify(-1, 63))
        assertEquals(Part.FAILED, DeliveryReport.classify(-1, 64))
        assertEquals(Part.FAILED, DeliveryReport.classify(-1, 99))
    }

    @Test fun 결과_코드가_정상이_아니면_실패이고_상태를_읽지_못하면_무시한다() {
        assertEquals(Part.FAILED, DeliveryReport.classify(0, 0))
        assertEquals(Part.FAILED, DeliveryReport.classify(1, null))
        assertEquals(Part.IGNORED, DeliveryReport.classify(-1, null))
        assertEquals(Part.IGNORED, DeliveryReport.classify(-1, -1))
    }

    @Test fun 보고_인텐트_키_왕복과_잘못된_값() {
        assertEquals(DeliveryReport.Key(42, 1, 3), DeliveryReport.parseKey(DeliveryReport.key(42, 1, 3)))
        assertNull(DeliveryReport.parseKey(null))
        assertNull(DeliveryReport.parseKey("42"))
        assertNull(DeliveryReport.parseKey("42/3/3"))     // 부분 번호가 범위 밖
        assertNull(DeliveryReport.parseKey("42/0/0"))
        assertNull(DeliveryReport.parseKey("x/0/1"))
    }

    // --- 부분 문자 합치기 ---

    @Test fun 한_통짜리는_도착_보고가_오면_도착() {
        assertEquals(DeliveryState.NONE, DeliveryTally().state)
        assertEquals(DeliveryState.DELIVERED, tally(1, 0 to Part.DELIVERED).state)
        assertEquals(DeliveryState.FAILED, tally(1, 0 to Part.FAILED).state)
    }

    @Test fun 여러_부분은_모두_도착해야_도착() {
        val t = tally(3, 0 to Part.DELIVERED, 2 to Part.DELIVERED)
        assertEquals(DeliveryState.PARTIAL, t.state)
        assertEquals(2, t.ok.size)
        assertEquals(DeliveryState.DELIVERED, t.with(Part.DELIVERED, 1, 3).state)
    }

    @Test fun 하나라도_실패_보고면_도착_실패() {
        assertEquals(DeliveryState.FAILED, tally(3, 0 to Part.DELIVERED, 1 to Part.FAILED).state)
        // 나머지가 모두 도착해도 실패 보고가 남아 있으면 실패
        assertEquals(DeliveryState.FAILED, tally(3, 1 to Part.FAILED, 0 to Part.DELIVERED, 2 to Part.DELIVERED).state)
    }

    @Test fun 시도중_보고와_무시는_상태를_바꾸지_않고_같은_부분_보고가_겹쳐도_한_번으로_센다() {
        val one = tally(2, 0 to Part.DELIVERED)
        assertEquals(one, one.with(Part.PENDING, 1, 2))
        assertEquals(one, one.with(Part.IGNORED, 1, 2))
        assertEquals(one, one.with(Part.DELIVERED, 0, 2))
        assertEquals(DeliveryState.PARTIAL, tally(2, 0 to Part.DELIVERED, 0 to Part.DELIVERED).state)
    }

    @Test fun 나중_보고가_같은_부분의_앞선_보고를_대신한다() {
        val t = tally(2, 0 to Part.FAILED, 0 to Part.DELIVERED, 1 to Part.DELIVERED)
        assertEquals(DeliveryState.DELIVERED, t.state)
    }

    @Test fun 전체_부분_수가_다른_옛_보고는_처음부터_다시_센다() {
        val t = tally(3, 0 to Part.DELIVERED, 1 to Part.DELIVERED).with(Part.FAILED, 0, 2)
        assertEquals(DeliveryTally(2, emptySet(), setOf(0)), t)
    }

    @Test fun 저장_형식_왕복과_깨진_값() {
        val t = tally(4, 3 to Part.DELIVERED, 0 to Part.DELIVERED, 2 to Part.FAILED)
        assertEquals("4;0,3;2", t.encode())
        assertEquals(t, DeliveryTally.decode(t.encode()))
        assertEquals("", DeliveryTally().encode())
        for (bad in listOf(null, "", "x", "3;0", "0;;", "a;b;c")) assertEquals(DeliveryTally(), DeliveryTally.decode(bad))
        // 범위 밖 부분 번호는 버린다
        assertEquals(DeliveryTally(2, setOf(0), emptySet()), DeliveryTally.decode("2;0,5;"))
    }

    // --- 발송 접수 판정은 도착 보고와 무관 ---

    @Test fun 발송_접수_판정은_도착_보고가_없거나_실패여도_그대로() {
        val known = setOf(1, 2, 3, 4)
        // 접수 성공(-1) → 성공, 실패(0) → 실패, 재시도 후보 → 재시도 대기: 도착 상태를 입력으로 받지 않는다
        assertEquals(SendResult.SUCCESS, SentResult.normalize(-1, known, retryOnFailure = true, canRetry = true))
        assertEquals(SendResult.FAIL, SentResult.normalize(0, known, retryOnFailure = true, canRetry = true))
        assertEquals(SendResult.RETRY_WAIT, SentResult.normalize(1, known, retryOnFailure = true, canRetry = true))
        assertEquals(SendResult.PERMANENT_FAIL, SentResult.normalize(1, known, retryOnFailure = true, canRetry = false))
        // 도착 실패로 기록된 건이라도 접수 성공 판정은 달라지지 않는다
        val failedDelivery = tally(1, 0 to Part.FAILED)
        assertEquals(DeliveryState.FAILED, failedDelivery.state)
        assertEquals(SendResult.SUCCESS, SentResult.normalize(-1, known, retryOnFailure = true, canRetry = true))
    }
}
