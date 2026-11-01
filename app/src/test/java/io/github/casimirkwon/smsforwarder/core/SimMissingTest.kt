package io.github.casimirkwon.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장된 SIM 이 기기에 없을 때. */
class SimMissingTest {
    @Test fun 기본값_minus1_은_없는_SIM_이_아니다() {
        assertFalse(SimMissing.isMissing(-1, emptyList()))
        assertFalse(SimMissing.isMissing(-1, listOf(0, 1)))
    }

    @Test fun 활성_목록에_없는_슬롯은_없는_SIM() {
        assertTrue(SimMissing.isMissing(1, listOf(0)))
        assertTrue(SimMissing.isMissing(0, emptyList()))
        assertFalse(SimMissing.isMissing(1, listOf(0, 1)))
    }

    @Test fun 없는_SIM_은_기본값으로_바뀌고_있는_SIM_은_그대로() {
        assertEquals(-1, SimMissing.effective(1, listOf(0)))
        assertEquals(0, SimMissing.effective(0, listOf(0)))
        assertEquals(-1, SimMissing.effective(-1, listOf(0)))
    }

    @Test fun 안내_이름_목록은_쉼표로_잇고_비면_null() {
        assertEquals("수신 SIM, 발신 SIM", SimMissing.joinedNames(listOf("수신 SIM", "발신 SIM")))
        assertEquals("발신 SIM", SimMissing.joinedNames(listOf("발신 SIM")))
        assertNull(SimMissing.joinedNames(emptyList()))
    }
}
