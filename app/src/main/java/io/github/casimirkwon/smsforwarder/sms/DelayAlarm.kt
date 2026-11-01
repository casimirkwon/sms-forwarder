package io.github.casimirkwon.smsforwarder.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 지연 전달 알람. `setExactAndAllowWhileIdle(RTC_WAKEUP, 전송 시각, PendingIntent)` 를 건다.
 * PendingIntent 는 앱 수신기에 보내는 명시적 브로드캐스트이고, requestCode 는 전송 시각을 int 로 자른 값(`(int) j`),
 * 플래그는 FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT 다.
 *
 * 권한: 매니페스트에는 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM 이 없다. 기본 설정(API 30 이하에서만 지연)에서는 권한 없이
 * `setExactAndAllowWhileIdle` 이 동작한다. API 31+ 에서 지연을 켜는 경우(README '지연 전달 켜는 방법')에는 매니페스트에 정확 알람
 * 권한을 추가해야 하고, 정확 알람을 쓸 수 없는 상태면 `setAndAllowWhileIdle`(부정확) 알람으로 대신한다.
 */
object DelayAlarm {
    const val ACTION = "io.github.casimirkwon.smsforwarder.action.DELAY_DUE"
    private const val TAG = "DelayAlarm"

    /** 알람을 걸고 정확 알람으로 걸었으면 true, 부정확 알람으로 대신했으면 false. */
    fun register(ctx: Context, timeMillis: Long): Boolean {
        Log.i(TAG, "register - delayTime=$timeMillis")
        val intent = Intent(ctx, SystemReceiver::class.java).setAction(ACTION)
        val pi = PendingIntent.getBroadcast(ctx, timeMillis.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pi)
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "exact alarm denied, fallback to inexact", e)
            }
        } else {
            Log.w(TAG, "canScheduleExactAlarms=false, fallback to inexact")
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pi)
        return false
    }
}
