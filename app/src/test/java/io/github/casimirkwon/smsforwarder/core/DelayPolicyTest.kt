package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayPolicyTest {
    private val now = 1_800_000_000_000L

    /** 기본 동작: API 30 이하에서만 지연(기본 상수 LEGACY_SDK_GATE = true). */
    @Test fun delayedWhenBitAndMinutesOnLegacySdk() {
        val p = DelayPolicy.plan(true, 30, now, sdk = 30)
        assertEquals(now + 30 * 60_000L, p!!.sendTime)
    }

    @Test fun immediateWhenBitOffOrZeroMinutes() {
        assertNull(DelayPolicy.plan(false, 30, now, 30))
        assertNull(DelayPolicy.plan(true, 0, now, 30))
        assertNull(DelayPolicy.plan(true, -5, now, 30))
    }

    /** 기본값: API 31 이상은 지연 없이 즉시 전송(설정에 지연이 켜져 있어도). */
    @Test fun defaultGateSkipsDelayOnSdk31Plus() {
        assertTrue(DelayPolicy.LEGACY_SDK_GATE)
        assertTrue(DelayPolicy.appliesOnSdk(29))
        assertTrue(DelayPolicy.appliesOnSdk(30))
        assertFalse(DelayPolicy.appliesOnSdk(31))
        assertFalse(DelayPolicy.appliesOnSdk(36))
        assertNull(DelayPolicy.plan(true, 30, now, sdk = 35))
        assertNull(DelayPolicy.plan(true, 30, now, sdk = 31))
    }

    /** 코드 경로 보존 확인: 상수를 false 로 바꾸면(README '켜는 방법') 모든 API 에서 지연이 동작한다. */
    @Test fun gateOffAppliesOnAllApis() {
        assertTrue(DelayPolicy.appliesOnSdk(31, legacyGate = false))
        assertTrue(DelayPolicy.appliesOnSdk(36, legacyGate = false))
        assertEquals(now + 600_000L, DelayPolicy.plan(true, 10, now, sdk = 36, legacyGate = false)!!.sendTime)
    }

    /** SDK_INT >= 31 이면 지연 없이 곧바로 전송, 30 이하에서만 지연. */
    @Test fun legacyGateOnlyDelaysOnOldSdk() {
        assertNull(DelayPolicy.plan(true, 10, now, sdk = 31, legacyGate = true))
        assertNull(DelayPolicy.plan(true, 10, now, sdk = 36, legacyGate = true))
        assertEquals(now + 600_000L, DelayPolicy.plan(true, 10, now, sdk = 30, legacyGate = true)!!.sendTime)
        assertEquals(now + 600_000L, DelayPolicy.plan(true, 10, now, sdk = 29, legacyGate = true)!!.sendTime)
    }

    @Test fun dueWindowIs60sBeforeSendTime() {
        assertTrue(DelayPolicy.isDue(now + 60_000, now))
        assertFalse(DelayPolicy.isDue(now + 60_001, now))
        assertTrue(DelayPolicy.isDue(now - 1, now))
    }

    @Test fun recoveryReRegistersFarAlarmsAndRunsNowForDueOnes() {
        val r = DelayPolicy.recover(listOf(now + 120_000, now + 60_000, now - 5_000, now + 60_001), now)
        assertEquals(listOf(now + 120_000, now + 60_001), r.alarms)
        assertTrue(r.runNow)
    }

    @Test fun recoveryNoDueMeansNoImmediateRun() {
        val r = DelayPolicy.recover(listOf(now + 3_600_000), now)
        assertEquals(listOf(now + 3_600_000), r.alarms)
        assertFalse(r.runNow)
        val none = DelayPolicy.recover(emptyList(), now)
        assertTrue(none.alarms.isEmpty())
        assertFalse(none.runNow)
    }

    @Test fun pickerMinutes() {
        assertEquals(0, DelayPolicy.minutesOf(0, 0))
        assertEquals(90, DelayPolicy.minutesOf(1, 30))
        assertEquals(24 * 60 + 59, DelayPolicy.minutesOf(24, 59))
    }

    @Test fun delayCodes() {
        assertEquals(20, SendResult.SCHEDULED)
        assertEquals(23, SendResult.DELAY_CANCELLED)
    }
}
