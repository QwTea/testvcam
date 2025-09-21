package com.example.virtualcam.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.virtualcam.R
import com.example.virtualcam.VirtualCamApp
import com.example.virtualcam.data.dao.RecentDao
import com.example.virtualcam.data.entity.RecentItem
import com.example.virtualcam.prefs.ApiPriority
import com.example.virtualcam.prefs.FrameFormat
import com.example.virtualcam.prefs.ModulePrefs
import com.example.virtualcam.prefs.ModuleSettings
import com.example.virtualcam.prefs.OrientationOption
import com.example.virtualcam.prefs.ScaleMode
import com.example.virtualcam.prefs.SourceType
import com.example.virtualcam.prefs.VideoMode
import com.example.virtualcam.ui.theme.VirtualCamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// GREP: UI_MAIN
class MainActivity : ComponentActivity() {

    private val modulePrefs: ModulePrefs by lazy { VirtualCamApp.get().modulePrefs }
    private val recentDao: RecentDao by lazy { VirtualCamApp.get().database.recentDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VirtualCamTheme {
                MainScreen(
                    modulePrefs = modulePrefs,
                    recentDao = recentDao,
                    onOpenHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                    onOpenDiagnostics = { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
                    onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) }
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    modulePrefs: ModulePrefs,
    recentDao: RecentDao,
    onOpenHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    var settingsState by remember { mutableStateOf(modulePrefs.read()) }
    var videoSlider by remember { mutableStateOf(0f) }
    var videoDuration by remember { mutableStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(modulePrefs) {
        settingsState = modulePrefs.read()
        videoSlider = 0f
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (err: SecurityException) {
                Toast.makeText(context, "Persist permission failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
            settingsState = settingsState.copy(sourceUri = uri)
            if (settingsState.sourceType == SourceType.VIDEO) {
                videoSlider = 0f
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "VirtualCam Settings", style = MaterialTheme.typography.headlineMedium)
        SegmentedToggle(
            options = listOf("Photo", "Video"),
            selectedIndex = if (settingsState.sourceType == SourceType.IMAGE) 0 else 1
        ) { index ->
            val newType = if (index == 0) SourceType.IMAGE else SourceType.VIDEO
            if (newType != settingsState.sourceType && newType == SourceType.VIDEO) {
                videoSlider = 0f
                videoDuration = 0L
            }
            settingsState = settingsState.copy(sourceType = newType)
        }
        Button(onClick = { launcher.launch(arrayOf("image/*", "video/*")) }) {
            Text(text = "Выбрать источник")
        }
        PreviewCard(
            settings = settingsState,
            sliderPosition = videoSlider,
            onDurationLoaded = { videoDuration = it },
            onSliderChanged = { videoSlider = it }
        )
        Divider()
        SwitchRow(
            title = "Enable module",
            checked = settingsState.enabled,
            onCheckedChange = { settingsState = settingsState.copy(enabled = it) }
        )
        SwitchRow(
            title = "Mirror output",
            checked = settingsState.mirror,
            onCheckedChange = { settingsState = settingsState.copy(mirror = it) }
        )
        SwitchRow(
            title = "Camera2 preview injection",
            checked = settingsState.injectCam2Preview,
            onCheckedChange = { settingsState = settingsState.copy(injectCam2Preview = it) }
        )
        SwitchRow(
            title = "Verbose logs",
            checked = settingsState.verbose,
            onCheckedChange = { settingsState = settingsState.copy(verbose = it) }
        )
        DropdownRow(
            label = "Scale mode",
            value = settingsState.scaleMode.storageValue,
            options = ScaleMode.values().map { it.storageValue },
        ) { selection ->
            settingsState = settingsState.copy(scaleMode = ScaleMode.fromStorage(selection))
        }
        DropdownRow(
            label = "Frame format",
            value = settingsState.format.storageValue,
            options = FrameFormat.values().map { it.storageValue },
        ) { selection ->
            settingsState = settingsState.copy(format = FrameFormat.fromStorage(selection))
        }
        DropdownRow(
            label = "API priority",
            value = settingsState.apiPriority.storageValue,
            options = ApiPriority.values().map { it.storageValue },
        ) { selection ->
            settingsState = settingsState.copy(apiPriority = ApiPriority.fromStorage(selection))
        }
        DropdownRow(
            label = "Orientation",
            value = settingsState.orientation.storageValue,
            options = OrientationOption.values().map { it.storageValue },
        ) { selection ->
            settingsState = settingsState.copy(orientation = OrientationOption.fromStorage(selection))
        }
        DropdownRow(
            label = "Video decode",
            value = settingsState.videoMode.storageValue,
            options = VideoMode.values().map { it.storageValue },
        ) { selection ->
            settingsState = settingsState.copy(videoMode = VideoMode.fromStorage(selection))
        }
        NumberRow(
            label = "Manual width",
            value = (settingsState.manualWidth ?: 0).takeIf { it > 0 }?.toString() ?: ""
        ) { value ->
            val width = value.toIntOrNull()
            settingsState = settingsState.copy(manualWidth = width)
        }
        NumberRow(
            label = "Manual height",
            value = (settingsState.manualHeight ?: 0).takeIf { it > 0 }?.toString() ?: ""
        ) { value ->
            val height = value.toIntOrNull()
            settingsState = settingsState.copy(manualHeight = height)
        }
        NumberRow(
            label = "FPS",
            value = settingsState.fps.toString()
        ) { value ->
            val fpsValue = value.toFloatOrNull() ?: settingsState.fps
            settingsState = settingsState.copy(fps = fpsValue)
        }
        Button(onClick = {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    modulePrefs.write(settingsState)
                    settingsState.sourceUri?.let {
                        recentDao.insert(
                            RecentItem(
                                uri = it.toString(),
                                type = settingsState.sourceType.storageValue,
                                addedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        }, enabled = settingsState.sourceUri != null) {
            Text(text = "Сохранить")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onOpenHistory) { Text("История") }
            TextButton(onClick = onOpenDiagnostics) { Text("Диагностика") }
            TextButton(onClick = onOpenAbout) { Text("О модуле") }
        }
        Text(
            text = "Video duration: ${TimeUnit.MILLISECONDS.toSeconds(videoDuration)} s",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SegmentedToggle(options: List<String>, selectedIndex: Int, onSelectedIndexChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Button(
                onClick = { onSelectedIndexChange(index) },
                modifier = Modifier.weight(1f),
                enabled = !selected
            ) {
                Text(text = option)
            }
        }
    }
}

@Composable
private fun PreviewCard(
    settings: ModuleSettings,
    sliderPosition: Float,
    onDurationLoaded: (Long) -> Unit,
    onSliderChanged: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Предпросмотр", style = MaterialTheme.typography.titleMedium)
            when (settings.sourceType) {
                SourceType.IMAGE -> {
                    AsyncImage(
                        model = settings.sourceUri,
                        contentDescription = "Selected image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
                SourceType.VIDEO -> {
                    VideoPreview(
                        uri = settings.sourceUri,
                        sliderPosition = sliderPosition,
                        onDurationLoaded = onDurationLoaded
                    ) { bitmap ->
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Video frame",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            )
                        } else {
                            Text("Нет превью")
                        }
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = onSliderChanged,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(
    uri: Uri?,
    sliderPosition: Float,
    onDurationLoaded: (Long) -> Unit,
    content: @Composable (android.graphics.Bitmap?) -> Unit
) {
    var frame by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    LaunchedEffect(uri, sliderPosition) {
        frame = null
        if (uri == null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val timeUs = (duration * 1000L * sliderPosition).toLong()
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                Pair(duration, bitmap)
            } catch (err: Exception) {
                Pair(0L, null)
            } finally {
                try {
                    retriever.release()
                } catch (ignored: RuntimeException) {
                }
            }
        }
        onDurationLoaded(result.first)
        frame = result.second
    }
    content(frame)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownRow(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(painterResource(id = R.drawable.ic_arrow_drop_down), contentDescription = "Toggle")
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { option ->
                    TextButton(onClick = {
                        onSelect(option)
                        expanded = false
                    }) {
                        Text(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberRow(
    label: String,
    value: String,
    onValueChanged: (String) -> Unit
) {
    var textState by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = textState,
            onValueChange = {
                textState = it
                onValueChanged(it)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
