package io.github.casimirkwon.smsforwarder.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.casimirkwon.smsforwarder.AppSettings
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.capture.NotificationCapture
import io.github.casimirkwon.smsforwarder.sms.SimUtil
import io.github.casimirkwon.smsforwarder.util.Perms
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(graph: Graph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsState(initial = AppSettings())
    var refresh by remember { mutableIntStateOf(0) }
    var serviceErrorDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {}

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        val workerMode = Dispatcher.isWorkerMode(settings.useForegroundService)
        Text(stringResource(R.string.status_forwarding, stringResource(if (settings.appEnable) R.string.common_on else R.string.common_off)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.status_mode, stringResource(if (workerMode) R.string.mode_worker else R.string.mode_service)), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text(stringResource(R.string.status_perm_title), style = MaterialTheme.typography.titleMedium)
        // refresh 를 읽어 재구성을 유도한다.
        val tick = refresh
        PermRow(stringResource(R.string.perm_sms_receive), Perms.hasAll(ctx, Perms.SMS_RECEIVE), tick)
        PermRow(stringResource(R.string.perm_sms_send), Perms.hasAll(ctx, Perms.SMS_SEND), tick)
        PermRow(stringResource(R.string.perm_phone_state), Perms.hasAll(ctx, Perms.PHONE_STATE), tick)
        PermRow(stringResource(R.string.perm_contacts), Perms.hasAll(ctx, Perms.CONTACTS), tick)
        PermRow(stringResource(R.string.perm_post_noti), Perms.hasPostNotifications(ctx), tick)
        PermRow(stringResource(R.string.perm_noti_access), Perms.hasNotificationAccess(ctx), tick)
        PermRow(stringResource(R.string.perm_battery), Perms.ignoringBatteryOptimizations(ctx), tick)
        Button(onClick = { launcher.launch(Perms.runtimeAll()) }) { Text(stringResource(R.string.status_btn_request_perms)) }
        Button(onClick = { Perms.requestIgnoreBatteryOptimizations(ctx) }) { Text(stringResource(R.string.status_btn_battery)) }
        HorizontalDivider()
        // 알림 전달: 알림 접근 허용 화면으로 이동하고, 리스너 상태(동작중/멈춤)를 보여 준다.
        Text(stringResource(R.string.status_noti_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.status_listener, stringResource(if (NotificationCapture.connected) R.string.listener_on else R.string.listener_off)), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { Perms.openNotificationAccessSettings(ctx) }) { Text(stringResource(R.string.status_btn_open_access)) }
        Button(onClick = {
            // 권한이 없거나 이미 동작 중이면 토스트, 권한은 있는데 서비스가 멈춰 있으면 안내 대화상자
            if (!Perms.hasNotificationAccess(ctx) || NotificationCapture.connected) {
                Toast.makeText(ctx, ctx.getString(R.string.status_listener_ok), Toast.LENGTH_LONG).show()
            } else {
                serviceErrorDialog = true
            }
        }) { Text(stringResource(R.string.status_btn_check_listener)) }
        HorizontalDivider()
        Text(stringResource(R.string.status_sim_title), style = MaterialTheme.typography.titleMedium)
        val sims = remember(tick) { SimUtil.activeSims(ctx) }
        if (sims.isEmpty()) Text(stringResource(R.string.status_sim_none), style = MaterialTheme.typography.bodySmall)
        sims.forEach {
            Text(stringResource(R.string.status_sim_row, it.slotIndex, it.number ?: it.displayName ?: "-", it.subscriptionId), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (serviceErrorDialog) {
        AlertDialog(
            onDismissRequest = { serviceErrorDialog = false },
            title = { Text(stringResource(R.string.status_listener_err_title)) },
            text = { Text(stringResource(R.string.status_listener_err_msg)) },
            confirmButton = { TextButton(onClick = { serviceErrorDialog = false; Perms.openNotificationAccessSettings(ctx) }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { serviceErrorDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PermRow(name: String, ok: Boolean, @Suppress("UNUSED_PARAMETER") tick: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name)
        Text(if (ok) stringResource(R.string.perm_granted) else stringResource(R.string.perm_needed), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}
