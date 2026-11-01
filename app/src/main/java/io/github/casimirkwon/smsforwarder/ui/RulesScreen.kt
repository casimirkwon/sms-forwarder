package io.github.casimirkwon.smsforwarder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.ConditionText
import io.github.casimirkwon.smsforwarder.core.DelayPolicy
import io.github.casimirkwon.smsforwarder.core.Conditions
import io.github.casimirkwon.smsforwarder.core.NotiCompose
import io.github.casimirkwon.smsforwarder.core.NotiRule
import io.github.casimirkwon.smsforwarder.core.NotiPart
import io.github.casimirkwon.smsforwarder.core.RuleForm
import io.github.casimirkwon.smsforwarder.core.ReplaceWord
import io.github.casimirkwon.smsforwarder.core.SimLabel
import io.github.casimirkwon.smsforwarder.core.SimMissing
import io.github.casimirkwon.smsforwarder.core.ActiveHours
import io.github.casimirkwon.smsforwarder.core.Template
import io.github.casimirkwon.smsforwarder.sms.SimUtil
import io.github.casimirkwon.smsforwarder.db.RuleEntity
import io.github.casimirkwon.smsforwarder.db.RuleKind
import io.github.casimirkwon.smsforwarder.util.Perms
import kotlinx.coroutines.launch

private const val DEFAULT_TEMPLATE = "발신: {보낸이}({연락처})\n{본문}"

/** 새 알림 규칙(v4)의 기본 전달 형식(제목은 보낼 내용 구성에 들어가므로 `{본문}` 만). 구형(v3 이하) 규칙의 기본은 `{보낸이}\n{본문}` 였다. */
private const val DEFAULT_NOTI_TEMPLATE = "{본문}"

/** 새 알림 규칙의 규칙 방식(보낼 내용 구성 방식). */
private const val NEW_NOTI_LEVEL = 4

@Composable
private fun partLabel(p: NotiPart) = stringResource(
    when (p) {
        NotiPart.TITLE -> R.string.part_title
        NotiPart.EXTRA -> R.string.part_extra
        NotiPart.TEXT -> R.string.part_text
        NotiPart.SUB -> R.string.part_sub
        NotiPart.BIG -> R.string.part_big
        NotiPart.MESSAGE -> R.string.part_message
    },
)

@Composable
fun RulesScreen(graph: Graph) {
    val scope = rememberCoroutineScope()
    val rules by graph.db.rules().observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<RuleEntity?>(null) }
    var needAccess by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val nextSmsName = stringResource(R.string.rules_default_sms, rules.size + 1)
    val nextNotiName = stringResource(R.string.rules_default_noti, rules.size + 1)

    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = RuleEntity(name = nextSmsName, template = DEFAULT_TEMPLATE) }) { Text(stringResource(R.string.rules_add_sms)) }
            // 새 알림 규칙의 기본값: 켜짐·결과 알림·기록 저장 + 알림 규칙(512) + 빈 알림 보내지 않기(16777216). 규칙 버전 "4", 보낼 내용은 "자동".
            Button(onClick = {
                editing = RuleEntity(
                    name = nextNotiName,
                    template = DEFAULT_NOTI_TEMPLATE,
                    kind = RuleKind.NOTIFICATION,
                    skipEmptyNoti = true,
                    level = NEW_NOTI_LEVEL,
                )
            }) { Text(stringResource(R.string.rules_add_noti)) }
        }
        if (rules.isEmpty()) Text(stringResource(R.string.rules_empty), Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.padding(top = 8.dp)) {
            items(rules, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = f }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        val isNoti = f.isNotification
                        Text(if (isNoti) stringResource(R.string.rules_tag_noti, f.name) else f.name, style = MaterialTheme.typography.titleMedium)
                        val from = if (isNoti) {
                            val apps = NotiRule.parsePackages(f.apps)
                            val appLabel = if (apps.isEmpty()) stringResource(R.string.rules_all_apps) else stringResource(R.string.rules_apps_count, apps.size)
                            if (f.isV4) appLabel
                            else "$appLabel · " + ConditionText.toLines(f.senderCond).replace("\n", " · ").ifEmpty { stringResource(R.string.rules_all_titles) }
                        } else {
                            ConditionText.toLines(f.senderCond).replace("\n", " · ").ifEmpty { stringResource(R.string.rules_all_senders) }
                        }
                        val to = parseDest(f).joinToString(", ").ifEmpty { stringResource(R.string.rules_no_dest) }
                        val delayHint = if (DelayPolicy.SHOW_DELAY_UI && f.delayEnabled && f.delayMinutes > 0) stringResource(R.string.rules_delay_hint, delayLabel(f.delayMinutes)) else ""
                        Text("$from → $to$delayHint", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = f.enabled, onCheckedChange = { on ->
                        scope.launch {
                            graph.db.rules().update(f.copy(enabled = on))
                        }
                    })
                }
                HorizontalDivider()
            }
        }
    }

    if (needAccess) {
        AlertDialog(
            onDismissRequest = { needAccess = false },
            title = { Text(stringResource(R.string.rules_access_title)) },
            text = { Text(stringResource(R.string.rules_access_msg)) },
            confirmButton = { TextButton(onClick = { needAccess = false; Perms.openNotificationAccessSettings(ctx) }) { Text(stringResource(R.string.rules_access_go)) } },
            dismissButton = { TextButton(onClick = { needAccess = false }) { Text(stringResource(R.string.rules_access_later)) } },
        )
    }

    editing?.let { f ->
        RuleEditor(
            initial = f,
            onDismiss = { editing = null },
            onSave = { saved ->
                scope.launch {
                    if (saved.id == 0L) graph.db.rules().insert(saved.copy(position = graph.db.rules().nextOrder()))
                    else graph.db.rules().update(saved)
                }
                // 알림 규칙을 저장했는데 알림 접근이 없으면 허용 화면으로 안내한다.
                if (saved.isNotification && !Perms.hasNotificationAccess(ctx)) needAccess = true
                editing = null
            },
            onDelete = if (f.id != 0L) ({
                scope.launch { graph.db.rules().delete(f) }
                editing = null
            }) else null,
        )
    }
}

private fun parseDest(f: RuleEntity) = io.github.casimirkwon.smsforwarder.core.parseDestinations(f.recipients)

/**
 * 규칙 편집: 전체 화면에 접이식 카드를 위에서 아래로 놓는다(기본 정보 → 보낼 곳 → 무엇을 전달할지 → 본문 만들기 → 보내는 SIM·시간 → 결과 처리).
 * 접힌 카드는 현재 값을 한 줄로 보여 준다. 입력 상태와 저장 검증은 [RuleForm] 이 맡는다(순수 코드, 단위 시험 대상).
 */
@Composable
private fun RuleEditor(initial: RuleEntity, onDismiss: () -> Unit, onSave: (RuleEntity) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    val isNoti = initial.isNotification
    // 알림 규칙 방식(level 4): 보낼 내용을 자동 / 직접 선택하고, 직접 선택 목록의 순서가 곧 이어 붙이는 순서
    val isV4 = initial.isV4
    // 저장된 SIM 이 기기에 없으면 기본값으로 바꿔 보여 주고 안내한다. SIM 목록을 읽을 수 있을 때만(전화 상태 권한), 문자 규칙만.
    val sims = remember { SimUtil.activeSims(ctx) }
    val simPerm = remember { Perms.hasAll(ctx, Perms.PHONE_STATE) }
    val activeSlots = sims.map { it.slotIndex }
    val simCheck = simPerm && !isNoti
    var form by remember {
        val base = RuleForm.of(initial)
        mutableStateOf(
            if (simCheck) base.copy(simIn = SimMissing.effective(initial.simInSlot, activeSlots), simOut = SimMissing.effective(initial.simOutSlot, activeSlots)) else base,
        )
    }
    var templateField by remember { mutableStateOf(TextFieldValue(form.template)) }
    var replaceField by remember { mutableStateOf(TextFieldValue(form.replaceLines)) }
    var simNotice by remember {
        mutableStateOf(
            if (!simCheck) null else SimMissing.joinedNames(
                listOfNotNull(
                    ctx.getString(R.string.sim_missing_in).takeIf { SimMissing.isMissing(initial.simInSlot, activeSlots) },
                    ctx.getString(R.string.sim_missing_out).takeIf { SimMissing.isMissing(initial.simOutSlot, activeSlots) },
                ),
            ),
        )
    }
    var scheduleDialog by remember { mutableStateOf(false) }
    var delayDialog by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    var basicOpen by rememberSaveable { mutableStateOf(true) }
    var destOpen by rememberSaveable { mutableStateOf(true) }
    var whatOpen by rememberSaveable { mutableStateOf(true) }
    var bodyOpen by rememberSaveable { mutableStateOf(true) }
    var whenOpen by rememberSaveable { mutableStateOf(false) }
    var resultOpen by rememberSaveable { mutableStateOf(false) }
    // 목록은 현재 활성 SIM 만. 권한이 없어 읽지 못하면 SIM 1·2 를 그대로 둔다.
    val simSlots = if (simPerm) activeSlots.sorted() else listOf(0, 1)
    fun simLabel(slot: Int): String {
        val sim = sims.firstOrNull { it.slotIndex == slot }
        return SimLabel.slot(slot, number = sim?.number, displayName = sim?.displayName)
    }
    val defaultName = stringResource(R.string.rule_default_name)

    fun trySave() {
        val current = form.copy(template = templateField.text, replaceLines = replaceField.text)
        when (val r = current.toRule(initial, defaultName)) {
            is RuleForm.Result.Ok -> onSave(r.rule)
            RuleForm.Result.NoRecipient -> {
                destOpen = true
                Toast.makeText(ctx, ctx.getString(R.string.rule_toast_no_dest), Toast.LENGTH_LONG).show()
            }
            RuleForm.Result.BadReplace -> {
                bodyOpen = true
                Toast.makeText(ctx, ctx.getString(R.string.rule_toast_bad_replace), Toast.LENGTH_LONG).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Text(
                        stringResource(
                            when {
                                isNoti && initial.id == 0L -> R.string.rule_title_add_noti
                                isNoti -> R.string.rule_title_edit_noti
                                initial.id == 0L -> R.string.rule_title_add_sms
                                else -> R.string.rule_title_edit_sms
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::trySave) { Text(stringResource(R.string.common_save)) }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 1. 기본 정보
                    RuleCard(stringResource(R.string.rule_card_basic), form.name.trim().ifEmpty { defaultName }, basicOpen, { basicOpen = it }) {
                        OutlinedTextField(form.name, { form = form.copy(name = it) }, label = { Text(stringResource(R.string.rule_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }

                    // 2. 보낼 곳
                    RuleCard(stringResource(R.string.rule_card_dest), stringResource(R.string.rule_sum_dest, form.recipients.lines().count { it.isNotBlank() }), destOpen, { destOpen = it }) {
                        OutlinedTextField(
                            form.recipients, { form = form.copy(recipients = it) }, label = { Text(stringResource(R.string.rule_recipients)) },
                            supportingText = { Text(stringResource(R.string.rule_recipients_help)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 3. 무엇을 전달할지: 대상 앱(알림) → 전달 대상 → 조건 → 인증·보안 메시지
                    val appCount = NotiRule.parsePackages(form.apps).size
                    val whatSummary = listOfNotNull(
                        if (isNoti) (if (appCount == 0) stringResource(R.string.rules_all_apps) else stringResource(R.string.rules_apps_count, appCount)) else null,
                        stringResource(if (form.matchAll) R.string.rule_target_all else R.string.rule_target_filter),
                    ).joinToString(" · ")
                    RuleCard(stringResource(R.string.rule_card_what), whatSummary, whatOpen, { whatOpen = it }) {
                        if (isNoti) {
                            // 설치된 앱 중에서 고른다. 아무것도 고르지 않으면 모든 앱.
                            SectionTitle(stringResource(R.string.rule_sec_apps))
                            Column(Modifier.fillMaxWidth().clickable { appPicker = true }.padding(vertical = 4.dp)) {
                                Text(if (appCount == 0) stringResource(R.string.rule_apps_all) else stringResource(R.string.rule_apps_selected, appCount), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.rule_apps_tap), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        SectionTitle(stringResource(R.string.rule_sec_target))
                        RadioRow(stringResource(R.string.rule_target_all), form.matchAll) { form = form.copy(matchAll = true) }
                        RadioRow(stringResource(R.string.rule_target_filter), !form.matchAll) { form = form.copy(matchAll = false) }
                        if (!form.matchAll) {
                            PrefSwitch(stringResource(R.string.rule_ignore_case), null, form.ignoreCase) { form = form.copy(ignoreCase = it) }
                            PrefSwitch(stringResource(R.string.rule_wildcard), null, form.wildcard) { form = form.copy(wildcard = it) }
                            if (isNoti) {
                                // level 4 규칙은 제목(발신자) 조건을 보지 않고 보낼 내용 구성으로 만든 본문에만 조건을 건다
                                if (!isV4) {
                                    OutlinedTextField(form.senderLines, { form = form.copy(senderLines = it) }, label = { Text(stringResource(R.string.rule_cond_noti_title)) }, supportingText = { Text(stringResource(R.string.rule_cond_noti_title_help)) }, modifier = Modifier.fillMaxWidth())
                                }
                                OutlinedTextField(form.bodyLines, { form = form.copy(bodyLines = it) }, label = { Text(stringResource(R.string.rule_cond_body)) }, modifier = Modifier.fillMaxWidth())
                            } else {
                                OutlinedTextField(form.senderLines, { form = form.copy(senderLines = it) }, label = { Text(stringResource(R.string.rule_cond_sender)) }, supportingText = { Text(stringResource(R.string.rule_cond_sender_help)) }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(form.bodyLines, { form = form.copy(bodyLines = it) }, label = { Text(stringResource(R.string.rule_cond_body)) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        PrefSwitch(stringResource(R.string.rule_allow_otp), stringResource(R.string.rule_allow_otp_desc), form.allowOtp) { form = form.copy(allowOtp = it) }
                    }

                    // 4. 본문 만들기: 알림 구성(알림 규칙) → 전달 형식과 토큰 버튼 → 단어 바꾸기
                    val bodySummary = templateField.text.lineSequence().firstOrNull { it.isNotBlank() } ?: stringResource(R.string.rule_sum_format_empty)
                    RuleCard(stringResource(R.string.rule_card_body), bodySummary, bodyOpen, { bodyOpen = it }) {
                        if (isNoti && isV4) {
                            SectionTitle(stringResource(R.string.rule_sec_content))
                            Text(stringResource(R.string.rule_content_desc), style = MaterialTheme.typography.bodySmall)
                            RadioRow(stringResource(R.string.rule_content_auto), form.compAuto) { form = form.copy(compAuto = true) }
                            if (form.compAuto) {
                                Text(stringResource(R.string.rule_content_auto_desc), style = MaterialTheme.typography.bodySmall)
                            }
                            RadioRow(stringResource(R.string.rule_content_manual), !form.compAuto) {
                                if (form.compAuto) {
                                    form = form.copy(
                                        compAuto = false,
                                        compParts = form.compParts.ifEmpty { NotiCompose.parse(NotiCompose.DEFAULT_DIRECT) },
                                    )
                                }
                            }
                            if (!form.compAuto) {
                                Text(stringResource(R.string.rule_content_manual_desc), style = MaterialTheme.typography.bodySmall)
                                val parts = form.compParts
                                parts.forEachIndexed { i, part ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) { CheckRow(partLabel(part), true) { form = form.copy(compParts = parts - part) } }
                                        TextButton(enabled = i > 0, onClick = { form = form.copy(compParts = parts.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }) { Text("▲") }
                                        TextButton(enabled = i < parts.size - 1, onClick = { form = form.copy(compParts = parts.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }) { Text("▼") }
                                    }
                                }
                                NotiPart.entries.filter { it !in parts }.forEach { part ->
                                    CheckRow(partLabel(part), false) { form = form.copy(compParts = parts + part) }
                                }
                            }
                        } else if (isNoti) {
                            SectionTitle(stringResource(R.string.rule_sec_noti_body))
                            Text(stringResource(R.string.rule_noti_body_desc), style = MaterialTheme.typography.bodySmall)
                            val auto = !(form.useText || form.useSub || form.useBig || form.useMessage)
                            CheckRow(stringResource(R.string.rule_noti_body_auto), auto) { form = form.copy(useText = false, useSub = false, useBig = false, useMessage = false) }
                            CheckRow(stringResource(R.string.part_text), form.useText) { form = form.copy(useText = it) }
                            CheckRow(stringResource(R.string.part_sub), form.useSub) { form = form.copy(useSub = it) }
                            CheckRow(stringResource(R.string.part_big), form.useBig) { form = form.copy(useBig = it) }
                            CheckRow(stringResource(R.string.part_message), form.useMessage) { form = form.copy(useMessage = it) }
                            PrefSwitch(stringResource(R.string.rule_skip_empty), null, form.skipEmpty) { form = form.copy(skipEmpty = it) }
                        }

                        SectionTitle(stringResource(R.string.rule_sec_format))
                        OutlinedTextField(
                            templateField, { templateField = it }, label = { Text(stringResource(R.string.rule_format_label)) },
                            supportingText = { Text(stringResource(R.string.rule_format_help)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (if (isNoti) Template.NOTI_TOKENS else Template.SMS_TOKENS).forEach { token ->
                                AssistChip(onClick = {
                                    val sel = templateField.selection
                                    templateField = TextFieldValue(templateField.text.replaceRange(sel.min, sel.max, token), TextRange(sel.min + token.length))
                                }, label = { Text(token) })
                            }
                        }
                        Text(stringResource(if (isNoti) R.string.rule_tokens_noti else R.string.rule_tokens_sms), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.rule_tokens_time), style = MaterialTheme.typography.bodySmall)

                        OutlinedTextField(replaceField, { replaceField = it }, label = { Text(stringResource(R.string.rule_replace_label)) }, supportingText = { Text(stringResource(R.string.rule_replace_help)) }, modifier = Modifier.fillMaxWidth())
                        // 커서 위치에 \n 을 넣는 버튼
                        AssistChip(onClick = {
                            val sel = replaceField.selection
                            replaceField = TextFieldValue(replaceField.text.replaceRange(sel.min, sel.max, "\\n"), TextRange(sel.min + 2))
                        }, label = { Text(stringResource(R.string.rule_replace_newline)) })
                        CheckRow(stringResource(R.string.rule_regex), form.regexReplace) { form = form.copy(regexReplace = it) }
                        CheckRow(stringResource(R.string.rule_remove_empty), form.removeEmptyLine) { form = form.copy(removeEmptyLine = it) }
                    }

                    // 5. 보내는 SIM·시간: SIM(문자 규칙만) → 전달 시간대 → (숨김) 지연 전달
                    val sched = ActiveHours.parse(form.schedule)
                    val schedLabel = if (sched.always) stringResource(R.string.rule_schedule_always) else scheduleSummary(sched)
                    val simCustom = !isNoti && (form.simIn >= 0 || form.simOut >= 0)
                    val whenSummary = if (simCustom) schedLabel + " · " + stringResource(R.string.rule_sum_sim_set) else schedLabel
                    RuleCard(stringResource(R.string.rule_card_when), whenSummary, whenOpen, { whenOpen = it }) {
                        // 알림에는 SIM 이 없다
                        if (!isNoti) {
                            SimPicker(stringResource(R.string.rule_sim_in), stringResource(R.string.rule_sim_in_help), form.simIn, stringResource(R.string.rule_sim_default), simSlots, ::simLabel) { form = form.copy(simIn = it) }
                            SimPicker(stringResource(R.string.rule_sim_out), stringResource(R.string.rule_sim_out_help), form.simOut, stringResource(R.string.rule_sim_default), simSlots, ::simLabel) { form = form.copy(simOut = it) }
                        }
                        PrefSwitch(stringResource(R.string.rule_schedule), schedLabel, !sched.always, onClick = { scheduleDialog = true }) { on ->
                            form = form.copy(schedule = ActiveHours.build(sched.copy(always = !on)))
                            if (on) scheduleDialog = true
                        }
                        if (DelayPolicy.SHOW_DELAY_UI) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).clickable { if (form.delayOn) delayDialog = true }) {
                                    Text(stringResource(R.string.rule_delay))
                                    if (form.delayOn) Text(stringResource(R.string.rule_delay_value, delayLabel(form.delayMinutes)), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = form.delayOn, onCheckedChange = { on -> if (on) delayDialog = true else form = form.copy(delayOn = false) })
                            }
                        }
                    }

                    // 6. 결과 처리
                    val resultSummary = stringResource(
                        R.string.rule_sum_result,
                        stringResource(if (form.saveHistory) R.string.common_on else R.string.common_off),
                        stringResource(if (form.notifyResult) R.string.common_on else R.string.common_off),
                    )
                    RuleCard(stringResource(R.string.rule_card_result), resultSummary, resultOpen, { resultOpen = it }) {
                        PrefSwitch(stringResource(R.string.rule_save_log), null, form.saveHistory) { form = form.copy(saveHistory = it) }
                        PrefSwitch(stringResource(R.string.rule_notify_result), null, form.notifyResult) { form = form.copy(notifyResult = it) }
                    }

                    if (onDelete != null) {
                        TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    simNotice?.let { names ->
        AlertDialog(
            onDismissRequest = { simNotice = null },
            title = { Text(stringResource(R.string.dlg_sim_missing_title)) },
            text = { Text(stringResource(R.string.rule_sim_missing_set, names)) },
            confirmButton = { TextButton(onClick = { simNotice = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    if (appPicker) {
        AppPickerDialog(
            initial = form.apps,
            title = stringResource(R.string.rule_picker_title),
            guide = stringResource(R.string.rule_picker_guide),
            onDismiss = { appPicker = false },
            onDone = { form = form.copy(apps = it); appPicker = false },
        )
    }

    if (scheduleDialog) {
        RuleScheduleDialog(
            initial = ActiveHours.parse(form.schedule),
            onCancel = {
                // 시간대를 처음 켜다가 취소하면 다시 "항상"으로 둔다
                if (ActiveHours.parse(initial.schedule).always) form = form.copy(schedule = initial.schedule)
                scheduleDialog = false
            },
            onOk = { form = form.copy(schedule = ActiveHours.build(it)); scheduleDialog = false },
        )
    }

    if (DelayPolicy.SHOW_DELAY_UI && delayDialog) {
        DelayDialog(
            initialMinutes = form.delayMinutes,
            onCancel = { form = form.copy(delayOn = false); delayDialog = false },
            onOk = { m -> form = form.copy(delayMinutes = m, delayOn = m > 0); delayDialog = false },
        )
    }
}

/** 접었다 펼 수 있는 카드. 접으면 제목 아래에 현재 값을 한 줄([summary])로 보여 준다. */
@Composable
private fun RuleCard(title: String, summary: String, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!expanded) Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val desc = stringResource(if (expanded) R.string.rule_card_close else R.string.rule_card_open)
                Text(if (expanded) "▲" else "▼", Modifier.semantics { contentDescription = desc }.padding(start = 8.dp))
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}


private fun slotText(s: ActiveHours.Slot) = "${hhmmLabel(s.start)}~${hhmmLabel(s.end)}"

@Composable
private fun scheduleSummary(sc: ActiveHours.Schedule): String {
    val dayNames = stringArrayResource(R.array.weekday_short)
    val ctx = LocalContext.current
    return if (sc.daily.on) stringResource(R.string.sched_daily, slotText(sc.daily))
    else sc.days.withIndex().filter { it.value.on }.joinToString(" · ") { ctx.getString(R.string.sched_day, dayNames[it.index], slotText(it.value)) }.ifEmpty { stringResource(R.string.sched_none) }
}

/** 듀얼 SIM 선택(스피너): 기본 설정 따름 / SIM 1 / SIM 2. */
@Composable
private fun SimPicker(title: String, help: String, value: Int, defaultLabel: String, slots: List<Int>, label: (Int) -> String, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(help, style = MaterialTheme.typography.bodySmall)
        Box {
            TextButton(onClick = { open = true }) { Text(if (value < 0) defaultLabel else label(value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { onChange(-1); open = false })
                slots.forEach { slot -> DropdownMenuItem(text = { Text(label(slot)) }, onClick = { onChange(slot); open = false }) }
            }
        }
    }
}

/**
 * 규칙 일정: 매일 같은 시간대, 또는 요일별 시간대. 시각은 HHMM. 시작이 끝보다 늦으면 자정을 넘기는 구간.
 * 문자열 형식은 [ActiveHours] 참조.
 */
@Composable
private fun RuleScheduleDialog(initial: ActiveHours.Schedule, onCancel: () -> Unit, onOk: (ActiveHours.Schedule) -> Unit) {
    val ctx = LocalContext.current
    fun pad(v: Int) = "%04d".format(v)
    val dayNames = stringArrayResource(R.array.weekday_short)
    // 첫 사용이면(모두 0~0) 매일 0000~2359 를 기본으로 보여 준다
    val dailyInit = if (!initial.daily.on && initial.days.none { it.on } && initial.daily.start == 0 && initial.daily.end == 0) ActiveHours.Slot(true, 0, 2359) else initial.daily
    var dailyOn by remember { mutableStateOf(dailyInit.on) }
    var dStart by remember { mutableStateOf(pad(dailyInit.start)) }
    var dEnd by remember { mutableStateOf(pad(dailyInit.end)) }
    val dayOn = remember { initial.days.map { it.on }.toMutableStateList() }
    val dayStart = remember { initial.days.map { pad(it.start) }.toMutableStateList() }
    val dayEnd = remember { initial.days.map { pad(it.end) }.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rule_schedule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sched_body), style = MaterialTheme.typography.bodySmall)
                CheckRow(stringResource(R.string.sched_daily_check), dailyOn) { dailyOn = it }
                if (dailyOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(dStart, { dStart = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.sched_start)) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(dEnd, { dEnd = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.sched_end)) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    dayNames.forEachIndexed { i, name ->
                        CheckRow(name, dayOn[i]) { dayOn[i] = it }
                        if (dayOn[i]) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(dayStart[i], { dayStart[i] = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.sched_start)) }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(dayEnd[i], { dayEnd[i] = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.sched_end)) }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun slot(on: Boolean, s: String, e: String): ActiveHours.Slot? {
                    if (!on) return ActiveHours.Slot(false, s.toIntOrNull() ?: 0, e.toIntOrNull() ?: 0)
                    val a = s.toHhmm() ?: return null
                    val b = e.toHhmm() ?: return null
                    return ActiveHours.Slot(true, a, b)
                }
                val daily = slot(dailyOn, dStart, dEnd)
                val days = dayNames.indices.map { i -> slot(!dailyOn && dayOn[i], dayStart[i], dayEnd[i]) }
                if (daily == null || days.any { it == null }) {
                    Toast.makeText(ctx, ctx.getString(R.string.sched_bad), Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                onOk(ActiveHours.Schedule(always = false, daily = daily, days = days.map { it!! }))
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** 지연 시간 선택(시 0~24, 분 0~59). 합이 0 이하이면 지연발송을 끈다. */
@Composable
private fun DelayDialog(initialMinutes: Long, onCancel: () -> Unit, onOk: (Long) -> Unit) {
    var hours by remember { mutableStateOf((initialMinutes / 60).coerceIn(0, 24).toString()) }
    var minutes by remember { mutableStateOf((initialMinutes % 60).toString()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.delay_title)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.delay_hours_field)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, label = { Text(stringResource(R.string.delay_minutes_field)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = (hours.toIntOrNull() ?: 0).coerceIn(0, 24)
                val m = (minutes.toIntOrNull() ?: 0).coerceIn(0, 59)
                onOk(DelayPolicy.minutesOf(h, m))
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun delayLabel(minutes: Long): String {
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt()
    return when {
        h > 0 && m > 0 -> stringResource(R.string.delay_both, h, m)
        h > 0 -> stringResource(R.string.delay_hours, h)
        else -> stringResource(R.string.delay_minutes, m)
    }
}
