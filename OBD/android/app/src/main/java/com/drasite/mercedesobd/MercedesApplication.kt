package com.drasite.mercedesobd

import android.app.Application

class MercedesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Read the current configuration on every lookup, including after a language change.
        I18n.localeProvider = { resources.configuration.locales[0] }
    }
}
