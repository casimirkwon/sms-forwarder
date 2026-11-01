package io.github.casimirkwon.smsforwarder.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.casimirkwon.smsforwarder.R
import io.github.casimirkwon.smsforwarder.core.AppItem
import io.github.casimirkwon.smsforwarder.core.AppListOrder
import io.github.casimirkwon.smsforwarder.core.NotiRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 설치된 앱 목록에서 대상 앱을 고르는 대화상자. 목록은 실행 가능한(LAUNCHER) 앱을
 * `queryIntentActivities` 로 얻어(QUERY_ALL_PACKAGES 권한) 패키지 중복을 없애고 [AppListOrder] 로 정렬한다.
 * 선택된 앱이 맨 위에 오고, 아무것도 고르지 않으면 모든 앱이 대상이다. [guide] 는 안내 문구(아래 [extra] 슬롯 위).
 */
@Composable
fun AppPickerDialog(
    initial: String,
    title: String,
    guide: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val ctx = LocalContext.current
    val selected = remember { NotiRule.parsePackages(initial).toMutableStateList() }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val locale = remember { Locale.getDefault() }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) }
        loading = false
    }
    // 선택된 앱을 위로 올리는 정렬은 대화상자를 연 시점의 선택으로 한 번만 한다(고르는 동안 목록이 움직이지 않게).
    val notInstalled = stringResource(R.string.not_installed)
    val arranged = remember(apps) { AppListOrder.arrange(apps, NotiRule.parsePackages(initial), locale, notInstalled) }
    val shown = AppListOrder.filter(arranged, query, locale)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(guide, style = MaterialTheme.typography.bodySmall)
                extra()
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.picker_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading) Text(stringResource(R.string.picker_loading), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        val on = selected.contains(app.packageName)
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) selected.remove(app.packageName) else selected.add(app.packageName) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, { if (it) selected.add(app.packageName) else selected.remove(app.packageName) })
                            Column {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(NotiRule.serializePackages(selected.toSet().toList())) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** MAIN/LAUNCHER 인텐트를 받는 앱을 모아 패키지 이름 기준으로 중복을 없앤다. */
fun loadLaunchableApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
    val seen = HashSet<String>()
    val out = ArrayList<AppItem>()
    try {
        for (ri in pm.queryIntentActivities(intent, 0)) {
            try {
                val info = ri.activityInfo.applicationInfo
                if (seen.add(info.packageName)) out.add(AppItem(info.loadLabel(pm).toString(), info.packageName))
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }
    return out
}
