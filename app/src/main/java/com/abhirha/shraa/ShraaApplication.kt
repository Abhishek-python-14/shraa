package com.abhirha.shraa

import android.app.Application

class ShraaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AssistantController.init(this)
    }
}
