package io.github.casimirkwon.smsforwarder.util

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.casimirkwon.smsforwarder.capture.NotificationCapture

/** 런타임 권한 묶음: 전화 상태, SMS 수신·읽기, SMS 발송, 연락처. */
object Perms {
    val PHONE_STATE = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
    /** READ_SMS + RECEIVE_SMS + RECEIVE_MMS */
    val SMS_RECEIVE = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_MMS)
    val SMS_SEND = arrayOf(Manifest.permission.SEND_SMS)
    val CONTACTS = arrayOf(Manifest.permission.READ_CONTACTS)

    fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun hasAll(ctx: Context, perms: Array<String>) = perms.all { has(ctx, it) }

    fun hasPhoneState(ctx: Context) = has(ctx, Manifest.permission.READ_PHONE_STATE)
    fun hasContacts(ctx: Context) = has(ctx, Manifest.permission.READ_CONTACTS)

    /** 알림 접근(알림 리스너) 허용 여부. API 27 이상은 isNotificationListenerAccessGranted. */
    fun hasNotificationAccess(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationListenerAccessGranted(ComponentName(ctx, NotificationCapture::class.java))
    }

    /** 알림 접근 설정 화면(ACTION_NOTIFICATION_LISTENER_SETTINGS). */
    fun openNotificationAccessSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun hasPostNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    /** 한 번에 요청하는 런타임 권한 전체. */
    fun runtimeAll(): Array<String> {
        val l = ArrayList<String>()
        l += PHONE_STATE
        l += SMS_RECEIVE
        l += SMS_SEND
        l += CONTACTS
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /**
     * 배터리 최적화 예외 요청: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 권한이 있으면
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS(package: URI)로 바로 요청하고, 없으면 예외 설정 목록 화면을 연다.
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        val intent = if (has(ctx, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        ctx.startActivity(intent)
    }
}
