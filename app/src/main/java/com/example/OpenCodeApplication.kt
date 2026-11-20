package com.example

import android.app.Application
import com.example.crash.CrashReporter
import com.example.data.ssh.SshSecurityProvider
import com.example.di.AppContainer

class OpenCodeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        runCatching { SshSecurityProvider.ensureInitialized() }
        container = AppContainer(this)
        container.foregroundServiceController.startObserving()
    }
}
