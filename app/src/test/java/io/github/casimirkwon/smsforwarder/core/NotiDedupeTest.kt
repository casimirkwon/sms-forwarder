package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 중복 알림 거르기. */
class NotiDedupeTest {
    private val min = 60_000L
    private val cfg = DedupeConfig(ignoreDuplicates = true, strict = false, summaryOnlyText = true, intervalMs = 60 * min)

    private fun item(time: Long, text: String = "t", big: String = "b", msg: String = "m", key: String = "k", id: Long = 0, rcs: Boolean = false) =
        NotiHistoryItem(id, time, text, big, msg, rcs, key)

    @Test fun 같은_알림이_60분_안에_다시_오면_중복() {
        val list = mutableListOf(item(0, id = 1))
        val r = NotiDedupe.check(list, item(30 * min), isSummary = false, cfg)
        assertTrue(r.duplicate)
        assertTrue(r.toDelete.isEmpty())
        assertEquals(1, list.size)
    }

    @Test fun 내용이_다르면_중복이_아니다() {
        val list = mutableListOf(item(0, id = 1))
        assertFalse(NotiDedupe.check(list, item(1 * min, text = "다른 본문"), false, cfg).duplicate)
        assertFalse(NotiDedupe.check(list, item(1 * min, big = "다른 긴 본문"), false, cfg).duplicate)
        assertFalse(NotiDedupe.check(list, item(1 * min, msg = "다른 메시지"), false, cfg).duplicate)
    }

    @Test fun 간격_창은_첫_알림_시각_기준이고_정확히_60분이면_중복이_아니며_오래된_이력은_지운다() {
        val list = mutableListOf(item(0, id = 1))
        val r = NotiDedupe.check(list, item(60 * min), false, cfg)
        assertFalse(r.duplicate)
        assertEquals(listOf(1L), r.toDelete.map { it.id })
        assertTrue(list.isEmpty())
    }

    @Test fun 중복으로_판정된_알림은_이력에_들어가지_않으므로_창이_밀리지_않는다() {
        val list = mutableListOf(item(0, id = 1))
        // 59분에 중복(이력은 그대로), 61분에는 첫 알림(0분)이 이미 60분을 넘어 중복이 아니다
        assertTrue(NotiDedupe.check(list, item(59 * min), false, cfg).duplicate)
        assertFalse(NotiDedupe.check(list, item(61 * min), false, cfg).duplicate)
    }

    @Test fun 엄격히_확인하면_알림_key_도_같아야_중복() {
        val strict = cfg.copy(strict = true)
        val list = mutableListOf(item(0, key = "k1", id = 1))
        assertTrue(NotiDedupe.check(list, item(1 * min, key = "k1"), false, strict).duplicate)
        assertFalse(NotiDedupe.check(list, item(1 * min, key = "k2"), false, strict).duplicate)
        // 엄격하지 않으면 key 가 달라도 중복
        assertTrue(NotiDedupe.check(list, item(1 * min, key = "k2"), false, cfg).duplicate)
    }

    @Test fun 요약_알림은_본문만_비교한다() {
        val list = mutableListOf(item(0, text = "same", big = "x", msg = "y", id = 1))
        val n = item(1 * min, text = "same", big = "다름", msg = "다름")
        assertTrue(NotiDedupe.check(list, n, isSummary = true, cfg).duplicate)
        assertFalse(NotiDedupe.check(list, n, isSummary = false, cfg).duplicate)
        // 옵션을 끄면 요약 알림도 전체를 비교한다
        assertFalse(NotiDedupe.check(list, n, isSummary = true, cfg.copy(summaryOnlyText = false)).duplicate)
    }

    @Test fun 중복_무시를_꺼도_오래된_이력은_정리하고_중복이라고_하지_않는다() {
        val off = cfg.copy(ignoreDuplicates = false)
        val list = mutableListOf(item(0, id = 1), item(100 * min, id = 2))
        val r = NotiDedupe.check(list, item(110 * min), false, off)
        assertFalse(r.duplicate)
        assertEquals(listOf(1L), r.toDelete.map { it.id })
        assertEquals(listOf(2L), list.map { it.id })
    }

    @Test fun RCS_이력은_중복_비교에서_빠지고_48시간_뒤에_정리된다() {
        val list = mutableListOf(item(0, id = 1, rcs = true))
        assertFalse(NotiDedupe.check(list, item(1 * min), false, cfg).duplicate)
        assertEquals(1, list.size)
        val r = NotiDedupe.check(list, item(48L * 60 * min), false, cfg)
        assertEquals(listOf(1L), r.toDelete.map { it.id })
    }

    @Test fun 간격을_바꾸면_그_간격을_따른다() {
        val ten = cfg.copy(intervalMs = 10 * min)
        val list = mutableListOf(item(0, id = 1))
        assertTrue(NotiDedupe.check(list, item(9 * min), false, ten).duplicate)
        assertFalse(NotiDedupe.check(list, item(10 * min), false, ten).duplicate)
    }
}
