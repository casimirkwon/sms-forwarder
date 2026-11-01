package io.github.casimirkwon.smsforwarder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 설정 묶음 제목. */
@Composable
fun PrefCategory(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

/**
 * 켜고 끄는 스위치 줄. [onClick] 이 있으면 줄을 눌렀을 때 그것을(상세 대화상자), 없으면 스위치를 뒤집는다.
 */
@Composable
fun PrefSwitch(title: String, summary: String? = null, checked: Boolean, onClick: (() -> Unit)? = null, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { if (onClick != null) onClick() else onChecked(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!summary.isNullOrEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** 눌러서 상세 화면·대화상자로 가는 줄. */
@Composable
fun PrefItem(title: String, summary: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!summary.isNullOrEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label)
    }
}

/** "HHMM" 문자열 → 정수(시 0~23, 분 0~59). 틀리면 null. */
fun String.toHhmm(): Int? = toIntOrNull()?.takeIf { io.github.casimirkwon.smsforwarder.core.ActiveHours.isValidHhmm(it) }

fun hhmmLabel(v: Int) = "%02d:%02d".format(v / 100, v % 100)

/** 눌러도 아무 일 없는 정보 줄(이름과 값). */
@Composable
fun PrefInfo(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
