package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunModeTest {
    @Test fun workerModeNeedsApi31AndSwitchOff() {
        assertFalse(RunMode.isWorkerMode(29, false))
        assertFalse(RunMode.isWorkerMode(30, false))
        assertTrue(RunMode.isWorkerMode(31, false))
        assertTrue(RunMode.isWorkerMode(36, false))
        assertFalse(RunMode.isWorkerMode(31, true))
        assertFalse(RunMode.isWorkerMode(36, true))
    }

    @Test fun startServiceOnLaunchOnlyInServiceModeWhenEnabled() {
        assertTrue(RunMode.shouldStartServiceOnLaunch(workerMode = false, appEnable = true))
        assertFalse(RunMode.shouldStartServiceOnLaunch(workerMode = false, appEnable = false))
        assertFalse(RunMode.shouldStartServiceOnLaunch(workerMode = true, appEnable = true))
    }

    @Test fun restartNoFiltersDoesNothing() {
        assertEquals(RunMode.RestartPlan(false, false), RunMode.restartPlan(false, false, false, true))
        assertEquals(RunMode.RestartPlan(false, false), RunMode.restartPlan(true, false, false))
    }

    @Test fun restartWorkerModeWarnsOnlyWithoutBatteryException() {
        assertEquals(RunMode.RestartPlan(false, true), RunMode.restartPlan(true, true, false))
        assertEquals(RunMode.RestartPlan(false, false), RunMode.restartPlan(true, true, true))
    }

    @Test fun restartServiceModeStartsServiceAndWarnsOnlyOnFailure() {
        assertEquals(RunMode.RestartPlan(true, false), RunMode.restartPlan(false, true, false, serviceStartFailed = false))
        assertEquals(RunMode.RestartPlan(true, true), RunMode.restartPlan(false, true, false, serviceStartFailed = true))
        assertEquals(RunMode.RestartPlan(true, false), RunMode.restartPlan(false, true, true, serviceStartFailed = true))
    }
}
