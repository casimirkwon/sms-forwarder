package io.github.casimirkwon.smsforwarder

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.casimirkwon.smsforwarder.core.ExportImport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "config")

/** 설정 한 시점의 값. 저장 키는 Settings 클래스의 K 객체에 있다. */
data class AppSettings(
    /** 전달 기능 켜짐, 기본 true */
    val appEnable: Boolean = true,
    /** 포그라운드 서비스 모드 사용, 기본 false (API 31+ 는 꺼져 있으면 워커 모드) */
    val useForegroundService: Boolean = false,
    /** 전송 기록 저장, 기본 true */
    val saveSendData: Boolean = true,
    /** 실패한 전송 재시도, 기본 true */
    val retrySmsOnFailure: Boolean = true,
    /** 재시도 최대 횟수, 기본 3 */
    val retryMax: Int = 3,
    /** 전송 사이 대기 간격 최소/최대(초), 설정 화면 기본 3 / 7 */
    val messageDelayMin: Int = 3,
    val messageDelayMax: Int = 7,
    /** 기본 수신/발신 SIM 슬롯, 기본 -1(미지정) */
    val defaultInSlot: Int = -1,
    val defaultOutSlot: Int = -1,
    /** 사용자 지정 SIM 이름 */
    val simName1: String = "",
    val simName2: String = "",
    /** 수신 기록 보관일(1/3/7, 기본 1) */
    val historyRetentionDays: Int = 1,
    /** 전송 결과 알림, 기본 true */
    val resultNotification: Boolean = true,
    /** 전송 중 WakeLock 사용, 기본 true */
    val sendWakeEnabled: Boolean = true,
    /** 네트워크 연결시 재전송, 기본 true (재시도 워커가 활성 네트워크가 있을 때만 재전송) */
    val waitingNetwork: Boolean = true,
    /** 로밍 중 전달, 기본 true (끄면 로밍 중에는 전달하지 않음) */
    val roamingEnable: Boolean = true,
    /** 전역 근무시간: 사용 여부(기본 false)와 시작·끝(HHMM, 기본 -1) */
    val workTimeEnable: Boolean = false,
    val workStartTime: Int = -1,
    val workEndTime: Int = -1,
    /** 중복 문자 막기, 기본 true */
    val avoidDuplicate: Boolean = true,
    /** 중복이면 항상 막기, 기본 false */
    val stopDuplicateAlways: Boolean = false,
    /** 중복으로 볼 건수, 기본 5 */
    val duplicateCount: Int = 5,
    /** 반복 전송 막기, 기본 true */
    val preventRepeated: Boolean = true,
    /** 분당 최대 전송 건수, 기본 15 */
    val repeatedMaxCount: Int = 15,
    /** 월 SMS 건수 제한: 사용 여부(기본 false), 한도(기본 100), 이번 달 사용량(0), 마지막으로 초기화한 달(-1) */
    val smsLimitEnabled: Boolean = false,
    val smsLimitCount: Int = 100,
    val smsLimitCurrent: Int = 0,
    val smsLimitLastResetMonth: Int = -1,
    /** 중복 알림 무시, 기본 true */
    val ignoreDuplicateNoti: Boolean = true,
    /** 중복 알림을 판정할 때 알림 키까지 비교(엄격히 확인). 첫 실행 안내가 켜서 저장하는 값에 맞춰 기본 true */
    val checkDuplicateStrictly: Boolean = true,
    /** 요약 알림은 본문만 비교, 기본 true */
    val summaryNotiOnlyText: Boolean = true,
    /** 중복 알림으로 보는 간격(분), 기본 60 */
    val ignoreIntervalMinutes: Long = 60,
    /** 모든 앱의 요약 알림 전달. 요약 알림은 기본적으로 전달하지 않으므로 기본 false */
    val forwardSummaryAllApps: Boolean = false,
    /** 요약 알림을 전달할 앱 목록(JSON 배열), 기본 빈 목록 */
    val forwardSummaryPackages: String = "[]",
)

class Settings(private val context: Context) {
    private object K {
        val appEnable = booleanPreferencesKey("forwarding_enabled")
        val useFg = booleanPreferencesKey("foreground_mode")
        val saveSend = booleanPreferencesKey("keep_send_log")
        val retryOnFail = booleanPreferencesKey("retry_failed_sends")
        val httpRetry = intPreferencesKey("retry_limit")
        val delayMin = intPreferencesKey("send_gap_min_sec")
        val delayMax = intPreferencesKey("send_gap_max_sec")
        val inSlot = intPreferencesKey("sim_default_in")
        val outSlot = intPreferencesKey("sim_default_out")
        val sim1 = stringPreferencesKey("sim_label_1")
        val sim2 = stringPreferencesKey("sim_label_2")
        val retention = intPreferencesKey("received_keep_days")
        val noti = booleanPreferencesKey("result_notice")
        val wake = booleanPreferencesKey("wake_while_sending")
        val waitingNetwork = booleanPreferencesKey("wait_for_network")
        val roaming = booleanPreferencesKey("allow_roaming")
        val workTime = booleanPreferencesKey("hours_limit_on")
        val startTime = intPreferencesKey("hours_start")
        val endTime = intPreferencesKey("hours_end")
        val avoidDup = booleanPreferencesKey("dedupe_on")
        val dupAlways = booleanPreferencesKey("dedupe_always")
        val dupCount = intPreferencesKey("dedupe_count")
        val preventRepeat = booleanPreferencesKey("burst_guard_on")
        val repeatMax = intPreferencesKey("burst_per_minute")
        val limitOn = booleanPreferencesKey("monthly_cap_on")
        val limitCount = intPreferencesKey("monthly_cap")
        val limitCurrent = intPreferencesKey("monthly_used")
        val limitMonth = intPreferencesKey("monthly_reset_month")
        val ignoreDupNoti = booleanPreferencesKey("noti_dedupe_on")
        val dupStrict = booleanPreferencesKey("noti_dedupe_strict")
        val summaryOnlyText = booleanPreferencesKey("summary_text_only")
        val ignoreInterval = longPreferencesKey("noti_dedupe_minutes")
        val summaryAll = booleanPreferencesKey("summary_all_apps")
        val summaryPkgs = stringPreferencesKey("summary_apps")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            appEnable = this[K.appEnable] ?: d.appEnable,
            useForegroundService = this[K.useFg] ?: d.useForegroundService,
            saveSendData = this[K.saveSend] ?: d.saveSendData,
            retrySmsOnFailure = this[K.retryOnFail] ?: d.retrySmsOnFailure,
            retryMax = this[K.httpRetry] ?: d.retryMax,
            messageDelayMin = this[K.delayMin] ?: d.messageDelayMin,
            messageDelayMax = this[K.delayMax] ?: d.messageDelayMax,
            defaultInSlot = this[K.inSlot] ?: d.defaultInSlot,
            defaultOutSlot = this[K.outSlot] ?: d.defaultOutSlot,
            simName1 = this[K.sim1] ?: d.simName1,
            simName2 = this[K.sim2] ?: d.simName2,
            historyRetentionDays = this[K.retention] ?: d.historyRetentionDays,
            resultNotification = this[K.noti] ?: d.resultNotification,
            sendWakeEnabled = this[K.wake] ?: d.sendWakeEnabled,
            waitingNetwork = this[K.waitingNetwork] ?: d.waitingNetwork,
            roamingEnable = this[K.roaming] ?: d.roamingEnable,
            workTimeEnable = this[K.workTime] ?: d.workTimeEnable,
            workStartTime = this[K.startTime] ?: d.workStartTime,
            workEndTime = this[K.endTime] ?: d.workEndTime,
            avoidDuplicate = this[K.avoidDup] ?: d.avoidDuplicate,
            stopDuplicateAlways = this[K.dupAlways] ?: d.stopDuplicateAlways,
            duplicateCount = this[K.dupCount] ?: d.duplicateCount,
            preventRepeated = this[K.preventRepeat] ?: d.preventRepeated,
            repeatedMaxCount = this[K.repeatMax] ?: d.repeatedMaxCount,
            smsLimitEnabled = this[K.limitOn] ?: d.smsLimitEnabled,
            smsLimitCount = this[K.limitCount] ?: d.smsLimitCount,
            smsLimitCurrent = this[K.limitCurrent] ?: d.smsLimitCurrent,
            smsLimitLastResetMonth = this[K.limitMonth] ?: d.smsLimitLastResetMonth,
            ignoreDuplicateNoti = this[K.ignoreDupNoti] ?: d.ignoreDuplicateNoti,
            checkDuplicateStrictly = this[K.dupStrict] ?: d.checkDuplicateStrictly,
            summaryNotiOnlyText = this[K.summaryOnlyText] ?: d.summaryNotiOnlyText,
            ignoreIntervalMinutes = this[K.ignoreInterval] ?: d.ignoreIntervalMinutes,
            forwardSummaryAllApps = this[K.summaryAll] ?: d.forwardSummaryAllApps,
            forwardSummaryPackages = this[K.summaryPkgs] ?: d.forwardSummaryPackages,
        )
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun snapshot(): AppSettings = flow.first()

    suspend fun setAppEnable(v: Boolean) = context.dataStore.edit { it[K.appEnable] = v }
    suspend fun setUseForegroundService(v: Boolean) = context.dataStore.edit { it[K.useFg] = v }
    suspend fun setRetrySmsOnFailure(v: Boolean) = context.dataStore.edit { it[K.retryOnFail] = v }
    suspend fun setWaitingNetwork(v: Boolean) = context.dataStore.edit { it[K.waitingNetwork] = v }
    suspend fun setSaveSendData(v: Boolean) = context.dataStore.edit { it[K.saveSend] = v }
    suspend fun setResultNotification(v: Boolean) = context.dataStore.edit { it[K.noti] = v }
    suspend fun setSendWakeEnabled(v: Boolean) = context.dataStore.edit { it[K.wake] = v }
    suspend fun setRetryMax(v: Int) = context.dataStore.edit { it[K.httpRetry] = v }
    suspend fun setMessageDelay(minSec: Int, maxSec: Int) = context.dataStore.edit {
        it[K.delayMin] = minSec
        it[K.delayMax] = maxSec
    }
    suspend fun setHistoryRetentionDays(v: Int) = context.dataStore.edit { it[K.retention] = v }
    suspend fun setSimNames(name1: String, name2: String) = context.dataStore.edit {
        it[K.sim1] = name1
        it[K.sim2] = name2
    }
    suspend fun setDefaultSlots(inSlot: Int, outSlot: Int) = context.dataStore.edit {
        it[K.inSlot] = inSlot
        it[K.outSlot] = outSlot
    }

    suspend fun setRoamingEnable(v: Boolean) = context.dataStore.edit { it[K.roaming] = v }

    /** 설정 화면에서 근무시간을 켤 때(시작·끝 시각과 함께 저장). */
    suspend fun setWorkTime(enable: Boolean, start: Int? = null, end: Int? = null) = context.dataStore.edit {
        it[K.workTime] = enable
        if (start != null) it[K.startTime] = start
        if (end != null) it[K.endTime] = end
    }

    suspend fun setDuplicate(enable: Boolean, always: Boolean, count: Int) = context.dataStore.edit {
        it[K.avoidDup] = enable
        it[K.dupAlways] = always
        it[K.dupCount] = count
    }

    suspend fun setRepeated(enable: Boolean, maxCount: Int) = context.dataStore.edit {
        it[K.preventRepeat] = enable
        it[K.repeatMax] = maxCount
    }

    suspend fun setSmsLimit(enable: Boolean, count: Int) = context.dataStore.edit {
        it[K.limitOn] = enable
        it[K.limitCount] = count
    }

    /** 알림 중복 거르기 설정("중복 알림 무시" 대화상자: 스위치, 시간(분), 엄격히 확인, 요약 알림은 본문만). */
    suspend fun setIgnoreDuplicateNoti(enable: Boolean, strict: Boolean, summaryOnlyText: Boolean, intervalMinutes: Long) = context.dataStore.edit {
        it[K.ignoreDupNoti] = enable
        it[K.dupStrict] = strict
        it[K.summaryOnlyText] = summaryOnlyText
        it[K.ignoreInterval] = intervalMinutes
    }

    /** 요약 알림 전달 설정("모든 앱" 스위치와 앱 목록). */
    suspend fun setForwardSummary(allApps: Boolean, packages: String) = context.dataStore.edit {
        it[K.summaryAll] = allApps
        it[K.summaryPkgs] = packages
    }

    /** 내보내기용: 저장돼 있는 사용자 설정 값만(기본값인 키는 빠진다). 내부 상태 키는 [ExportImport.SETTING_KEYS] 에 없어 제외된다. */
    suspend fun exportValues(): Map<String, Any> {
        val prefs = context.dataStore.data.first()
        val out = LinkedHashMap<String, Any>()
        for ((name, type) in ExportImport.SETTING_KEYS) {
            val v: Any? = when (type) {
                ExportImport.SettingType.BOOL -> prefs[booleanPreferencesKey(name)]
                ExportImport.SettingType.INT -> prefs[intPreferencesKey(name)]
                ExportImport.SettingType.LONG -> prefs[longPreferencesKey(name)]
                ExportImport.SettingType.STRING -> prefs[stringPreferencesKey(name)]
            }
            if (v != null) out[name] = v
        }
        return out
    }

    /** 가져오기용: 파일에 있는 키만 덮어쓴다(없는 키는 그대로). 형식은 [ExportImport.parse] 가 이미 확인했다. */
    suspend fun importValues(values: Map<String, Any>) {
        context.dataStore.edit { p ->
            for ((name, type) in ExportImport.SETTING_KEYS) {
                val v = values[name] ?: continue
                when (type) {
                    ExportImport.SettingType.BOOL -> p[booleanPreferencesKey(name)] = v as Boolean
                    ExportImport.SettingType.INT -> p[intPreferencesKey(name)] = (v as Number).toInt()
                    ExportImport.SettingType.LONG -> p[longPreferencesKey(name)] = (v as Number).toLong()
                    ExportImport.SettingType.STRING -> p[stringPreferencesKey(name)] = v as String
                }
            }
        }
    }

    /** 달이 바뀌었으면 현재 건수를 0 으로 되돌리고 그 달을 기록. */
    suspend fun resetSmsLimitIfNewMonth(currentMonth: Int) {
        context.dataStore.edit {
            if (io.github.casimirkwon.smsforwarder.core.SmsLimit.needsReset(currentMonth, it[K.limitMonth] ?: -1)) {
                it[K.limitCurrent] = 0
                it[K.limitMonth] = currentMonth
            }
        }
    }

    /** 전송 호출이 끝나면 건수를 1 올린다(조각 수와 상관없이 호출당 1). */
    suspend fun incrementSmsLimitCurrent() = context.dataStore.edit {
        it[K.limitCurrent] = (it[K.limitCurrent] ?: 0) + 1
    }
}
