package com.example.kitsune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kitsune.data.entity.SettingsEntity
import com.example.kitsune.ui.KitsuneApp
import com.example.kitsune.ui.theme.KitsuneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Kitsune)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as KitsuneApp).container

        setContent {
            val settingsState by appContainer.settingsRepository.observeSettings()
                .collectAsStateWithLifecycle(initialValue = SettingsEntity())

            val systemInDark = isSystemInDarkTheme()
            val darkTheme = when (settingsState.theme) {
                "light" -> false
                "dark" -> true
                else -> systemInDark
            }

            KitsuneTheme(darkTheme = darkTheme) {
                KitsuneApp(
                    appContainer = appContainer
                )
            }
        }
    }
}
