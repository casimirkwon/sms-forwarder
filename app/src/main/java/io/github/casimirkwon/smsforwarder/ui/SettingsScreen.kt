package io.github.casimirkwon.smsforwarder.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import io.github.casimirkwon.smsforwarder.core.DuplicateMode
import io.github.casimirkwon.smsforwarder.core.SimLabel
import io.github.casimirkwon.smsforwarder.core.SimMissing
import io.github.casimirkwon.smsforwarder.core.SpamBlock
import io.github.casimirkwon.smsforwarder.core.SpamRule
import io.github.casimirkwon.smsforwarder.db.SpamRuleEntity
import io.github.casimirkwon.smsforwarder.sms.ForwardingService
import io.github.casimirkwon.smsforwarder.sms.SimUtil
import io.github.casimirkwon.smsforwarder.util.Perms
import io.github.casimirkwon.smsforwarder.work.Dispatcher
import kotlinx.coroutines.launch

private enum class Dlg { SCHEDULE, DUAL_SIM, GAP, SPAM, SMS_LIMIT, SUMMARY, DUP_NOTI, SIMILAR, REPEATED, RETRY, KEEP }

/**
 * 설정 탭. 용도별로 다섯 묶음으로 나눈다: 기본 동작 → 보내기 안전장치 → 알림 가로채기 → 데이터·기록 → 정보.
 * 스위치는 줄에서 바로 켜고 끄고, 상세 값이 있는 항목은 줄을 눌러 대화상자로 연다.
 */
@Composable
fun SettingsScreen(graph: Graph, onOpenStatus: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by graph.settings.flow.collectAsState(initial = AppSettings())
    var dlg by remember { mutableStateOf<Dlg?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))

        // ── 기본 동작 ──
        PrefCategory(stringResource(R.string.set_cat_basic))
        PrefSwitch(stringResource(R.string.set_enable_title), stringResource(R.string.set_enable_desc), s.appEnable) { v -> scope.launch { graph.settings.setAppEnable(v) } }
        val workerMode = Dispatcher.isWorkerMode(s.useForegroundService)
        PrefSwitch(
            stringResource(R.string.set_fg_title),
            stringResource(R.string.set_fg_desc, stringResource(if (workerMode) R.string.mode_worker else R.string.mode_service)),
            s.useForegroundService,
        ) { v ->
            scope.launch {
                graph.settings.setUseForegroundService(v)
                if (v) {
                    Dispatcher.startService(ctx)
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.set_fg_off_toast), Toast.LENGTH_LONG).show()
                    ctx.startService(Intent(ctx, ForwardingService::class.java).setAction(ForwardingService.ACTION_STOP))
                }
            }
        }
        PrefSwitch(
            stringResource(R.string.set_hours),
            if (s.workTimeEnable && s.workStartTime >= 0 && s.workEndTime >= 0) stringResource(R.string.set_hours_on, hhmmLabel(s.workStartTime), hhmmLabel(s.workEndTime)) else stringResource(R.string.set_hours_off),
            s.workTimeEnable,
            onClick = { dlg = Dlg.SCHEDULE },
        ) { v ->
            scope.launch {
                if (!v) {
                    graph.settings.setWorkTime(false)
                } else {
                    graph.settings.setWorkTime(true, if (s.workStartTime >= 0) s.workStartTime else 0, if (s.workEndTime >= 0) s.workEndTime else 2359)
                    dlg = Dlg.SCHEDULE
                }
            }
        }
        PrefSwitch(stringResource(R.string.set_roaming), stringResource(R.string.set_roaming_desc), s.roamingEnable) { v -> scope.launch { graph.settings.setRoamingEnable(v) } }
        PrefItem(stringResource(R.string.set_sim), stringResource(R.string.set_sim_desc)) { dlg = Dlg.DUAL_SIM }
        PrefItem(stringResource(R.string.set_gap), stringResource(R.string.set_gap_value, s.messageDelayMin, s.messageDelayMax)) { dlg = Dlg.GAP }
        PrefSwitch(stringResource(R.string.set_wake), stringResource(R.string.set_wake_desc), s.sendWakeEnabled) { v -> scope.launch { graph.settings.setSendWakeEnabled(v) } }

        // ── 보내기 안전장치 ──
        PrefCategory(stringResource(R.string.set_cat_guard))
        PrefItem(stringResource(R.string.set_spam), stringResource(R.string.set_spam_desc)) { dlg = Dlg.SPAM }
        PrefItem(stringResource(R.string.set_similar), similarSummary(s)) { dlg = Dlg.SIMILAR }
        PrefItem(stringResource(R.string.set_repeat), if (s.preventRepeated) stringResource(R.string.set_repeat_on, s.repeatedMaxCount) else stringResource(R.string.common_off)) { dlg = Dlg.REPEATED }
        PrefItem(stringResource(R.string.set_cap), if (s.smsLimitEnabled) stringResource(R.string.set_cap_on, s.smsLimitCurrent, s.smsLimitCount) else stringResource(R.string.common_off)) { dlg = Dlg.SMS_LIMIT }
        PrefItem(stringResource(R.string.set_retry), if (s.retrySmsOnFailure) stringResource(R.string.set_retry_on, s.retryMax) else stringResource(R.string.common_off)) { dlg = Dlg.RETRY }
        PrefSwitch(stringResource(R.string.set_wait_net), stringResource(R.string.set_wait_net_desc), s.waitingNetwork) { v -> scope.launch { graph.settings.setWaitingNetwork(v) } }

        // ── 알림 가로채기 ──
        PrefCategory(stringResource(R.string.set_cat_intercept))
        PrefSwitch(
            stringResource(R.string.set_dup_noti),
            stringResource(R.string.set_dup_noti_desc),
            s.ignoreDuplicateNoti,
            onClick = { dlg = Dlg.DUP_NOTI },
        ) { v -> scope.launch { graph.settings.setIgnoreDuplicateNoti(v, s.checkDuplicateStrictly, s.summaryNotiOnlyText, s.ignoreIntervalMinutes) } }
        PrefItem(stringResource(R.string.set_summary), if (s.forwardSummaryAllApps) stringResource(R.string.set_summary_all) else stringResource(R.string.set_summary_default)) { dlg = Dlg.SUMMARY }

        // ── 데이터·기록 ──
        PrefCategory(stringResource(R.string.set_cat_data))
        PrefSwitch(stringResource(R.string.set_keep_log), stringResource(R.string.set_keep_log_desc), s.saveSendData) { v -> scope.launch { graph.settings.setSaveSendData(v) } }
        PrefItem(stringResource(R.string.set_keep_days), keepDaysLabel(s.historyRetentionDays)) { dlg = Dlg.KEEP }
        PrefSwitch(stringResource(R.string.set_result_notice), stringResource(R.string.set_result_notice_desc), s.resultNotification) { v -> scope.launch { graph.settings.setResultNotification(v) } }
        // 폰 교체·재설치 대비
        ConfigTransferItems(graph)

        // ── 정보 ──
        PrefCategory(stringResource(R.string.set_cat_about))
        PrefItem(stringResource(R.string.set_perm_title), stringResource(R.string.set_perm_desc), onOpenStatus)
        PrefInfo(stringResource(R.string.set_version), remember { appVersionName(ctx) })
    }

    when (dlg) {
        Dlg.SCHEDULE -> ScheduleDialog(s, graph) { dlg = null }
        Dlg.DUAL_SIM -> DualSimDialog(s, graph) { dlg = null }
        Dlg.GAP -> GapDialog(s, graph) { dlg = null }
        Dlg.SPAM -> SpamListDialog(graph) { dlg = null }
        Dlg.SMS_LIMIT -> SmsLimitDialog(s, graph) { dlg = null }
        Dlg.SUMMARY -> SummaryDialog(s, graph) { dlg = null }
        Dlg.DUP_NOTI -> DupNotiDialog(s, graph) { dlg = null }
        Dlg.SIMILAR -> SimilarDialog(s, graph) { dlg = null }
        Dlg.REPEATED -> RepeatedDialog(s, graph) { dlg = null }
        Dlg.RETRY -> RetryDialog(s, graph) { dlg = null }
        Dlg.KEEP -> KeepDaysDialog(s, graph) { dlg = null }
        null -> {}
    }
}

@Composable
private fun similarSummary(s: AppSettings) = when (DuplicateMode.of(s.avoidDuplicate, s.stopDuplicateAlways)) {
    DuplicateMode.OFF -> stringResource(R.string.common_off)
    DuplicateMode.ALWAYS -> stringResource(R.string.set_similar_always)
    else -> stringResource(R.string.set_similar_count, s.duplicateCount)
}

@Composable
private fun keepDaysLabel(days: Int) = if (days <= 1) stringResource(R.string.hist_keep_day) else stringResource(R.string.hist_keep_days, days)

private fun appVersionName(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()


// ───────────────────────── 대화상자 ─────────────────────────

@Composable
private fun ScheduleDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf(if (s.workStartTime >= 0) "%04d".format(s.workStartTime) else "0000") }
    var end by remember { mutableStateOf(if (s.workEndTime >= 0) "%04d".format(s.workEndTime) else "2359") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_hours)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dlg_hours_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(start, { start = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.dlg_hours_start)) }, singleLine = true)
                OutlinedTextField(end, { end = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.dlg_hours_end)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = start.toHhmm()
                val b = end.toHhmm()
                if (a != null && b != null) {
                    scope.launch { graph.settings.setWorkTime(s.workTimeEnable, a, b) }
                    onClose()
                }
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun DualSimDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sims = remember { SimUtil.activeSims(ctx) }
    // 저장된 SIM 이 기기에 없으면 기본값으로 바꿔 보여 주고 안내한다. 목록을 읽을 수 있을 때(전화 상태 권한)만 점검한다.
    val simPerm = remember { Perms.hasAll(ctx, Perms.PHONE_STATE) }
    val activeSlots = sims.map { it.slotIndex }
    val simSlots = if (simPerm) activeSlots.sorted() else listOf(0, 1)
    var inSlot by remember { mutableStateOf(if (simPerm) SimMissing.effective(s.defaultInSlot, activeSlots) else s.defaultInSlot) }
    var outSlot by remember { mutableStateOf(if (simPerm) SimMissing.effective(s.defaultOutSlot, activeSlots) else s.defaultOutSlot) }
    var simNotice by remember {
        mutableStateOf(
            if (!simPerm) null else SimMissing.joinedNames(
                listOfNotNull(
                    ctx.getString(R.string.sim_missing_in).takeIf { SimMissing.isMissing(s.defaultInSlot, activeSlots) },
                    ctx.getString(R.string.sim_missing_out).takeIf { SimMissing.isMissing(s.defaultOutSlot, activeSlots) },
                ),
            ),
        )
    }
    var name1 by remember { mutableStateOf(s.simName1) }
    var name2 by remember { mutableStateOf(s.simName2) }
    fun slotLabel(slot: Int): String {
        val sim = sims.firstOrNull { it.slotIndex == slot }
        return SimLabel.slot(slot, number = sim?.number, displayName = sim?.displayName)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_sim)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dlg_sim_intro), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dlg_sim_in), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.dlg_sim_in_desc), style = MaterialTheme.typography.bodySmall)
                RadioRow(stringResource(R.string.dlg_sim_all), inSlot == -1) { inSlot = -1 }
                simSlots.forEach { RadioRow(slotLabel(it), inSlot == it) { inSlot = it } }
                Text(stringResource(R.string.dlg_sim_out), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.dlg_sim_out_desc), style = MaterialTheme.typography.bodySmall)
                RadioRow(stringResource(R.string.dlg_sim_system), outSlot == -1) { outSlot = -1 }
                simSlots.forEach { RadioRow(slotLabel(it), outSlot == it) { outSlot = it } }
                Text(stringResource(R.string.dlg_sim_names), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.dlg_sim_names_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name1, { name1 = it }, label = { Text(stringResource(R.string.dlg_sim_name1)) }, singleLine = true)
                OutlinedTextField(name2, { name2 = it }, label = { Text(stringResource(R.string.dlg_sim_name2)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    graph.settings.setDefaultSlots(inSlot, outSlot)
                    graph.settings.setSimNames(name1.trim(), name2.trim())
                }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
    simNotice?.let { names ->
        AlertDialog(
            onDismissRequest = { simNotice = null },
            title = { Text(stringResource(R.string.dlg_sim_missing_title)) },
            text = { Text(stringResource(R.string.dlg_sim_missing_set, names)) },
            confirmButton = { TextButton(onClick = { simNotice = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun SpamListDialog(graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spam by graph.db.spam().observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<SpamRuleEntity?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_spam)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dlg_spam_body), style = MaterialTheme.typography.bodySmall)
                if (spam.isEmpty()) Text(stringResource(R.string.dlg_spam_empty), Modifier.padding(vertical = 8.dp))
                spam.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.data, Modifier.weight(1f))
                        Text(stringResource(SpamBlock.typeLabel(r.type)), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { editing = SpamRuleEntity(type = SpamRule.PHONE, data = "") }) { Text(stringResource(R.string.common_add)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
    )
    editing?.let { r ->
        SpamEditor(r, onDismiss = { editing = null }, onSave = { saved ->
            scope.launch { if (saved.id == 0L) graph.db.spam().insert(saved) else graph.db.spam().update(saved) }
            editing = null
        }, onDelete = if (r.id != 0L) ({ scope.launch { graph.db.spam().delete(r.id) }; editing = null }) else null)
    }
}

@Composable
private fun SpamEditor(initial: SpamRuleEntity, onDismiss: () -> Unit, onSave: (SpamRuleEntity) -> Unit, onDelete: (() -> Unit)?) {
    var type by remember { mutableStateOf(initial.type) }
    var data by remember { mutableStateOf(initial.data) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) stringResource(R.string.dlg_spam_add) else stringResource(R.string.dlg_spam_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(SpamRule.PHONE, SpamRule.NOTI_TITLE, SpamRule.BODY).forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { type = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(type == t, { type = t })
                        Text(stringResource(SpamBlock.typeLabel(t)))
                    }
                }
                OutlinedTextField(data, { data = it }, label = { Text(stringResource(R.string.dlg_spam_value)) })
            }
        },
        confirmButton = { TextButton(onClick = { if (data.isNotEmpty()) onSave(initial.copy(type = type, data = data)) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/**
 * SMS 제한: 스위치는 켜고 끌 때 바로 저장(한도는 저장된 값을 쓴다),
 * 한도 입력은 대화상자를 닫을 때 숫자일 때만 저장한다(범위 검사 없음). 이번달 사용량은 읽기만.
 */
@Composable
private fun SmsLimitDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var limit by remember { mutableStateOf(s.smsLimitCount.toString()) }
    fun close() {
        limit.toIntOrNull()?.let { v -> scope.launch { graph.settings.setSmsLimit(s.smsLimitEnabled, v) } }
        onClose()
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringResource(R.string.set_cap)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.set_cap), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = s.smsLimitEnabled, onCheckedChange = { on -> scope.launch { graph.settings.setSmsLimit(on, s.smsLimitCount) } })
                }
                Text(stringResource(R.string.dlg_cap_desc), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.dlg_cap_used))
                    Text("${s.smsLimitCurrent}")
                }
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(9) }, label = { Text(stringResource(R.string.dlg_cap_limit)) }, singleLine = true)
                Text(stringResource(R.string.dlg_cap_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.common_ok)) } },
    )
}

@Composable
private fun SummaryDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_summary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dlg_summary_body), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.dlg_summary_all), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = s.forwardSummaryAllApps, onCheckedChange = { on -> scope.launch { graph.settings.setForwardSummary(on, s.forwardSummaryPackages) } })
                }
                Button(onClick = { picker = true }) { Text(stringResource(R.string.dlg_summary_pick)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_ok)) } },
    )
    if (picker) {
        AppPickerDialog(
            initial = s.forwardSummaryPackages,
            title = stringResource(R.string.dlg_summary_pick_title),
            guide = stringResource(R.string.dlg_summary_pick_guide),
            onDismiss = { picker = false },
            onDone = { picked -> scope.launch { graph.settings.setForwardSummary(s.forwardSummaryAllApps, picked) }; picker = false },
        )
    }
}

@Composable
private fun DupNotiDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hours by remember { mutableStateOf((s.ignoreIntervalMinutes / 60).toString()) }
    var minutes by remember { mutableStateOf((s.ignoreIntervalMinutes % 60).toString()) }
    var strict by remember { mutableStateOf(s.checkDuplicateStrictly) }
    var summaryText by remember { mutableStateOf(s.summaryNotiOnlyText) }
    fun interval(): Long = ((hours.toLongOrNull() ?: 0) * 60 + (minutes.toLongOrNull() ?: 0)).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_dup_noti)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_dup_noti_desc), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dlg_dup_interval), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.dlg_dup_hours)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.dlg_dup_minutes)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                CheckRow(stringResource(R.string.dlg_dup_key), strict) { strict = it }
                CheckRow(stringResource(R.string.dlg_dup_summary_text), summaryText) { summaryText = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { graph.settings.setIgnoreDuplicateNoti(s.ignoreDuplicateNoti, strict, summaryText, interval()) }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 비슷한 메시지 전송 방지: 끄기 / 중복 메시지 항상 막기 / 1분 동안 N개의 메시지. */
@Composable
private fun SimilarDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(DuplicateMode.of(s.avoidDuplicate, s.stopDuplicateAlways)) }
    var count by remember { mutableStateOf(s.duplicateCount.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_similar)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dlg_similar_body), style = MaterialTheme.typography.bodySmall)
                RadioRow(stringResource(R.string.dlg_opt_off), mode == DuplicateMode.OFF) { mode = DuplicateMode.OFF }
                RadioRow(stringResource(R.string.set_similar_always), mode == DuplicateMode.ALWAYS) { mode = DuplicateMode.ALWAYS }
                Row(Modifier.fillMaxWidth().clickable { mode = DuplicateMode.COUNT }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == DuplicateMode.COUNT, { mode = DuplicateMode.COUNT })
                    Text(stringResource(R.string.dlg_similar_prefix))
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3); mode = DuplicateMode.COUNT }, singleLine = true, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.dlg_similar_suffix))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (avoid, always) = DuplicateMode.flags(mode)
                scope.launch { graph.settings.setDuplicate(avoid, always, (count.toIntOrNull() ?: s.duplicateCount).coerceAtLeast(1)) }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 반복 전송 방지: 끄기 / 1분 동안 N개의 메시지, 차단 목록. */
@Composable
private fun RepeatedDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val blocks by graph.db.repeatBlocks().observeAll().collectAsState(initial = emptyList())
    var on by remember { mutableStateOf(s.preventRepeated) }
    var count by remember { mutableStateOf(s.repeatedMaxCount.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_repeat)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dlg_repeat_body1), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.dlg_repeat_body2), style = MaterialTheme.typography.bodySmall)
                RadioRow(stringResource(R.string.dlg_opt_off), !on) { on = false }
                Row(Modifier.fillMaxWidth().clickable { on = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(on, { on = true })
                    Text(stringResource(R.string.dlg_repeat_prefix))
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3); on = true }, singleLine = true, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.dlg_repeat_suffix))
                }
                Text(stringResource(R.string.dlg_repeat_list), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (blocks.isEmpty()) Text(stringResource(R.string.dlg_repeat_empty), style = MaterialTheme.typography.bodySmall)
                blocks.forEach { b ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(b.recipient)
                        TextButton(onClick = { scope.launch { graph.db.repeatBlocks().delete(b.id) } }) { Text(stringResource(R.string.dlg_repeat_release)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { graph.settings.setRepeated(on, (count.toIntOrNull() ?: s.repeatedMaxCount).coerceAtLeast(1)) }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}


/** 전송 간격: 메시지를 보내는 사이에 두는 대기 시간(최소~최대 초, 무작위). 숫자는 확인 때 숫자일 때만 저장한다. */
@Composable
private fun GapDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dMin by remember { mutableStateOf(s.messageDelayMin.toString()) }
    var dMax by remember { mutableStateOf(s.messageDelayMax.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_gap)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dlg_gap_desc), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dMin, { dMin = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.dlg_gap_min)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(dMax, { dMax = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.dlg_gap_max)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = dMin.toIntOrNull()
                val b = dMax.toIntOrNull()
                if (a != null || b != null) scope.launch { graph.settings.setMessageDelay(a ?: s.messageDelayMin, b ?: s.messageDelayMax) }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 재시도: 실패한 SMS 를 다시 보낼지(스위치는 바로 저장)와 최대 횟수(확인 때 숫자일 때만 저장). */
@Composable
private fun RetryDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var retry by remember { mutableStateOf(s.retryMax.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_retry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dlg_retry_body), style = MaterialTheme.typography.bodySmall)
                PrefSwitch(stringResource(R.string.dlg_retry_on), null, s.retrySmsOnFailure) { v -> scope.launch { graph.settings.setRetrySmsOnFailure(v) } }
                OutlinedTextField(retry, { retry = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.dlg_retry_count)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                retry.toIntOrNull()?.let { v -> scope.launch { graph.settings.setRetryMax(v) } }
                onClose()
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 수신 이력 보관 기간(하루/3일/7일). 이력 탭 맨 위의 같은 항목과 같은 값을 바꾼다. */
@Composable
private fun KeepDaysDialog(s: AppSettings, graph: Graph, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_keep_days)) },
        text = {
            Column {
                listOf(1, 3, 7).forEach { d ->
                    RadioRow(keepDaysLabel(d), s.historyRetentionDays == d) {
                        scope.launch { graph.settings.setHistoryRetentionDays(d) }
                        onClose()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_close)) } },
    )
}
