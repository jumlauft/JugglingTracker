package com.juggling.tracker

import android.app.Application
import com.google.firebase.FirebaseApp

class JugglingTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
