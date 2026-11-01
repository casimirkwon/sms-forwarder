package io.github.casimirkwon.smsforwarder.capture

import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.core.DedupeConfig
import io.github.casimirkwon.smsforwarder.core.IncomingNotification
import io.github.casimirkwon.smsforwarder.core.NotiCompose
import io.github.casimirkwon.smsforwarder.core.NotiDedupe
import io.github.casimirkwon.smsforwarder.core.NotiRule
import io.github.casimirkwon.smsforwarder.core.NotiHistoryItem
import io.github.casimirkwon.smsforwarder.core.NotiText
import io.github.casimirkwon.smsforwarder.core.StepCode
import io.github.casimirkwon.smsforwarder.db.NotiSeenRow
import io.github.casimirkwon.smsforwarder.util.NotiAccessWarning
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 알림 리스너(BIND_NOTIFICATION_LISTENER_SERVICE, exported).
 *
 * - `onNotificationPosted` 는 곧바로 단일 스레드 작업 줄에 넘기고,
 *   작업 줄이 알림을 한 건씩 차례로 처리한다. 첫 작업은 DB(`noti_seen`)의 알림 이력 불러오기.
 * - 처리: 무시할 알림 거르기 → 필드 추출 → 중복 알림 거르기(메모리 목록 + DB) →
 *   요약 알림 판정 → 켜진 알림 규칙이 있으면 `ACTION_NOTI` 에 해당하는 이벤트를 처리 경로(워커/서비스)로 보낸다.
 * - `onListenerConnected`/`onListenerDisconnected`/`onDestroy` 에서 [connected] 를 갱신한다(메인 화면의
 *   "알림 서비스: 실행 중/실행 중 아님" 표시와 "알림 서비스 오류" 안내에 쓰인다). requestRebind 는 부르지 않는다.
 */
class NotificationCapture : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationCapture"

        /** 리스너가 연결된 상태인가 */
        @Volatile var connected = false

        /** 알림을 낸 앱이 기본 문자 앱인가(기본 문자 앱이 없으면 구글/삼성 메시지 앱). */
        fun isSmsApp(ctx: Context, pkg: String): Boolean {
            val default = try {
                Telephony.Sms.getDefaultSmsPackage(ctx) ?: ""
            } catch (e: Exception) {
                ""
            }
            if (default.isEmpty()) return pkg == "com.google.android.apps.messaging" || pkg == "com.samsung.android.messaging"
            return default == pkg
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** 중복 거르기에 쓰는 알림 이력(시간순) */
    private val history = ArrayList<NotiHistoryItem>()

    override fun onCreate() {
        Log.i(TAG, "onCreate")
        super.onCreate()
        val graph = (application as App).graph
        scope.launch {
            for (task in tasks) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e(TAG, "task failed", e)
                }
            }
        }
        tasks.trySend {
            val rows = graph.db.notiSeen().all()
            history.addAll(rows.map { NotiHistoryItem(it.id, it.time, it.text, it.bigText, it.messageText, it.rcs, it.notiKey) })
            Log.i(TAG, "mMsgList.size=${history.size}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
        connected = false
        tasks.close()
        scope.cancel()
    }

    override fun onListenerConnected() {
        Log.i(TAG, "onListenerConnected")
        super.onListenerConnected()
        connected = true
        NotiAccessWarning.onAccessRestored(this)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "onListenerDisconnected")
        super.onListenerDisconnected()
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        tasks.trySend { handle(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private suspend fun handle(sbn: StatusBarNotification) {
        val graph = (application as App).graph
        val pkg = sbn.packageName ?: return
        val notification = sbn.notification
        if (NotiText.shouldIgnore(pkg, packageName, sbn.isOngoing, notification.flags)) return
        Log.i(TAG, "onNotificationPosted: pkg=$pkg")

        val extras = notification.extras
        var imageCount = 0
        var title = ""
        var text = ""
        var subText = ""
        var bigText = ""
        var messageText = ""
        var oldText = ""
        try {
            val picture = extras.get("android.picture")
            val oldTextTemplate = extras.get("android.template") as? String
            title = NotiText.cleanTitle(extras.get("android.title").toString())
            text = extras.getCharSequence("android.text")?.toString() ?: ""
            subText = extras.getCharSequence("android.subText")?.toString() ?: ""
            bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            // MessagingStyle: 마지막 메시지의 text(없으면 "null" 문자열), 그림이면 개수만 센다.
            var lastMessageText: String? = null
            val messages = extras.get("android.messages") as? Array<*>
            if (messages != null && messages.isNotEmpty()) {
                val last = messages[messages.size - 1] as Bundle
                lastMessageText = last.get("text").toString()
                messageText = lastMessageText
                if (picture == null) {
                    val type = last.get("type") as? String
                    val uri = last.get("uri") as? Uri
                    if (!type.isNullOrEmpty() && type.contains("image/") && uri != null && canOpen(uri)) imageCount = 1
                }
            }
            if (picture is Bitmap) imageCount = 1
            oldText = try {
                NotiText.oldText(oldTextTemplate, text, bigText, lastMessageText)
            } catch (e: Exception) {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
        }

        // 추가 내용: 제목·본문·서브·긴 본문·메시지·앱 이름과 겹치지 않는 요약문·큰 제목·대화방 이름·보낸이·InboxStyle 줄
        val extraContent = try {
            extractExtraContent(sbn, extras, pkg, title, text, subText, bigText, messageText)
        } catch (e: Exception) {
            Log.e(TAG, "extra content failed", e)
            ""
        }

        val smsApp = isSmsApp(this, pkg)
        var time = if (smsApp) notification.`when` else sbn.postTime
        if (time <= 0) time = sbn.postTime
        Log.i(TAG, "onNotificationPosted: hasTitle=${!NotiText.isBlank(title)}, hasText=${!NotiText.isBlank(text)}")
        if (NotiText.allBlank(title, text, bigText, messageText, oldText)) return

        val s = graph.settings.snapshot()
        val cfg = DedupeConfig(
            ignoreDuplicates = s.ignoreDuplicateNoti,
            strict = s.checkDuplicateStrictly,
            summaryOnlyText = s.summaryNotiOnlyText,
            intervalMs = s.ignoreIntervalMinutes * 60_000L,
        )
        val isSummary = notification.flags and NotiText.FLAG_GROUP_SUMMARY != 0
        var item = NotiHistoryItem(0, time, text, bigText, messageText, false, sbn.key ?: "")
        val outcome = NotiDedupe.check(history, item, isSummary, cfg)
        Log.i(TAG, "isNotificationDuplicated: hasSameNotiMsg=${outcome.duplicate}, timeGap=${cfg.intervalMs}, checkDuplication=${cfg.ignoreDuplicates}, isSummaryNoti=$isSummary, checkKeyForNormalNoti=${cfg.strict}, summaryNotiOnlyText=${cfg.summaryOnlyText}")
        val staleIds = outcome.toDelete.map { it.id }.filter { it != 0L }
        if (!outcome.duplicate) {
            if (staleIds.isNotEmpty()) graph.db.notiSeen().delete(staleIds)
            val id = graph.db.notiSeen().insert(NotiSeenRow(0, item.time, item.text, item.bigText, item.messageText, item.isRcs, item.key))
            item = item.copy(id = id)
            history.add(item)
        } else if (staleIds.isNotEmpty()) {
            graph.db.notiSeen().delete(staleIds)
        }
        if (outcome.duplicate) {
            Log.i(TAG, "sendNotification: hasSameNotiMsg")
            return
        }

        var eventPackage = pkg
        val n0 = IncomingNotification(title, text, subText, bigText, messageText, oldText, time, pkg, imageCount, extraContent)
        if (isSummary) {
            val key = NotiText.SUMMARY_PREFIX + pkg
            if (!NotiRule.summaryAllowed(s.forwardSummaryAllApps, s.forwardSummaryPackages, pkg)) {
                Log.i(TAG, "sendNotification: no forward summary noti=$key")
                graph.pipeline.recordSkippedNotification(n0.copy(packageName = key), StepCode.SUMMARY_NOT_FORWARDED)
                return
            }
            eventPackage = key
        }
        Log.i(TAG, "Receive Notification = $eventPackage")
        val hasNotiFilter = graph.db.rules().all().any { it.enabled && it.isNotification }
        if (!hasNotiFilter) {
            Log.i(TAG, "sendNotification: No notification filter")
            graph.pipeline.recordSkippedNotification(n0.copy(packageName = eventPackage), null)
            return
        }
        Dispatcher.dispatchNotification(this, graph, n0.copy(packageName = eventPackage))
    }

    /**
     * 알림 추가 내용 추출. 항목마다 예외를 따로 삼켜 빈 값으로 둔다.
     * - `android.summaryText`, `android.title.big`, InboxStyle 의 `android.textLines`
     * - MessagingStyle(`android.template` 에 "MessagingStyle" 포함)이면 대화방 이름(대화 바로가기의 긴 라벨 → 짧은 라벨 → 없으면
     *   `android.conversationTitle`)과 마지막 메시지 보낸이(`sender` → 없으면 `sender_person` 이름)
     * 겹침 제거·줄바꿈 연결은 [NotiCompose.extraContent].
     */
    private fun extractExtraContent(
        sbn: StatusBarNotification, extras: Bundle, pkg: String,
        title: String, text: String, subText: String, bigText: String, messageText: String,
    ): String {
        fun <T> safe(default: T, block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "NotiExtraContent", e)
            default
        }
        val summary = safe<CharSequence?>(null) { extras.getCharSequence("android.summaryText") }
        val bigTitle = safe<CharSequence?>(null) { extras.getCharSequence("android.title.big") }
        val lines = safe(emptyList<CharSequence?>()) {
            // 정규화 후 빈 줄은 뺀다(NotiCompose 에서도 거르지만 여기서 먼저 뺀다)
            extras.getCharSequenceArray("android.textLines")?.toList()?.filter { NotiCompose.normalize(it).isNotEmpty() } ?: emptyList()
        }
        val template = safe("") { NotiCompose.normalize(extras.getString("android.template")) }
        var conversation: CharSequence? = null
        var sender: CharSequence? = null
        if (template.contains("MessagingStyle")) {
            var label = ""
            val ranking = safe<Ranking?>(null) {
                val r = Ranking()
                if (currentRanking?.getRanking(sbn.key, r) == true) r else null
            }
            if (ranking != null) {
                label = safe("") {
                    val shortcut = ranking.conversationShortcutInfo
                    if (shortcut == null) "" else {
                        NotiCompose.normalize(shortcut.longLabel).ifEmpty { NotiCompose.normalize(shortcut.shortLabel) }
                    }
                }
            }
            if (label.isEmpty()) label = safe("") { NotiCompose.normalize(extras.getCharSequence("android.conversationTitle")) }
            conversation = label
            sender = safe<CharSequence?>(null) {
                val messages = extras.get("android.messages") as? Array<*>
                val last = if (messages.isNullOrEmpty()) null else messages[messages.size - 1] as? Bundle
                if (last == null) "" else {
                    var name = NotiCompose.normalize(last.getCharSequence("sender"))
                    if (name.isEmpty()) {
                        val person = last.get("sender_person") as? Person
                        name = NotiCompose.normalize(person?.name)
                    }
                    name
                }
            }
        }
        return NotiCompose.extraContent(
            summary, bigTitle, conversation, sender, lines, title, text, subText, bigText, messageText,
        ) {
            try {
                packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
            } catch (e: Exception) {
                ""
            }
        }
    }

    /** 메시지 그림 URI 를 읽을 수 있는가(스트림을 열지 못하면 예외를 삼키고 false). */
    private fun canOpen(uri: Uri): Boolean = try {
        contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }
}
