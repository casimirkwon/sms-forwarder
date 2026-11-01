package io.github.casimirkwon.smsforwarder.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.ExportImport
import io.github.casimirkwon.smsforwarder.util.ConfigTransfer
import io.github.casimirkwon.smsforwarder.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 설정 화면의 "규칙·설정 내보내기 / 가져오기"(폰 교체·재설치 대비).
 * 저장소 접근 프레임워크(SAF)로 JSON 파일을 만들고 읽는다. 인터넷 권한은 쓰지 않는다.
 */
@Composable
fun ConfigTransferItems(graph: Graph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfer = remember { ConfigTransfer(ctx, graph) }
    var preview by remember { mutableStateOf<ExportImport.Data?>(null) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var applySettings by remember { mutableStateOf(true) }
    var existingCount by remember { mutableStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val json = transfer.exportJson()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
            Toast.makeText(ctx, if (result.isSuccess) ctx.getString(R.string.xfer_export_ok) else ctx.getString(R.string.xfer_export_fail, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                        val bytes = ins.readNBytes(ExportImport.MAX_BYTES + 1)
                        if (bytes.size > ExportImport.MAX_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                rejected = ctx.getString(R.string.xfer_read_fail)
                return@launch
            }
            when (val p = ExportImport.parse(text)) {
                is ExportImport.Parsed.Rejected -> rejected = p.reason
                is ExportImport.Parsed.Ok -> {
                    existingCount = graph.db.rules().count()
                    applySettings = true
                    preview = p.data
                }
            }
        }
    }

    PrefItem(stringResource(R.string.xfer_export_title), stringResource(R.string.xfer_export_desc)) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportLauncher.launch("sms-forwarder-export-$stamp.json")
    }
    PrefItem(stringResource(R.string.xfer_import_title), stringResource(R.string.xfer_import_desc)) {
        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    rejected?.let { reason ->
        AlertDialog(
            onDismissRequest = { rejected = null },
            title = { Text(stringResource(R.string.xfer_reject_title)) },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { rejected = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    preview?.let { d ->
        fun finish(mode: ConfigTransfer.Mode) {
            preview = null
            confirmReplace = false
            scope.launch {
                val result = runCatching { transfer.apply(d, mode, applySettings) }
                if (result.isSuccess) {
                    Toast.makeText(ctx, ctx.getString(R.string.xfer_done, result.getOrNull() ?: 0), Toast.LENGTH_LONG).show()
                    // 알림 규칙이 켜져 있는데 알림 접근이 없으면 알려 준다(규칙 저장 때와 같은 안내)
                    if (d.rules.any { it.isNotification && it.enabled } && !Perms.hasNotificationAccess(ctx)) {
                        Toast.makeText(ctx, ctx.getString(R.string.xfer_need_access), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.xfer_import_fail, result.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                }
            }
        }
        if (confirmReplace) {
            AlertDialog(
                onDismissRequest = { confirmReplace = false },
                title = { Text(stringResource(R.string.xfer_replace_all)) },
                text = { Text(stringResource(R.string.xfer_replace_msg, existingCount, d.rules.size)) },
                confirmButton = { TextButton(onClick = { finish(ConfigTransfer.Mode.REPLACE) }) { Text(stringResource(R.string.xfer_replace_btn)) } },
                dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.common_cancel)) } },
            )
        } else {
            AlertDialog(
                onDismissRequest = { preview = null },
                title = { Text(stringResource(R.string.xfer_preview_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(if (d.rules.isNotEmpty()) stringResource(R.string.xfer_rules_count_colon, d.rules.size) else stringResource(R.string.xfer_rules_count, d.rules.size))
                        d.rules.forEach { f ->
                            val base = stringResource(if (f.isNotification) R.string.xfer_kind_noti else R.string.xfer_kind_sms, f.level)
                            val kind = if (f.enabled) base else stringResource(R.string.xfer_kind_off, base)
                            Text(stringResource(R.string.xfer_rule_line, f.name, kind), style = MaterialTheme.typography.bodySmall)
                        }
                        d.spamRules?.let { Text(stringResource(R.string.xfer_spam, it.size)) }
                        val s = d.settings
                        if (s != null) {
                            Text(stringResource(R.string.xfer_settings, s.size))
                            CheckRow(stringResource(R.string.xfer_apply_settings), applySettings) { applySettings = it }
                        } else {
                            Text(stringResource(R.string.xfer_no_settings))
                        }
                        if (d.appVersion.isNotEmpty()) Text(stringResource(R.string.xfer_version, d.appVersion), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.xfer_explain, existingCount), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { finish(ConfigTransfer.Mode.ADD) }) { Text(stringResource(R.string.common_add)) }
                        TextButton(onClick = { confirmReplace = true }) { Text(stringResource(R.string.xfer_replace_all)) }
                    }
                },
                dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
    }
}
