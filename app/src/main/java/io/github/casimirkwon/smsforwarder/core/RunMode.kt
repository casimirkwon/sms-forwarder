package io.github.casimirkwon.smsforwarder.core

/**
 * 처리 경로 선택과 부팅·업데이트 후 동작 판정의 조건식.
 */
object RunMode {
    /** 워커 모드 = API 31 이상이고 "포그라운드 서비스 사용" 이 꺼져 있을 때. 그 밖에는 서비스 모드. */
    fun isWorkerMode(sdkInt: Int, useForegroundService: Boolean) = sdkInt >= 31 && !useForegroundService

    /** MainActivity 진입 시 서비스를 띄울지: 서비스 모드이고 앱 사용이 켜져 있을 때. */
    fun shouldStartServiceOnLaunch(workerMode: Boolean, appEnable: Boolean) = !workerMode && appEnable

    data class RestartPlan(val startService: Boolean, val warnBatteryOrPermission: Boolean)

    /**
     * 부팅·앱 업데이트 후. 서비스 모드: 필터가 있으면 서비스 기동을 시도하고, 기동에 실패했는데
     * 배터리 최적화 예외도 아니면 경고. 워커 모드: 필터가 있는데 배터리 최적화 예외가 아니면 경고.
     * (실패 여부는 기동을 시도한 뒤에야 알 수 있어 serviceStartFailed 를 받는다.)
     */
    fun restartPlan(workerMode: Boolean, hasFilters: Boolean, ignoringBattery: Boolean, serviceStartFailed: Boolean = false): RestartPlan {
        if (!hasFilters) return RestartPlan(startService = false, warnBatteryOrPermission = false)
        return if (workerMode) RestartPlan(false, !ignoringBattery)
        else RestartPlan(true, serviceStartFailed && !ignoringBattery)
    }
}
