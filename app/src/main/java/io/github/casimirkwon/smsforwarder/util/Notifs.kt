package io.github.casimirkwon.smsforwarder.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.ui.MainActivity

/**
 * 알림 채널과 알림. 전달 결과·문제·안내 채널은 중요도 LOW, 상주(서비스·워커) 채널은 DEFAULT 이며,
 * 모든 채널은 소리·진동·불빛이 없다.
 */
object Notifs {
    const val CH_RESULT = "channel_result"
    const val CH_PROBLEM = "channel_problem"
    const val CH_UPDATE = "channel_notice"
    const val CH_WORKER = "channel_service"

    /** 안내 채널의 알림(서비스 시작 안내·시작 경고)은 같은 ID 를 써서 서로 덮어쓴다. */
    const val ID_HINT = 101
    const val ID_SIM_ERROR = 102
    const val ID_RESULT = 103
    const val ID_PERMISSION_ERROR = 104
    const val ID_NOTI_ACCESS = 105
    const val ID_WORKER = 110

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun quiet(id: String, name: String, importance: Int) = NotificationChannel(id, name, importance).apply {
            enableLights(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(quiet(CH_RESULT, ctx.getString(R.string.ch_result), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(quiet(CH_PROBLEM, ctx.getString(R.string.ch_problem), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(quiet(CH_UPDATE, ctx.getString(R.string.ch_notice), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(quiet(CH_WORKER, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notify(ctx: Context, id: Int, n: Notification) {
        if (!Perms.hasPostNotifications(ctx)) return
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    fun result(ctx: Context, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_RESULT, n)
    }

    fun problem(ctx: Context, id: Int, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, id, n)
    }

    /** 서비스 모드에서 권한이 부족할 때(문제 채널). */
    fun permissionError(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentTitle(ctx.getString(R.string.n_perm_title))
            .setContentText(ctx.getString(R.string.n_perm_text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_PERMISSION_ERROR, n)
    }

    /** 알림 접근이 빠져 알림 규칙 전달만 멈췄을 때(문자 전달은 계속). 한 번만 띄운다 — [NotiAccessWarning]. */
    fun notificationAccessWarning(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_PROBLEM)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentTitle(ctx.getString(R.string.n_noti_access_title))
            .setContentText(ctx.getString(R.string.n_noti_access_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.n_noti_access_text)))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_NOTI_ACCESS, n)
    }

    /** 부팅·업데이트 후 서비스 기동 실패 또는 배터리 최적화 예외 아님(안내 채널). */
    fun startupWarning(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(ctx.getString(R.string.n_startup_text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_HINT, n)
    }

    /** 서비스 시작 직전 안내(안내 채널, 사용자가 지울 수 없음). 서비스가 뜨면 지운다. */
    fun startServiceHint(ctx: Context, title: String? = null) {
        val b = NotificationCompat.Builder(ctx, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(ctx.getString(R.string.n_hint_text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (title != null) b.setContentTitle(title)
        val n = b.build().apply { flags = flags or Notification.FLAG_NO_CLEAR }
        notify(ctx, ID_HINT, n)
    }

    /** 서비스 모드의 상주 알림(상주 채널): "숨기기" 는 채널 알림 설정으로. */
    fun serviceNotification(ctx: Context): Notification {
        val hide = PendingIntent.getActivity(
            ctx, 13123,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CH_WORKER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            ctx, 4321,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_WORKER)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(ctx.getString(R.string.n_service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(R.drawable.ic_stat_forward, ctx.getString(R.string.n_service_hide), hide)
            .build()
    }

    /** 워커의 ForegroundInfo 알림(상주 채널, 서비스 타입 없음). */
    fun workerNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_WORKER)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentText(ctx.getString(R.string.n_worker_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 4321, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
}
