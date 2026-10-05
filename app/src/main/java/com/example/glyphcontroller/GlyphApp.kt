package com.example.glyphcontroller

import android.app.Application

class GlyphApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlyphRuntime.init(this)
    }
}
