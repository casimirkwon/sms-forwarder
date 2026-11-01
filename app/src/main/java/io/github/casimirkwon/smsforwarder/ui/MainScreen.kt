package io.github.casimirkwon.smsforwarder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.casimirkwon.smsforwarder.Graph
import io.github.casimirkwon.smsforwarder.R

@Composable
fun MainScreen(graph: Graph) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(stringResource(R.string.tab_status), stringResource(R.string.tab_rules), stringResource(R.string.tab_settings), stringResource(R.string.tab_history))
    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> StatusScreen(graph)
                1 -> RulesScreen(graph)
                2 -> SettingsScreen(graph, onOpenStatus = { tab = 0 })
                else -> HistoryScreen(graph)
            }
        }
    }
}
