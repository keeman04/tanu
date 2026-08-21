package com.tanu.app

import android.app.Application
import androidx.work.Configuration

class TanuApplication : Application(), Configuration.Provider {
    lateinit var database: TanuDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = TanuDatabase.get(this)
        PipelineRecovery.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
