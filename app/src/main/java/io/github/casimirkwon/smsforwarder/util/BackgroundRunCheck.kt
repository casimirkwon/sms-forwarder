package io.github.casimirkwon.smsforwarder.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.util.Log
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.BackgroundRun
import io.github.casimirkwon.smsforwarder.core.BackgroundRun.AutoStart
import io.github.casimirkwon.smsforwarder.core.BackgroundRun.Prompt

/**
 * 백그라운드 실행 안내의 시스템 쪽(종료 기록 조회·제조사 설정 화면 탐색·시스템 속성 읽기). 판정식은 [BackgroundRun].
 * 저장값(SharedPreferences `background_hint`): `exit_checked_at`(마지막 종료 기록 점검 시각), `once_shown`, `last_shown`,
 * `show_xiaomi_hint`(읽기만 하며 기본 true).
 */
object BackgroundRunCheck {
    private const val TAG = "BackgroundRun"
    private const val PREFS = "background_hint"
    private const val K_CHECKED_AT = "exit_checked_at"
    private const val K_ONCE_SHOWN = "once_shown"
    private const val K_LAST_SHOWN = "last_shown"
    private const val K_NEED_MIUI = "show_xiaomi_hint"

    /** 안내창에 띄울 내용. [settingsIntent] 가 있으면 "백그라운드 설정 열기" 단추를 보인다. */
    data class Dialog(val prompt: Prompt, val message: String, val settingsIntent: Intent?)

    /** 샤오미 보안 센터 자동 시작 관리 화면 */
    private val XIAOMI_COMPONENTS = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )

    /** 제조사별 자동 시작·백그라운드 실행 관리 화면(앞에서부터 처음 열 수 있는 것) */
    private val OEM_COMPONENTS = listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.zui.safecenter", "com.lenovo.safecenter.MainTab.LeSafeMainActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** SystemProperties.get 를 리플렉션으로 읽고, 실패하면 빈 문자열. */
    private fun sysProp(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun isXiaomi() = sysProp("ro.miui.ui.version.code").isNotEmpty() || sysProp("ro.mi.os.version.code").isNotEmpty()

    /** 샤오미 자동 시작 appop(10008). 읽지 못하면 모름. */
    private fun autoStart(ctx: Context): AutoStart = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        BackgroundRun.autoStartFromOp(m.invoke(ops, BackgroundRun.OP_AUTO_START, Process.myUid(), ctx.packageName) as? Int)
    } catch (_: Throwable) {
        AutoStart.UNKNOWN
    }

    /** 이 인텐트로 열 수 있는 노출된 화면이 있고(필요한 권한이 있거나 없고) 그렇다면 true. */
    private fun canOpen(ctx: Context, intent: Intent): Boolean = try {
        val pm = ctx.packageManager
        val ri = pm.resolveActivity(intent, 0x10000) ?: pm.resolveActivity(intent, 0)
        val ai = ri?.activityInfo
        ai != null && ai.exported && (ai.permission == null || ctx.checkSelfPermission(ai.permission) == 0)
    } catch (_: Exception) {
        false
    }

    /** 목록에서 처음 열 수 있는 화면의 인텐트. */
    private fun firstOpenable(ctx: Context, list: List<ComponentName>): Intent? =
        list.map { Intent().setComponent(it) }.firstOrNull { canOpen(ctx, it) }

    /** 마지막 점검 이후 사용자 강제 종료 횟수(프로세스 종료 기록 조회). 조회 실패는 0. */
    private fun forceStops(ctx: Context, now: Long): Int = try {
        val since = BackgroundRun.checkFrom(prefs(ctx).getLong(K_CHECKED_AT, 0L), now)
        val lastUpdate = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
            .filter { it.processName == ctx.packageName && it.timestamp > since }
        exits.forEach { Log.d(TAG, "exit reason=${it.reason} importance=${it.importance} desc=${it.description}") }
        BackgroundRun.countForceStops(
            exits.map { BackgroundRun.ExitRecord(it.reason, it.processName, it.description, it.timestamp) },
            ctx.packageName, since, lastUpdate,
        )
    } catch (e: Exception) {
        Log.w(TAG, "exit info failed", e)
        0
    }

    /**
     * 화면에 들어올 때의 판정. 종료 기록을 읽은 뒤 마지막 점검 시각을 저장하고, 안내를 띄우기로 정하면 마지막 표시 시각(과 필요하면
     * 한 번만 표시 플래그)을 저장한 뒤 내용을 돌려준다. 안내할 것이 없으면 null. 블로킹 호출이 있으므로 백그라운드 스레드에서 부른다.
     */
    fun evaluate(ctx: Context, now: Long, shownThisSession: Boolean): Dialog? {
        val sp = prefs(ctx)
        val xiaomi = isXiaomi()
        val auto = if (xiaomi) autoStart(ctx) else AutoStart.UNKNOWN
        val oemIntent = if (xiaomi) null else firstOpenable(ctx, OEM_COMPONENTS)
        val stops = forceStops(ctx, now)
        sp.edit().putLong(K_CHECKED_AT, now).apply()
        val prompt = BackgroundRun.decide(
            BackgroundRun.Input(
                shownThisSession = shownThisSession,
                xiaomi = xiaomi,
                autoStart = auto,
                hasOemIntent = oemIntent != null,
                forceStops = stops,
                onceShown = sp.getBoolean(K_ONCE_SHOWN, false),
                needMiui = sp.getBoolean(K_NEED_MIUI, true),
                lastShown = sp.getLong(K_LAST_SHOWN, 0L),
                now = now,
            ),
        )
        Log.d(TAG, "evaluate xiaomi=$xiaomi autoStart=$auto oem=${oemIntent?.component?.packageName} forceStops=$stops prompt=$prompt")
        if (prompt == Prompt.NONE) return null

        val intent = if (xiaomi) {
            firstOpenable(ctx, XIAOMI_COMPONENTS)
                ?: Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT).takeIf { canOpen(ctx, it) }
        } else oemIntent

        val message = when (prompt) {
            Prompt.XIAOMI_DENIED ->
                ctx.getString(R.string.bg_xiaomi_denied)
            Prompt.XIAOMI_UNKNOWN ->
                ctx.getString(R.string.bg_xiaomi_unknown)
            Prompt.OEM_SETTINGS ->
                ctx.getString(R.string.bg_oem)
            Prompt.FORCE_STOPPED ->
                if (Perms.ignoringBatteryOptimizations(ctx)) {
                    ctx.getString(R.string.bg_force_stopped_unrestricted)
                } else {
                    ctx.getString(R.string.bg_force_stopped_restricted)
                }
            Prompt.NONE -> return null
        }

        val ed = sp.edit().putLong(K_LAST_SHOWN, now)
        if (BackgroundRun.marksOnceShown(prompt)) ed.putBoolean(K_ONCE_SHOWN, true)
        ed.apply()
        return Dialog(prompt, message, intent)
    }

    /** "백그라운드 설정 열기": 인텐트로 열고, 실패하면 앱 정보 화면으로 간다. */
    fun openSettings(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent)
            Log.d(TAG, "openSettings ${intent.component?.packageName ?: intent.action}")
        } catch (e: Exception) {
            Log.w(TAG, "openSettings failed", e)
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            } catch (e2: Exception) {
                Log.w(TAG, "app details failed", e2)
            }
        }
    }
}
