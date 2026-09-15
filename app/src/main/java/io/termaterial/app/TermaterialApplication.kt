package io.termaterial.app

import android.app.Application

class TermaterialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
