package com.example.kitsune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.kitsune.ui.KitsuneApp
import com.example.kitsune.ui.theme.KitsuneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as KitsuneApp).container

        setContent {
            KitsuneTheme {
                KitsuneApp(
                    appContainer = appContainer
                )
            }
        }
    }
}
