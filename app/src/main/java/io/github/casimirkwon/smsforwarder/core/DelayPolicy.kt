package io.github.casimirkwon.smsforwarder.core

/**
 * 지연 전달 판정(SMS 경로의 예약 계획, 알람이 울렸을 때의 대상 판정, 부팅 복구의 조건식).
 *
 * 동작:
 * - 규칙 옵션 비트 128 이 켜져 있고 규칙의 `delaytime`(분) > 0 이면 수신처마다 전달 건을 상태 10000003(예약됨)으로 만들고
 *   전송 시각을 `지금 + 분×60000` 으로 둔다. 곧바로 보내지 않고 AlarmManager 알람만 건다.
 * - 알람이 울리면 예약 행 중 `지금 + 60초 ≥ 전송 시각` 인 것을 id 오름차순으로 전송한다.
 * - 이 지연은 기본적으로 **API 30 이하에서만** 적용한다(`SDK_INT >= 31` 이면 곧바로 전송, 설정 화면의 스위치도 기본은 숨김).
 *   부팅 후 알람 복구도 `SDK_INT < 31` 일 때만 한다([LEGACY_SDK_GATE] = true). 코드 경로는 남겨 두었으므로
 *   모든 API 에서 지연 전달을 쓰려면 README '지연 전달 켜는 방법'(상수·권한·UI 세 곳)을 따른다.
 */
object DelayPolicy {
    /** true 면 `sdk < 31` 일 때만 지연 전달을 적용한다(기본). false 로 바꾸면 모든 API 에서 동작(README 참조). */
    const val LEGACY_SDK_GATE = true

    /**
     * 규칙 편집창의 "지연발송" 스위치를 보일지. 지연 전달이 기본으로 꺼져 있으므로 기본은 숨김.
     * 지연 전달을 켜려면 [LEGACY_SDK_GATE] 와 함께 true 로 바꾼다(README '지연 전달 켜는 방법').
     */
    const val SHOW_DELAY_UI = false

    /** 전송 시각이 이 시간(60초) 안으로 다가왔으면 알람을 기다리지 않고 바로 보낸다(알람 처리·부팅 복구 공통). */
    const val DUE_WINDOW_MS = 60_000L

    /** 지연 전달 계획: 이 시각에 보낸다. */
    data class Plan(val sendTime: Long)

    fun appliesOnSdk(sdk: Int, legacyGate: Boolean = LEGACY_SDK_GATE) = !legacyGate || sdk < 31

    /** 규칙의 지연 전달 설정(켜짐 여부·지연 분)으로 지연 계획을 낸다. 즉시 전송이면 null. */
    fun plan(enabled: Boolean, delayMinutes: Long, now: Long, sdk: Int, legacyGate: Boolean = LEGACY_SDK_GATE): Plan? {
        if (!appliesOnSdk(sdk, legacyGate)) return null
        if (!enabled || delayMinutes <= 0) return null
        return Plan(now + delayMinutes * 60_000L)
    }

    /** 전송 대상 판정: `지금 + 60초 ≥ 전송 시각`. */
    fun isDue(sendTime: Long, now: Long) = now + DUE_WINDOW_MS >= sendTime

    data class Recovery(
        /** 알람을 다시 걸 전송 시각들(지금 + 60초 보다 먼 것, 입력 순서 유지) */
        val alarms: List<Long>,
        /** 이미 때가 지났거나 1분 안인 건이 있어 곧바로 지연 전송 처리를 돌려야 하는지 */
        val runNow: Boolean,
    )

    /** 부팅 복구(BOOT_COMPLETED): 예약 행의 전송 시각 목록 → 알람 재등록 목록과 즉시 처리 여부. */
    fun recover(sendTimes: List<Long>, now: Long): Recovery {
        val limit = now + DUE_WINDOW_MS
        val alarms = ArrayList<Long>()
        var runNow = false
        for (t in sendTimes) {
            if (t > limit) alarms.add(t) else runNow = true
        }
        return Recovery(alarms, runNow)
    }

    /** 규칙 편집창의 시·분 선택(시 0~24, 분 0~59) → 지연 분. */
    fun minutesOf(hours: Int, minutes: Int) = hours * 60L + minutes
}
