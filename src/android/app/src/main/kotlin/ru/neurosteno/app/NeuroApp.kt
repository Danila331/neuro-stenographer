package ru.neurosteno.app

import android.app.Application

class NeuroApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // создаёт app-specific внешнюю папку силами самого приложения (иначе её владелец -- adb shell и приложение её не прочитает)
        runCatching { getExternalFilesDir(null) }
    }
}
