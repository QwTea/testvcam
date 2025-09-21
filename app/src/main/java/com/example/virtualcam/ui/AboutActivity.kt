package com.example.virtualcam.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.virtualcam.BuildConfig
import com.example.virtualcam.ui.theme.VirtualCamTheme

// GREP: UI_ABOUT
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VirtualCamTheme {
                AboutScreen()
            }
        }
    }
}

@Composable
private fun AboutScreen() {
    val context = LocalContext.current
    val repoLink = "https://github.com/example/virtualcam"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "VirtualCam", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Version: ${BuildConfig.VERSION_NAME}")
        Text(text = "License: Apache-2.0")
        Text(
            text = "Репозиторий:",
            style = MaterialTheme.typography.titleMedium
        )
        Button(onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repoLink))
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = repoLink)
        }
        Text(text = "DISCLAIMER", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Этот модуль предназначен только для тестирования и QA на ваших устройствах. Соблюдайте законы, правила приложений и приватность. Не используйте для обхода биометрии, анти-фрода и DRM.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
