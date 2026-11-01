package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.ReceivedEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SimLabelTest {
    private fun row(kind: String, slot: Int) = ReceivedEntity(time = 0, receiveTime = 0, address = "발신", body = "", simSlot = slot, kind = kind)
    private val lab = { s: Int -> SimLabel.slot(s) }

    @Test fun 알림_행은_SIM_없이_앱이름_시각() {
        assertEquals("카카오톡 · 11/01 10:00:00", SimLabel.historyTitle(row(Reapply.KIND_NOTI, -1).copy(address = "카카오톡"), "11/01 10:00:00", lab))
        assertEquals("앱 · t", SimLabel.historyTitle(row(Reapply.KIND_NOTI, 0).copy(address = "앱"), "t", lab))
    }
    @Test fun 문자_슬롯0은_SIM1_슬롯1은_SIM2() {
        assertEquals("발신 · SIM 1 · t", SimLabel.historyTitle(row(Reapply.KIND_SMS, 0), "t", lab))
        assertEquals("발신 · SIM 2 · t", SimLabel.historyTitle(row(Reapply.KIND_SMS, 1), "t", lab))
    }
    @Test fun 슬롯_모르는_문자는_SIM_생략() {
        assertEquals("발신 · t", SimLabel.historyTitle(row(Reapply.KIND_SMS, -1), "t", lab))
    }
    @Test fun SIM_이름이_있으면_괄호로_붙는다() {
        assertEquals("SIM 1 (회사폰)", SimLabel.slot(0, custom = "회사폰", number = "010"))
        assertEquals("SIM 2 (010)", SimLabel.slot(1, number = "010", displayName = "KT"))
        assertEquals("SIM 2 (KT)", SimLabel.slot(1, displayName = "KT"))
        assertEquals("SIM 2", SimLabel.slot(1))
        assertEquals("발신 · SIM 1 (회사폰) · t", SimLabel.historyTitle(row(Reapply.KIND_SMS, 0), "t") { SimLabel.slot(it, "회사폰") })
    }
}
