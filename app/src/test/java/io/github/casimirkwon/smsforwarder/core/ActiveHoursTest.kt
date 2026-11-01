package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ActiveHoursTest {
    /** 시험용 도우미: 사례를 읽기 쉽게 "F;T_900,1800;F_0,0;…"(항상;매일;일~토) 꼴로 적고 저장 JSON 으로 바꾼다. */
    private fun compact(text: String): String {
        val parts = text.split(";")
        fun slot(i: Int): ActiveHours.Slot {
            val seg = parts[i].split("_")
            val t = seg[1].split(",")
            return ActiveHours.Slot(seg[0] == "T", t[0].toInt(), t[1].toInt())
        }
        return ActiveHours.build(ActiveHours.Schedule(parts[0] == "T", slot(1), (2..8).map { slot(it) }))
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance().apply { clear(); set(y, mo, d, h, mi) }

    @Test fun defaultIsAlwaysActive() {
        assertTrue(ActiveHours.isActive(ActiveHours.DEFAULT, at(2026, Calendar.OCTOBER, 31, 3, 0)))
        assertTrue(ActiveHours.isActive("깨진 값", at(2026, Calendar.OCTOBER, 31, 3, 0)))
        assertTrue(ActiveHours.isActive("F;T_900,1800;F_0,0;", at(2026, Calendar.OCTOBER, 31, 3, 0))) // 옛 문자열 형식은 읽지 않는다 → 기본(항상)
    }

    @Test fun dailyWindow() {
        val spec = compact("F;T_900,1800;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;")
        assertTrue(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 9, 0)))
        assertTrue(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 18, 0)))
        assertFalse(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 18, 1)))
    }

    @Test fun overnightWindow() {
        val spec = compact("F;T_2200,600;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;")
        assertTrue(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 23, 30)))
        assertTrue(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 5, 59)))
        assertFalse(ActiveHours.isActive(spec, at(2026, Calendar.OCTOBER, 31, 12, 0)))
    }

    @Test fun perWeekday() {
        // 일요일만 켜짐 (2026-11-01 은 일요일)
        val spec = compact("F;F_0,0;T_0,2359;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;F_0,0;")
        assertTrue(ActiveHours.isActive(spec, at(2026, Calendar.NOVEMBER, 1, 12, 0)))
        assertFalse(ActiveHours.isActive(spec, at(2026, Calendar.NOVEMBER, 2, 12, 0)))
    }

    @Test fun parseAndBuildRoundTrip() {
        assertTrue(ActiveHours.parse(ActiveHours.DEFAULT).always)
        assertEquals(ActiveHours.DEFAULT, ActiveHours.build(ActiveHours.parse(ActiveHours.DEFAULT)))
        val spec = compact("F;T_900,1800;F_0,0;T_1000,1200;F_0,0;F_0,0;F_0,0;F_0,0;T_2200,600;")
        val sc = ActiveHours.parse(spec)
        assertFalse(sc.always)
        assertEquals(ActiveHours.Slot(true, 900, 1800), sc.daily)
        assertEquals(ActiveHours.Slot(true, 1000, 1200), sc.days[1])
        assertEquals(ActiveHours.Slot(true, 2200, 600), sc.days[6])
        assertEquals(spec, ActiveHours.build(sc))
        // 깨진 값은 기본(항상)으로
        assertEquals(ActiveHours.DEFAULT, ActiveHours.build(ActiveHours.parse("깨진 값")))
        assertTrue(ActiveHours.isValid(ActiveHours.build(sc.copy(always = true))))
    }

    @Test fun hhmmValidation() {
        assertTrue(ActiveHours.isValidHhmm(0))
        assertTrue(ActiveHours.isValidHhmm(2359))
        assertFalse(ActiveHours.isValidHhmm(2400))
        assertFalse(ActiveHours.isValidHhmm(960))
    }
}
