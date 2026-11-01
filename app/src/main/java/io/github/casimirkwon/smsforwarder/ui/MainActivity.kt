package io.github.casimirkwon.smsforwarder.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.github.casimirkwon.smsforwarder.App
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.RunMode
import io.github.casimirkwon.smsforwarder.sms.ForwardingService
import io.github.casimirkwon.smsforwarder.util.BackgroundRunCheck
import io.github.casimirkwon.smsforwarder.util.Notifs
import io.github.casimirkwon.smsforwarder.util.Perms
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** 이 화면에서 백그라운드 실행 안내창을 이미 띄웠는지 */
    private var bgShown = false
    private var bgDialog by mutableStateOf<BackgroundRunCheck.Dialog?>(null)
    /** 시작 안내 순서(백그라운드 실행 안내 → … → 배터리 예외)를 지키려고, 백그라운드 안내 판정이 끝난 뒤에 배터리 안내를 연다 */
    private var bgChecked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as App).graph
        onEntered()
        checkBackgroundRun()
        setContent {
            AppTheme {
                Surface { MainScreen(graph) }
                bgDialog?.let { d -> BackgroundRunDialog(d) { bgDialog = null } }
                if (bgChecked && bgDialog == null) BatteryPrompt()
            }
        }
    }

    /**
     * 백그라운드 실행 안내: 필터가 하나라도 있을 때만 판정한다.
     * 종료 기록 조회·시스템 속성 읽기가 있어 IO 스레드에서 한다.
     */
    private fun checkBackgroundRun() {
        val graph = (application as App).graph
        lifecycleScope.launch {
            try {
                if (graph.db.rules().count() > 0) {
                    val d = withContext(Dispatchers.IO) { BackgroundRunCheck.evaluate(this@MainActivity, System.currentTimeMillis(), bgShown) }
                    if (d != null) {
                        bgShown = true
                        bgDialog = d
                    }
                }
            } finally {
                bgChecked = true
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onEntered()
    }

    /**
     * 화면에 들어올 때: 서비스 시작 안내 알림(ID 1000)을 지우고,
     * 서비스 모드이고 앱 사용이 켜져 있으며 서비스가 떠 있지 않으면 서비스를 기동한다.
     */
    private fun onEntered() {
        Notifs.cancel(this, Notifs.ID_HINT)
        val graph = (application as App).graph
        lifecycleScope.launch {
            val s = graph.settings.snapshot()
            val worker = Dispatcher.isWorkerMode(s.useForegroundService)
            if (!ForwardingService.running && RunMode.shouldStartServiceOnLaunch(worker, s.appEnable)) {
                Dispatcher.startService(this@MainActivity)
            }
        }
    }
}

/**
 * 백그라운드 실행 안내창(제목 "백그라운드 실행 허용", 뒤로 가기·바깥 터치로 닫히지 않음, 설정 단추는 열 수 있는 화면이 있을 때만).
 * 외부 웹 페이지로 가는 "도움말" 단추는 두지 않는다.
 */
@Composable
private fun BackgroundRunDialog(d: BackgroundRunCheck.Dialog, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.main_bg_title)) },
        text = {
            Column {
                Text(d.message)
                d.settingsIntent?.let { intent ->
                    TextButton(onClick = {
                        onClose()
                        BackgroundRunCheck.openSettings(ctx, intent)
                    }) { Text(stringResource(R.string.main_bg_open)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
    )
}

/**
 * 배터리 최적화 예외 안내(메인 화면 진입 시): 예외가 아니면 안내 다이얼로그를 띄우고,
 * 확인하면 시스템의 예외 요청 화면으로 가며 취소하면 아무것도 하지 않는다.
 */
@Composable
private fun BatteryPrompt() {
    val ctx = LocalContext.current
    var show by rememberSaveable { mutableStateOf(!Perms.ignoringBatteryOptimizations(ctx)) }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.main_battery_title)) },
            text = { Text(stringResource(R.string.main_battery_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    show = false
                    Perms.requestIgnoreBatteryOptimizations(ctx)
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content,
    )
}
