package io.github.casimirkwon.smsforwarder.core

import io.github.casimirkwon.smsforwarder.db.ReceivedEntity

/** SIM 표기. 슬롯은 0부터 저장하고 화면에는 SIM 1·SIM 2 (slot+1) 로 보인다. */
object SimLabel {
    /** "SIM 1" 또는 "SIM 1 (이름)". 이름은 사용자 지정명 → 전화번호 → 표시 이름 순. */
    fun slot(slot: Int, custom: String = "", number: String? = null, displayName: String? = null): String {
        val extra = custom.ifEmpty { null } ?: number ?: displayName
        return "SIM ${slot + 1}" + (extra?.let { " ($it)" } ?: "")
    }

    /** 수신 이력 한 줄 제목. 알림 행은 SIM 없이 "앱 이름 · 시각", 문자·MMS 행은 슬롯을 알 때만 "발신 · SIM n · 시각". */
    fun historyTitle(h: ReceivedEntity, time: String, simLabel: (Int) -> String): String =
        if (h.kind == Reapply.KIND_NOTI || h.simSlot < 0) "${h.address} · $time" else "${h.address} · ${simLabel(h.simSlot)} · $time"
}
