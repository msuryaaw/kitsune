package com.example.kitsune

import android.app.Application
import com.example.kitsune.core.AppContainer
import com.example.kitsune.core.DefaultAppContainer

class KitsuneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
    }
}

