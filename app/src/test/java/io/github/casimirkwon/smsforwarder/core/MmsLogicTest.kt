package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsLogicTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cstr(s: String) = s.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)

    // --- WapPushParser ---
    @Test fun parsesContentLocation() {
        // From 헤더의 value-length 는 실제 길이에 맞춰야 건너뛸 수 있다
        val from = b(0x80) + cstr("+821012345678")
        var d = b(0x8C, 0x82) + b(0x98) + cstr("T123") + b(0x8D, 0x90) + b(0x89, from.size) + from
        d += b(0x8A, 0x80) + b(0x8E, 0x02, 0x1F, 0x40) + b(0x88, 0x03, 0x81, 0x01, 0x0E)
        d += b(0x83) + cstr("http://mms.example.com/msg?id=42")
        assertEquals("http://mms.example.com/msg?id=42", WapPushParser.contentLocation(d))
    }

    @Test fun locationBeforeOtherHeadersAndQuoteChar() {
        val d = b(0x8C, 0x82, 0x83, 0x7F) + cstr("http://x/y") + b(0x98) + cstr("T1")
        assertEquals("http://x/y", WapPushParser.contentLocation(d))
    }

    @Test fun nullWhenNotNotificationOrNoLocation() {
        assertNull(WapPushParser.contentLocation(b(0x8C, 0x84, 0x83) + cstr("http://x")))   // m-retrieve-conf
        assertNull(WapPushParser.contentLocation(b(0x8C, 0x82, 0x98) + cstr("T1")))          // content-location 없음
        assertNull(WapPushParser.contentLocation(null))
        assertNull(WapPushParser.contentLocation(ByteArray(0)))
        assertNull(WapPushParser.contentLocation(b(0x8C, 0x82, 0x83, 0x68, 0x74)))           // 널 종료 없음
    }

    @Test fun skipsUnknownHeaderWithUintvarLength() {
        // 0x31(알 수 없음) 대신 0x95(가짜 헤더) + 값 길이 31 → uintvar 3 → 3바이트
        val d = b(0x8C, 0x82, 0x95, 31, 3, 1, 2, 3, 0x83) + cstr("http://z")
        assertEquals("http://z", WapPushParser.contentLocation(d))
    }

    // --- MmsInbox: 재시도 일정 ---
    @Test fun nextDelayIs6sWithin30sAnd15sAfter() {
        assertEquals(6_000L, MmsInbox.nextDelayMs(1, 3_000))
        assertEquals(6_000L, MmsInbox.nextDelayMs(2, 25_000))
        assertEquals(6_000L, MmsInbox.nextDelayMs(2, 29_999))
        assertEquals(15_000L, MmsInbox.nextDelayMs(2, 30_000))
        assertEquals(15_000L, MmsInbox.nextDelayMs(4, 35_000))
    }

    @Test fun finalAttemptNeedsThreeAttemptsAndOver120s() {
        // 경과 + 간격 > 120초 이고 시도 3회 이상이어야 마지막
        assertEquals(15_000L, MmsInbox.nextDelayMs(5, 105_000))   // 105 + 15 = 120 → 아직 (초과 아님)
        assertNull(MmsInbox.nextDelayMs(5, 106_000))              // 121 > 120
        assertNull(MmsInbox.nextDelayMs(3, 110_000))
        assertNull(MmsInbox.nextDelayMs(6, 125_000))
        // 시도가 3 미만이면 120초를 넘어도 계속(첫 시도가 아주 늦게 도는 경우)
        assertEquals(15_000L, MmsInbox.nextDelayMs(1, 125_000))
        assertEquals(15_000L, MmsInbox.nextDelayMs(2, 200_000))
        assertNull(MmsInbox.nextDelayMs(3, 200_000))
    }

    @Test fun scheduleTableFromThe3sFirstAttempt() {
        // 첫 시도 3초 → 9 → 15 → 21 → 27 → 33(15초 간격 시작) → 48 → … → 마지막은 시도 11(108초)
        var elapsed = 3_000L
        var attempt = 1
        val times = ArrayList<Long>()
        while (true) {
            times.add(elapsed)
            val next = MmsInbox.nextDelayMs(attempt, elapsed) ?: break
            elapsed += next
            attempt++
        }
        assertEquals(listOf(3L, 9L, 15L, 21L, 27L, 33L, 48L, 63L, 78L, 93L, 108L), times.map { it / 1000 })
    }

    @Test fun elapsedClampsNegativeToZero() {
        assertEquals(0L, MmsInbox.elapsedMs(1_000, 5_000))
        assertEquals(4_000L, MmsInbox.elapsedMs(9_000, 5_000))
    }

    @Test fun urlOnlyWithin30sOfReceive() {
        assertEquals("u", MmsInbox.urlForElapsed(0, "u"))
        assertEquals("u", MmsInbox.urlForElapsed(29_999, "u"))
        assertNull(MmsInbox.urlForElapsed(30_000, "u"))
        assertNull(MmsInbox.urlForElapsed(1_000, null))
    }

    // --- MmsInbox: 준비 점검 ---
    @Test fun readinessFromPartsAndSender() {
        assertEquals(MmsReadiness.NO_PARTS, MmsInbox.readiness(0, "01012345678"))
        assertEquals(MmsReadiness.NO_PARTS, MmsInbox.readiness(0, null))            // 파트 먼저 본다
        assertEquals(MmsReadiness.NO_SENDER, MmsInbox.readiness(2, null))
        assertEquals(MmsReadiness.NO_SENDER, MmsInbox.readiness(2, ""))
        assertEquals(MmsReadiness.NO_SENDER, MmsInbox.readiness(2, "   "))
        assertEquals(MmsReadiness.NO_SENDER, MmsInbox.readiness(1, "insert-address-token"))
        assertEquals(MmsReadiness.READY, MmsInbox.readiness(1, "+821012345678"))
        // 점검이 꺼져 있으면 항상 READY
        assertEquals(MmsReadiness.READY, MmsInbox.readiness(0, null, readyCheck = false))
    }

    @Test fun deliverOnlyReadyOrNoSenderOnFinalAttempt() {
        assertTrue(MmsInbox.canDeliver(MmsReadiness.READY, false))
        assertTrue(MmsInbox.canDeliver(MmsReadiness.READY, true))
        assertFalse(MmsInbox.canDeliver(MmsReadiness.NO_SENDER, false))
        assertTrue(MmsInbox.canDeliver(MmsReadiness.NO_SENDER, true))      // 마지막 시도는 보낸이 없이도 전달
        assertFalse(MmsInbox.canDeliver(MmsReadiness.NO_PARTS, false))
        assertFalse(MmsInbox.canDeliver(MmsReadiness.NO_PARTS, true))      // 파트가 없으면 마지막에도 전달 안 함
    }

    // --- MmsInbox: 기준 시각 ---
    @Test fun pivotIsMaxOfSavedAndInitMinus60() {
        val now = 2_000_000_000_000L
        val init = now - 10_000          // 10초 전 수신
        val initSec = init / 1000
        assertEquals(initSec - 60, MmsInbox.pivotSec(init, null, false, now))
        assertEquals(initSec - 10, MmsInbox.pivotSec(init, initSec - 10, false, now))
        assertEquals(initSec - 60, MmsInbox.pivotSec(init, initSec - 500, false, now))
    }

    @Test fun adjust3PullsPivotDownToInitMinus5WhenReadyCheck() {
        val now = 2_000_000_000_000L
        val init = now - 10_000
        val initSec = init / 1000
        // 저장값이 수신 시각-5초보다 크면 수신 시각-5초로 당긴다(준비 점검 켜짐)
        assertEquals(initSec - 5, MmsInbox.pivotSec(init, initSec + 3, false, now))
        // 점검이 꺼져 있으면 당기지 않는다(예전 동작)
        assertEquals(initSec + 3, MmsInbox.pivotSec(init, initSec + 3, false, now, readyCheck = false))
    }

    @Test fun pivotWithUrlIsZeroThenClampedTo15Minutes() {
        val now = 2_000_000_000_000L
        assertEquals(now / 1000 - 900, MmsInbox.pivotSec(now - 5_000, 123L, true, now))
    }

    @Test fun pivotNeverOlderThan15MinutesBeforeNow() {
        val now = 2_000_000_000_000L
        val oldInit = now - 3_600_000
        assertEquals(now / 1000 - 900, MmsInbox.pivotSec(oldInit, null, false, now))
    }

    // --- MmsInbox: 훑기·저장 시각 ---
    private fun rows(vararg d: Long) = d.mapIndexed { i, v -> MmsRow("${100 - i}", v, "1") }

    @Test fun scanHandlesRowsNewerThanPivotAndStopsAtOlder() {
        val handled = ArrayList<Long>()
        val sc = MmsInbox.scan(rows(300, 250, 200, 100), 200, emptySet(), false) { handled.add(it.date); true }
        assertEquals(listOf(300L, 250L), handled)            // 내림차순으로 훑는다
        assertTrue(sc.handled)
        assertFalse(sc.hasWaiting)
        assertTrue(sc.done)
        assertEquals(300L, sc.maxProcessed)
        assertEquals(300L, sc.saveTime)
    }

    @Test fun notReadyRowIsWaitingAndLimitsSavedTime() {
        // 300 은 준비됨, 250 은 아직 → 저장 시각 = min(300, 250-1) = 249, 끝나지 않음
        val sc = MmsInbox.scan(rows(300, 250), 100, emptySet(), false) { it.date == 300L }
        assertTrue(sc.handled)
        assertTrue(sc.hasWaiting)
        assertFalse(sc.done)
        assertEquals(250L, sc.waitingMin)
        assertEquals(300L, sc.maxProcessed)
        assertEquals(249L, sc.saveTime)
    }

    @Test fun waitingMinIsTheOldestNotReadyRow() {
        val sc = MmsInbox.scan(rows(300, 280, 260), 100, emptySet(), false) { false }
        assertFalse(sc.handled)
        assertEquals(260L, sc.waitingMin)
        assertEquals(-1L, sc.maxProcessed)
        assertEquals(-1L, sc.saveTime)           // 처리한 행이 없으면 min(-1, 259) = -1 → 저장 안 함
        assertFalse(sc.done)
    }

    @Test fun nothingReadyMeansNothingToSave() {
        val sc = MmsInbox.scan(rows(300), 100, emptySet(), false) { false }
        assertTrue(sc.saveTime <= 0)
    }

    @Test fun exceptionInOneRowDoesNotStopTheOthers() {
        val handled = ArrayList<Long>()
        val sc = MmsInbox.scan(rows(300, 250, 220), 100, emptySet(), false) {
            if (it.date == 250L) throw IllegalStateException("boom")
            handled.add(it.date); true
        }
        assertEquals(listOf(300L, 220L), handled)
        assertTrue(sc.handled)
        assertEquals(250L, sc.waitingMin)        // 예외 행은 아직 안 된 것으로 취급
        assertFalse(sc.done)
    }

    @Test fun alreadyProcessedRowsAreSkippedButCountTowardMaxDate() {
        val r = rows(300, 250)
        val seen = setOf(MmsInbox.key(r[0]))
        val handled = ArrayList<Long>()
        val sc = MmsInbox.scan(r, 100, seen, false) { handled.add(it.date); true }
        assertEquals(listOf(250L), handled)
        assertEquals(300L, sc.maxProcessed)
        // 다시 훑어 모두 이미 처리했다면(url 없음) handled 는 거짓 → 끝나지 않음
        val sc2 = MmsInbox.scan(r, 100, r.map { MmsInbox.key(it) }.toSet(), false) { error("호출되면 안 됨") }
        assertFalse(sc2.handled)
        assertFalse(sc2.done)
        assertEquals(300L, sc2.maxProcessed)
        // url 로 찾는 중이면 이미 처리한 행도 처리한 것으로 본다
        val sc3 = MmsInbox.scan(r, 100, r.map { MmsInbox.key(it) }.toSet(), true) { error("호출되면 안 됨") }
        assertTrue(sc3.handled)
        assertTrue(sc3.done)
    }

    @Test fun rowAtOrBelowPivotStopsScanButCountsTowardMaxDate() {
        val sc = MmsInbox.scan(rows(300, 200, 150), 250, emptySet(), false) { true }
        assertEquals(300L, sc.maxProcessed)      // 300 처리, 200 은 기준 이하 → 멈춤(최대 date 에는 반영)
        val none = MmsInbox.scan(rows(200, 150), 250, emptySet(), false) { error("호출되면 안 됨") }
        assertFalse(none.handled)
        assertEquals(200L, none.maxProcessed)
        assertEquals(200L, none.saveTime)
    }

    @Test fun emptyInboxScan() {
        val sc = MmsInbox.scan(emptyList(), 0, emptySet(), false) { true }
        assertFalse(sc.handled)
        assertEquals(-1L, sc.maxProcessed)
        assertEquals(-1L, sc.waitingMin)
        assertFalse(sc.done)
    }

    // --- 처리한 MMS 기록(HandledMms) ---

    @Test fun handledAddRejectsDuplicatesAndKeepsAtMost200() {
        val h = HandledMms.empty()
        for (i in 1..250) assertTrue(h.add("k$i", 1_000L + i))
        assertEquals(HandledMms.MAX, h.size)
        assertFalse(h.contains("k50"))              // 가장 먼저 넣은 것부터 빠진다
        assertTrue(h.contains("k51"))
        assertTrue(h.contains("k250"))
        assertFalse(h.add("k250", 9_999L))
        assertEquals(HandledMms.MAX, h.size)
    }

    @Test fun handledDropsEntriesOlderThanTtl() {
        val h = HandledMms.empty()
        h.add("old", 0L)
        h.add("recent", HandledMms.TTL_MS)
        h.add("new", HandledMms.TTL_MS + 1)         // old 는 TTL 을 1ms 넘겨서 빠진다
        assertFalse(h.contains("old"))
        assertTrue(h.contains("recent"))
        assertTrue(h.contains("new"))
        h.trim(HandledMms.TTL_MS * 3)
        assertEquals(0, h.size)
    }

    @Test fun handledEncodeDecodeRoundTripAndSkipsBrokenLines() {
        val h = HandledMms.empty()
        h.add("|12|1700000000|3", 111L)
        h.add("|13|1700000005|null", 222L)
        val back = HandledMms.decode(h.encode())
        assertEquals(h.keys().toList(), back.keys().toList())
        assertEquals(h.encode(), back.encode())
        val broken = HandledMms.decode("abc\tx\n\n333\n444\t\n555\t|1|2|3")
        assertEquals(listOf("|1|2|3"), broken.keys().toList())
        assertEquals(0, HandledMms.decode(null).size)
        assertEquals(0, HandledMms.decode("").size)
    }

    /** 재시작 시나리오: 같은 수신함을 두 번 훑는다. 두 번째는 "새 프로세스"라 기록을 문자열에서 되살린다. */
    private fun runScan(rows: List<MmsRow>, h: HandledMms, delivered: MutableList<String>, initMs: Long, nowMs: Long, savedLast: Long?): MmsInbox.Scan {
        val pivot = MmsInbox.pivotSec(initMs, savedLast, hasUrl = false, nowMs = nowMs)
        return MmsInbox.scan(rows, pivot, h.keys(), hasUrl = false) { r ->
            if (!h.add(MmsInbox.key(r), nowMs)) true else { delivered.add(r.id); true }
        }
    }

    @Test fun sameMmsIsDeliveredOnceAcrossRestartButNewMmsIsDelivered() {
        val now = 1_700_000_100_000L
        val first = MmsRow("7", 1_700_000_095L, "1")
        val delivered = ArrayList<String>()
        val h1 = HandledMms.empty()
        // 첫 프로세스: 전달했지만 "마지막 시각" 저장 전에 강제 종료됐다고 본다(savedLast 없음)
        runScan(listOf(first), h1, delivered, initMs = now - 5_000, nowMs = now, savedLast = null)
        assertEquals(listOf("7"), delivered)
        val persisted = h1.encode()

        // 재시작 직후 다른 MMS 의 알림이 와서 읽기 기준이 수신 시각-5초까지 내려간다 → 기록이 없으면 7 이 다시 뽑힌다
        val second = MmsRow("8", 1_700_000_103L, "1")
        val h2 = HandledMms.decode(persisted)
        runScan(listOf(second, first), h2, delivered, initMs = now + 2_000, nowMs = now + 3_000, savedLast = null)
        assertEquals(listOf("7", "8"), delivered)   // 7 은 한 번만, 새 MMS 8 은 전달

        // 기록이 없었다면(옛 동작) 7 이 또 전달됐을 것이다
        val noLog = ArrayList<String>()
        runScan(listOf(second, first), HandledMms.empty(), noLog, initMs = now + 2_000, nowMs = now + 3_000, savedLast = null)
        assertEquals(listOf("8", "7"), noLog)
    }

    @Test fun handledLogCapStillSkipsRecentOnesAfterManyMms() {
        val h = HandledMms.empty()
        for (i in 1..300) h.add("|$i|${1000 + i}|1", 1_000L)
        val back = HandledMms.decode(h.encode())
        assertEquals(HandledMms.MAX, back.size)
        assertTrue(back.contains("|300|1300|1"))
        assertFalse(back.contains("|1|1001|1"))
    }

    // --- MmsParts ---
    @Test fun partKinds() {
        assertEquals(MmsParts.Kind.TEXT, MmsParts.kind("text/plain"))
        assertEquals(MmsParts.Kind.IGNORE, MmsParts.kind("text/html"))
        assertEquals(MmsParts.Kind.IGNORE, MmsParts.kind("application/smil"))
        assertEquals(MmsParts.Kind.IGNORE, MmsParts.kind(null))
        assertEquals(MmsParts.Kind.IMAGE, MmsParts.kind("image/jpeg"))
        assertEquals(MmsParts.Kind.IGNORE, MmsParts.kind("video/3gpp"))
    }

    @Test fun fileLinesStopAtFirstEmptyLine() {
        assertEquals("ab", MmsParts.joinFileLines(sequenceOf("a", "b", "", "c")))
        assertEquals("ab", MmsParts.joinFileLines(sequenceOf("a", "b", null, "c")))
        assertEquals("", MmsParts.joinFileLines(sequenceOf("", "x")))
        assertEquals("한줄", MmsParts.joinFileLines(sequenceOf("한줄")))
    }

    @Test fun textsJoinWithoutSeparatorAndNullWhenNone() {
        assertEquals("가나", MmsParts.joinTexts(listOf("가", null, "나")))
        assertNull(MmsParts.joinTexts(emptyList()))
        assertNull(MmsParts.joinTexts(listOf(null, null)))
        assertEquals("", MmsParts.joinTexts(listOf("")))
    }
}
