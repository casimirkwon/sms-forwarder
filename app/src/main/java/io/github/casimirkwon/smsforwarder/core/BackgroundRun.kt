package io.github.casimirkwon.smsforwarder.core

/**
 * 백그라운드 실행 안내 판정(메인 화면 시작 시 안내창 큐).
 *
 * 앱 화면에 들어올 때(필터가 하나라도 있으면) 아래를 판정해 안내창을 최대 하나 띄운다.
 * - 샤오미 계열(`ro.miui.ui.version.code` 또는 `ro.mi.os.version.code` 가 비어 있지 않음)이면 appop 10008(자동 시작)을 읽는다.
 *   0 허용 / 1·2 거부 / 그 밖·예외 모름.
 * - 프로세스 종료 기록(ApplicationExitInfo)에서 마지막 점검 이후(처음이면 7일 전부터) 사용자 강제 종료(reason 10)를 센다.
 *   설명에 [IGNORED_DESCRIPTIONS] 중 하나가 들어 있거나(설정 앱·설치·데이터 삭제 등) 앱 갱신 시각 ±60초 안이면 세지 않는다.
 * - 마지막 점검 시각은 판정 때마다(종료 기록을 읽은 뒤) 지금으로 저장한다.
 * 안내창을 띄우면 마지막 표시 시각을 저장하고, 샤오미 "모름"·제조사 설정 안내는 "한 번만" 표시 플래그도 올린다.
 */
object BackgroundRun {
    /** 0x240c8400 = 7일. 종료 기록 조회 시작 한도이자 같은 안내 사이 최소 간격. */
    const val WEEK_MS = 604_800_000L

    /** ApplicationExitInfo.REASON_USER_REQUESTED */
    const val REASON_USER_REQUESTED = 10

    /** appop OP_AUTO_START (샤오미 자동 시작) */
    const val OP_AUTO_START = 10008

    /** 이 문구가 설명에 들어 있는 종료는 사용자가 앱을 끈 것으로 세지 않는다(대소문자 무시, 부분 일치). */
    val IGNORED_DESCRIPTIONS = listOf(
        "clear data", "installPackage", "replace pkg", "pkg removed", "start instr", "finished inst", "(com.android.settings)",
    )

    /** 앱 갱신 시각과 이 시간 안(±60초, 끝은 포함하지 않는 60,001ms 미만)에 일어난 종료는 갱신에 따른 종료로 보고 세지 않는다. */
    private const val UPDATE_WINDOW_BEFORE_MS = 60_000L
    private const val UPDATE_WINDOW_AFTER_MS = 60_001L

    enum class AutoStart { ALLOWED, DENIED, UNKNOWN }

    enum class Prompt { NONE, XIAOMI_DENIED, XIAOMI_UNKNOWN, OEM_SETTINGS, FORCE_STOPPED }

    /** 종료 기록 한 건(ApplicationExitInfo 의 reason·processName·description·timestamp). */
    data class ExitRecord(val reason: Int, val processName: String?, val description: String?, val timestamp: Long)

    /** appop 결과 → 허용 여부. */
    fun autoStartFromOp(mode: Int?): AutoStart = when (mode) {
        0 -> AutoStart.ALLOWED
        1, 2 -> AutoStart.DENIED
        else -> AutoStart.UNKNOWN
    }

    /** 종료 기록 조회 기준 시각 = max(저장된 마지막 점검 시각, 지금 − 7일). */
    fun checkFrom(lastChecked: Long, now: Long): Long = maxOf(lastChecked, now - WEEK_MS)

    /**
     * 사용자 강제 종료 횟수. [exits] 중 우리 프로세스의 [since] 이후 기록만 보고, reason 10 이면서 설명이 무시 목록에 없고
     * 앱 갱신 시각([lastUpdateTime], 0 이하면 모름) ±60초 밖인 것을 센다.
     */
    fun countForceStops(exits: List<ExitRecord>, packageName: String, since: Long, lastUpdateTime: Long): Int =
        exits.filter { it.processName == packageName && it.timestamp > since }.count { e ->
            if (e.reason != REASON_USER_REQUESTED) return@count false
            val desc = e.description ?: ""
            if (IGNORED_DESCRIPTIONS.any { desc.contains(it, ignoreCase = true) }) return@count false
            if (lastUpdateTime > 0) {
                val diff = e.timestamp - lastUpdateTime
                if (diff >= -UPDATE_WINDOW_BEFORE_MS && diff < UPDATE_WINDOW_AFTER_MS) return@count false
            }
            true
        }

    data class Input(
        /** 이 화면(MainActivity 인스턴스)에서 이미 안내창을 띄웠는가 */
        val shownThisSession: Boolean,
        val xiaomi: Boolean,
        val autoStart: AutoStart,
        /** 샤오미가 아닐 때, 열 수 있는 제조사 자동 시작 설정 화면이 있는가 */
        val hasOemIntent: Boolean,
        val forceStops: Int,
        /** 저장값 `bg_run_once_shown`(기본 false) */
        val onceShown: Boolean,
        /** 저장값 `show_xiaomi_hint`(기본 true; 이 값을 바꾸는 곳이 없어 항상 true) */
        val needMiui: Boolean,
        /** 저장값 `bg_run_last_shown`(기본 0) */
        val lastShown: Long,
        val now: Long,
    )

    /** 안내 결정표: 이번 화면 진입에서 띄울 안내를 고른다. */
    fun decide(i: Input): Prompt {
        if (i.shownThisSession) return Prompt.NONE
        if (i.xiaomi && i.autoStart == AutoStart.DENIED) return Prompt.XIAOMI_DENIED
        if (i.lastShown > 0) {
            val elapsed = i.now - i.lastShown
            if (elapsed in 0 until WEEK_MS) return Prompt.NONE
        }
        val alreadyShown = i.onceShown || (i.xiaomi && !i.needMiui)
        val forceStopPrompt = if (i.forceStops > 0) Prompt.FORCE_STOPPED else Prompt.NONE
        return if (i.xiaomi) {
            when (i.autoStart) {
                AutoStart.ALLOWED -> Prompt.NONE
                AutoStart.DENIED -> Prompt.XIAOMI_DENIED
                AutoStart.UNKNOWN -> if (!alreadyShown) Prompt.XIAOMI_UNKNOWN else forceStopPrompt
            }
        } else {
            if (i.hasOemIntent && !alreadyShown) Prompt.OEM_SETTINGS else forceStopPrompt
        }
    }

    /** 안내창을 띄운 뒤 `bg_run_once_shown` 을 올릴 안내인가(샤오미 모름·제조사 설정). */
    fun marksOnceShown(p: Prompt): Boolean = p == Prompt.XIAOMI_UNKNOWN || p == Prompt.OEM_SETTINGS
}
