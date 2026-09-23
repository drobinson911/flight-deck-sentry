package com.uasflightdeck.sentry

import android.app.Application

class SentryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
