package io.github.casimirkwon.smsforwarder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.casimirkwon.smsforwarder.AppSettings
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.DeliveryState
import io.github.casimirkwon.smsforwarder.core.DeliveryTally
import io.github.casimirkwon.smsforwarder.core.Reapply
import io.github.casimirkwon.smsforwarder.core.SendResult
import io.github.casimirkwon.smsforwarder.core.SimLabel
import io.github.casimirkwon.smsforwarder.core.StepCode
import io.github.casimirkwon.smsforwarder.db.ReceivedEntity
import io.github.casimirkwon.smsforwarder.sms.SimUtil
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val timeFmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
private fun fmt(t: Long) = timeFmt.format(Date(t))
@Composable
private fun retentionLabel(days: Int) = if (days <= 1) stringResource(R.string.hist_keep_day) else stringResource(R.string.hist_keep_days, days)

@Composable
fun HistoryScreen(graph: Graph) {
    val sends by graph.db.deliveries().observeRecent(100).collectAsState(initial = emptyList())
    val received by graph.db.received().observeRecent(100).collectAsState(initial = emptyList())
    val steps by graph.db.received().observeRecentSteps(500).collectAsState(initial = emptyList())
    val stepsByMsg = steps.groupBy { it.receivedId }
    val settings by graph.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val sims = remember { SimUtil.activeSims(ctx) }
    fun simLabel(slot: Int): String {
        val sim = sims.firstOrNull { it.slotIndex == slot }
        val custom = when (slot) { 0 -> settings.simName1; 1 -> settings.simName2; else -> "" }
        return SimLabel.slot(slot, custom, sim?.number, sim?.displayName)
    }
    // 수신 이력 항목의 "필터 다시 적용" 확인 대상(확인하면 ACTION_REAPPLY 를 보내고 토스트로 알린다)
    var reapplyTarget by remember { mutableStateOf<ReceivedEntity?>(null) }

    reapplyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { reapplyTarget = null },
            text = { Text(stringResource(R.string.hist_reapply_confirm)) },
            dismissButton = { TextButton(onClick = { reapplyTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    reapplyTarget = null
                    scope.launch { Dispatcher.dispatchReapply(ctx, graph, target.id) }
                    Toast.makeText(ctx, ctx.getString(R.string.hist_reapply_toast), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.hist_reapply)) }
            },
        )
    }

    LazyColumn(Modifier.padding(16.dp)) {
        // 기록 보관 기간: 24시간/3일/7일, 기본 24시간.
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Text(stringResource(R.string.hist_retention), style = MaterialTheme.typography.bodyMedium)
                Box {
                    TextButton(onClick = { menu = true }) { Text(retentionLabel(settings.historyRetentionDays)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(1, 3, 7).forEach { d ->
                            DropdownMenuItem(text = { Text(retentionLabel(d)) }, onClick = { scope.launch { graph.settings.setHistoryRetentionDays(d) }; menu = false })
                        }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.hist_deliveries), style = MaterialTheme.typography.titleLarge) }
        if (sends.isEmpty()) item { Text(stringResource(R.string.hist_deliveries_empty), Modifier.padding(vertical = 8.dp)) }
        items(sends, key = { "s${it.id}" }) { s ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("[${s.ruleName}] ${s.sender} → ${s.recipient}", style = MaterialTheme.typography.titleSmall)
                val whenLabel = if (s.status == SendResult.SCHEDULED) stringResource(R.string.hist_scheduled, fmt(s.sendTime)) else fmt(s.sendTime)
                Text("$whenLabel · ${SendResult.label(ctx, s.status)}", style = MaterialTheme.typography.bodySmall)
                val tally = DeliveryTally.decode(s.deliveryTally)
                val deliveredAt = fmt(s.deliveryTime)
                when (tally.state) {
                    DeliveryState.DELIVERED -> Text(stringResource(R.string.hist_dlv_done, deliveredAt), style = MaterialTheme.typography.bodySmall)
                    DeliveryState.FAILED -> Text(stringResource(R.string.hist_dlv_failed, deliveredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    DeliveryState.PARTIAL -> Text(stringResource(R.string.hist_dlv_partial, tally.ok.size, tally.total, deliveredAt), style = MaterialTheme.typography.bodySmall)
                    DeliveryState.NONE -> {}
                }
                Text(s.content, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                if (s.detail.isNotEmpty()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
        }
        item { Text(stringResource(R.string.hist_received), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        items(received, key = { "r${it.id}" }) { h ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                    Text(SimLabel.historyTitle(h, fmt(h.time), ::simLabel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (Reapply.canReapply(h)) {
                        var itemMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { itemMenu = true }) { Text("⋮") }
                            DropdownMenu(expanded = itemMenu, onDismissRequest = { itemMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.hist_reapply)) }, onClick = { itemMenu = false; reapplyTarget = h })
                            }
                        }
                    }
                }
                Text(h.body, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                stepsByMsg[h.id].orEmpty().sortedBy { it.id }.forEach {
                    Text(stringResource(R.string.hist_step, it.ruleName, StepCode.describe(ctx, it.code)), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}
