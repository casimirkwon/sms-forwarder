package io.github.casimirkwon.smsforwarder.core

/**
 * 설정 화면의 라디오 선택지와
 * 저장 키(스위치 + "항상" + 기준 횟수) 사이의 대응.
 * 선택지: 끄기 / 중복 메시지 항상 막기 / "1분 동안 N개의 메시지".
 */
object DuplicateMode {
    const val OFF = 0
    const val ALWAYS = 1
    const val COUNT = 2

    fun of(avoid: Boolean, always: Boolean) = when {
        !avoid -> OFF
        always -> ALWAYS
        else -> COUNT
    }

    /** 모드 → (setting_avoid_duplicate_messages, stop_sending_duplicate_messages_always) */
    fun flags(mode: Int) = when (mode) {
        OFF -> false to false
        ALWAYS -> true to true
        else -> true to false
    }
}
