package com.example.virtualcam.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.virtualcam.VirtualCamApp
import com.example.virtualcam.data.entity.RecentItem
import com.example.virtualcam.prefs.ModulePrefs
import com.example.virtualcam.prefs.SourceType
import com.example.virtualcam.ui.theme.VirtualCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// GREP: UI_HISTORY
class HistoryActivity : ComponentActivity() {

    private val modulePrefs: ModulePrefs by lazy { VirtualCamApp.get().modulePrefs }
    private val recentDao by lazy { VirtualCamApp.get().database.recentDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VirtualCamTheme {
                val recentItems by recentDao.observeAll().collectAsState(initial = emptyList())
                HistoryScreen(
                    recentItems = recentItems,
                    onApply = { item -> applyRecent(item) },
                    onClear = { clearHistory() }
                )
            }
        }
    }

    private fun applyRecent(item: RecentItem) {
        val uri = Uri.parse(item.uri)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val current = modulePrefs.read()
                modulePrefs.write(
                    current.copy(
                        sourceUri = uri,
                        sourceType = SourceType.fromStorage(item.type)
                    )
                )
            }
            Toast.makeText(this@HistoryActivity, "Источник применён", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun clearHistory() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { recentDao.clear() }
            Toast.makeText(this@HistoryActivity, "История очищена", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun HistoryScreen(
    recentItems: List<RecentItem>,
    onApply: (RecentItem) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "История", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
            Text("Очистить")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = true)) {
            items(recentItems) { item ->
                HistoryRow(item = item, onApply = onApply)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: RecentItem, onApply: (RecentItem) -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable { onApply(item) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = Uri.parse(item.uri),
                contentDescription = null,
                modifier = Modifier.weight(0.3f)
            )
            Column(modifier = Modifier.weight(0.7f)) {
                Text(text = item.type, style = MaterialTheme.typography.labelLarge)
                Text(text = item.uri, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
