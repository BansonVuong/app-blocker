package com.appblocker

import android.app.Application

class App : Application() {
    lateinit var storage: Storage
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        storage = Storage(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
