package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.core.BackgroundRun.AutoStart
import io.github.casimirkwon.smsforwarder.core.BackgroundRun.ExitRecord
import io.github.casimirkwon.smsforwarder.core.BackgroundRun.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 백그라운드 실행 안내 판정. */
class BackgroundRunTest {
    private val pkg = "io.github.casimirkwon.smsforwarder"
    private val now = 10L * BackgroundRun.WEEK_MS
    private val day = 86_400_000L

    private fun exit(reason: Int = 10, desc: String? = "force stop", ts: Long = now - day, proc: String? = pkg) = ExitRecord(reason, proc, desc, ts)

    private fun input(
        shown: Boolean = false, xiaomi: Boolean = false, auto: AutoStart = AutoStart.UNKNOWN, oem: Boolean = false, stops: Int = 0,
        once: Boolean = false, needMiui: Boolean = true, last: Long = 0, at: Long = now,
    ) = BackgroundRun.Input(shown, xiaomi, auto, oem, stops, once, needMiui, last, at)

    // ── 종료 기록 ──
    @Test fun 조회_기준은_마지막_점검과_7일_전_중_늦은_쪽() {
        assertEquals(now - BackgroundRun.WEEK_MS, BackgroundRun.checkFrom(0, now))
        assertEquals(now - day, BackgroundRun.checkFrom(now - day, now))
        assertEquals(now - BackgroundRun.WEEK_MS, BackgroundRun.checkFrom(now - 30 * day, now))
    }

    @Test fun 사용자_강제_종료만_센다() {
        val list = listOf(exit(reason = 10), exit(reason = 10, ts = now - 2 * day), exit(reason = 3), exit(reason = 5), exit(reason = 13))
        assertEquals(2, BackgroundRun.countForceStops(list, pkg, now - BackgroundRun.WEEK_MS, 0))
    }

    @Test fun 점검_이후_기록과_우리_프로세스만() {
        val old = exit(ts = now - 3 * day)
        val fresh = exit(ts = now - day)
        val other = exit(proc = "$pkg:remote", ts = now - day)
        val noName = exit(proc = null, ts = now - day)
        // since = 2일 전: 3일 전 기록·다른 프로세스 이름·이름 없는 기록은 제외
        assertEquals(1, BackgroundRun.countForceStops(listOf(old, fresh, other, noName), pkg, now - 2 * day, 0))
        // 같은 시각은 "이후"가 아니다
        assertEquals(0, BackgroundRun.countForceStops(listOf(exit(ts = now - 2 * day)), pkg, now - 2 * day, 0))
    }

    @Test fun 설명에_무시_문구가_있으면_세지_않는다_대소문자_무시() {
        for (d in listOf("clear data", "installPackage", "replace pkg", "pkg removed", "start instr", "finished inst", "(com.android.settings)", "User Requested Clear Data!", "INSTALLPACKAGE x")) {
            assertEquals(d, 0, BackgroundRun.countForceStops(listOf(exit(desc = d)), pkg, 0, 0))
        }
        assertEquals(1, BackgroundRun.countForceStops(listOf(exit(desc = null)), pkg, 0, 0))
        assertEquals(1, BackgroundRun.countForceStops(listOf(exit(desc = "swipe")), pkg, 0, 0))
    }

    @Test fun 앱_갱신_시각_앞뒤_60초는_세지_않는다() {
        val upd = now - day
        assertEquals(0, BackgroundRun.countForceStops(listOf(exit(ts = upd)), pkg, 0, upd))
        assertEquals(0, BackgroundRun.countForceStops(listOf(exit(ts = upd - 60_000)), pkg, 0, upd))
        assertEquals(0, BackgroundRun.countForceStops(listOf(exit(ts = upd + 60_000)), pkg, 0, upd))
        assertEquals(1, BackgroundRun.countForceStops(listOf(exit(ts = upd + 60_001)), pkg, 0, upd))
        assertEquals(1, BackgroundRun.countForceStops(listOf(exit(ts = upd - 60_001)), pkg, 0, upd))
        // 갱신 시각을 모르면(0) 거르지 않는다
        assertEquals(1, BackgroundRun.countForceStops(listOf(exit(ts = 5)), pkg, 0, 0))
    }

    @Test fun appop_해석() {
        assertEquals(AutoStart.ALLOWED, BackgroundRun.autoStartFromOp(0))
        assertEquals(AutoStart.DENIED, BackgroundRun.autoStartFromOp(1))
        assertEquals(AutoStart.DENIED, BackgroundRun.autoStartFromOp(2))
        assertEquals(AutoStart.UNKNOWN, BackgroundRun.autoStartFromOp(3))
        assertEquals(AutoStart.UNKNOWN, BackgroundRun.autoStartFromOp(null))
    }

    // ── 표시 판정 ──
    @Test fun 이_화면에서_이미_띄웠으면_없음() {
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(shown = true, xiaomi = true, auto = AutoStart.DENIED, stops = 3)))
    }

    @Test fun 샤오미_자동시작_거부는_간격과_상관없이_매번() {
        assertEquals(Prompt.XIAOMI_DENIED, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.DENIED)))
        assertEquals(Prompt.XIAOMI_DENIED, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.DENIED, last = now - 1000, once = true)))
    }

    @Test fun 마지막_표시_7일_안이면_없음_정확히_7일이면_다시() {
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(stops = 1, last = now - day)))
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(stops = 1, last = now - BackgroundRun.WEEK_MS + 1)))
        assertEquals(Prompt.FORCE_STOPPED, BackgroundRun.decide(input(stops = 1, last = now - BackgroundRun.WEEK_MS)))
        // 시계가 거꾸로 간 경우(마지막 표시가 미래)는 막지 않는다
        assertEquals(Prompt.FORCE_STOPPED, BackgroundRun.decide(input(stops = 1, last = now + day)))
    }

    @Test fun 샤오미_모름은_한번만_그뒤는_강제종료일_때만() {
        assertEquals(Prompt.XIAOMI_UNKNOWN, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.UNKNOWN)))
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.UNKNOWN, once = true)))
        assertEquals(Prompt.FORCE_STOPPED, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.UNKNOWN, once = true, stops = 2)))
        // need_to_show_miui=false 도 "이미 표시"로 본다
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.UNKNOWN, needMiui = false)))
    }

    @Test fun 샤오미_자동시작_허용이면_강제종료가_있어도_없음() {
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(xiaomi = true, auto = AutoStart.ALLOWED, stops = 5)))
    }

    @Test fun 제조사_설정_화면이_있으면_한번만_그뒤는_강제종료일_때만() {
        assertEquals(Prompt.OEM_SETTINGS, BackgroundRun.decide(input(oem = true)))
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(oem = true, once = true)))
        assertEquals(Prompt.FORCE_STOPPED, BackgroundRun.decide(input(oem = true, once = true, stops = 1)))
        // 제조사 화면이 없으면 안내 대상이 아니다(샤오미 아닌 일반 기기)
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(oem = false)))
    }

    @Test fun 일반_기기에서_강제종료_기록이_있으면_안내() {
        assertEquals(Prompt.FORCE_STOPPED, BackgroundRun.decide(input(stops = 1)))
        assertEquals(Prompt.NONE, BackgroundRun.decide(input(stops = 0)))
    }

    @Test fun 한번만_표시_플래그를_올리는_안내() {
        assertTrue(BackgroundRun.marksOnceShown(Prompt.XIAOMI_UNKNOWN))
        assertTrue(BackgroundRun.marksOnceShown(Prompt.OEM_SETTINGS))
        assertFalse(BackgroundRun.marksOnceShown(Prompt.XIAOMI_DENIED))
        assertFalse(BackgroundRun.marksOnceShown(Prompt.FORCE_STOPPED))
        assertFalse(BackgroundRun.marksOnceShown(Prompt.NONE))
    }
}
