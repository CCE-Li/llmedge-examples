package com.example.llmedgeexample

import android.app.Application
import android.util.Log

/**
 * Application class for LLMEdge Example app.
 *
 * Handles global application lifecycle events and memory management.
 */
class LLMEdgeExampleApp : Application() {

    companion object {
        private const val TAG = "LLMEdgeExampleApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application created")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            TRIM_MEMORY_MODERATE -> "MODERATE"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "UNKNOWN($level)"
        }

        Log.i(TAG, "onTrimMemory called with level: $levelName")

        // Delegate to VideoModelManager to handle memory pressure
        VideoModelManager.handleMemoryPressure(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory called - critical memory situation")

        // This is called when the system is running critically low on memory
        // Release the model immediately
        VideoModelManager.releaseModel()
    }
}
