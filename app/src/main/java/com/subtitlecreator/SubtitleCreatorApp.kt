package com.subtitlecreator

import android.app.Application
import android.util.Log
import com.subtitlecreator.jni.WhisperLib

class SubtitleCreatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Log.i(TAG, "whisper.cpp info: ${WhisperLib.systemInfo()}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load whisper_jni yet: ${t.message}")
        }
    }
    companion object { private const val TAG = "App" }
}
