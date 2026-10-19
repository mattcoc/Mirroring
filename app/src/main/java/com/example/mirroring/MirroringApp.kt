package com.example.mirroring

import android.app.Application
import com.google.android.material.color.DynamicColors

class MirroringApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
