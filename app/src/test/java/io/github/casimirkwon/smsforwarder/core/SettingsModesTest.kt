package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModesTest {
    @Test fun duplicateModeFromFlags() {
        assertEquals(DuplicateMode.OFF, DuplicateMode.of(avoid = false, always = false))
        assertEquals(DuplicateMode.OFF, DuplicateMode.of(avoid = false, always = true))
        assertEquals(DuplicateMode.ALWAYS, DuplicateMode.of(avoid = true, always = true))
        assertEquals(DuplicateMode.COUNT, DuplicateMode.of(avoid = true, always = false))
    }

    @Test fun duplicateModeToFlags() {
        assertEquals(false to false, DuplicateMode.flags(DuplicateMode.OFF))
        assertEquals(true to true, DuplicateMode.flags(DuplicateMode.ALWAYS))
        assertEquals(true to false, DuplicateMode.flags(DuplicateMode.COUNT))
    }

    /** 기본값(avoid 켜짐, always 꺼짐, 횟수 5)은 "1분 동안 5개" 선택 상태다. */
    @Test fun defaultsAreCountMode() {
        val d = io.github.casimirkwon.smsforwarder.AppSettings()
        assertEquals(DuplicateMode.COUNT, DuplicateMode.of(d.avoidDuplicate, d.stopDuplicateAlways))
        assertEquals(5, d.duplicateCount)
        assertEquals(15, d.repeatedMaxCount)
    }
}
