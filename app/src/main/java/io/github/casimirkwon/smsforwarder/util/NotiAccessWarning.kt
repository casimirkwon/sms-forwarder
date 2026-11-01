package io.github.casimirkwon.smsforwarder.util

import android.content.Context
import io.github.casimirkwon.smsforwarder.core.NotiAccessWarn

/**
 * 알림 접근이 빠졌을 때의 경고 알림을 한 번만 띄우기 위한 상태 저장. 판정은 [NotiAccessWarn.decide].
 * 상태("이미 경고함")는 앱이 다시 시작돼도 남는다.
 */
object NotiAccessWarning {
    private const val PREFS = "perm_state"
    private const val KEY_WARNED = "noti_access_warned"

    /**
     * 알림 접근 점검. 접근이 있으면 true(경고 상태도 초기화하고 떠 있는 경고는 지운다).
     * 없으면 false — 켜진 알림 규칙이 있고 아직 경고하지 않았다면 경고 알림을 한 번 띄운다.
     */
    @Synchronized
    fun check(ctx: Context, needsAccess: Boolean): Boolean {
        val hasAccess = Perms.hasNotificationAccess(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val warned = sp.getBoolean(KEY_WARNED, false)
        val d = NotiAccessWarn.decide(hasAccess, needsAccess, warned)
        if (d.show) {
            // 알림 표시 권한이 없어 띄우지 못했다면 "경고함" 으로 기록하지 않는다(나중에 다시 시도).
            if (Perms.hasPostNotifications(ctx)) {
                Notifs.notificationAccessWarning(ctx)
                sp.edit().putBoolean(KEY_WARNED, true).apply()
            }
        } else if (d.warned != warned) {
            sp.edit().putBoolean(KEY_WARNED, d.warned).apply()
            if (hasAccess) Notifs.cancel(ctx, Notifs.ID_NOTI_ACCESS)
        }
        return hasAccess
    }

    /** 알림 서비스가 연결됐다는 건 접근이 돌아왔다는 뜻 — 경고 상태를 초기화한다. */
    @Synchronized
    fun onAccessRestored(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_WARNED, false)) {
            sp.edit().putBoolean(KEY_WARNED, false).apply()
            Notifs.cancel(ctx, Notifs.ID_NOTI_ACCESS)
        }
    }
}
