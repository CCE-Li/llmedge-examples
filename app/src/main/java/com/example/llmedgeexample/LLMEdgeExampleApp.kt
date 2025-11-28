package com.example.llmedgeexample

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import io.aatricks.llmedge.LLMEdgeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for LLMEdge Example app.
 *
 * Handles global application lifecycle events and memory management.
 * All model lifecycle is managed through LLMEdgeManager for proper resource coordination.
 */
class LLMEdgeExampleApp : Application() {

    companion object {
        private const val TAG = "LLMEdgeExampleApp"
        private const val BYTES_IN_MB = 1024L * 1024L
        
        @Volatile
        private var instance: LLMEdgeExampleApp? = null
        
        fun getInstance(): LLMEdgeExampleApp? = instance
    }

    // Use IO dispatcher for operations that involve native JNI calls
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        logMemoryState("Application started")
        
        // Log Vulkan availability for debugging
        val vulkanInfo = LLMEdgeManager.getVulkanDeviceInfo()
        if (vulkanInfo != null) {
            Log.i(TAG, "Vulkan available: ${vulkanInfo.deviceCount} device(s), " +
                    "${vulkanInfo.freeMemoryMB}MB free / ${vulkanInfo.totalMemoryMB}MB total")
        } else {
            Log.w(TAG, "Vulkan not available - GPU acceleration disabled")
        }
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

        Log.i(TAG, "onTrimMemory: level=$levelName")
        logMemoryState("Before memory cleanup")

        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                // Critical memory pressure - cancel any ongoing generation
                Log.w(TAG, "Critical memory pressure - canceling generations")
                LLMEdgeManager.cancelGeneration()
            }
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE -> {
                // App is backgrounded or moderate pressure - allow GC to run
                Log.i(TAG, "Moderate memory pressure - allowing GC")
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory - critical memory situation")
        logMemoryState("Low memory callback")
        
        // Cancel any active generations
        LLMEdgeManager.cancelGeneration()
    }

    /**
     * Log detailed memory state for debugging.
     */
    fun logMemoryState(phase: String) {
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_IN_MB
        val heapMax = runtime.maxMemory() / BYTES_IN_MB
        val heapFree = heapMax - heapUsed

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val systemAvail = memoryInfo.availMem / BYTES_IN_MB
        val systemTotal = memoryInfo.totalMem / BYTES_IN_MB

        Log.i(TAG, "=== Memory State: $phase ===")
        Log.i(TAG, "  Heap: ${heapUsed}MB used / ${heapMax}MB max (${heapFree}MB free)")
        Log.i(TAG, "  System: ${systemAvail}MB available / ${systemTotal}MB total")
        Log.i(TAG, "  Low memory: ${memoryInfo.lowMemory}")
        
        // Log Vulkan memory if available
        LLMEdgeManager.getVulkanDeviceInfo()?.let { vulkan ->
            Log.i(TAG, "  Vulkan: ${vulkan.freeMemoryMB}MB free / ${vulkan.totalMemoryMB}MB total")
        }
    }
    
    /**
     * Check if device has low memory (less than 8GB total RAM).
     */
    fun isLowMemoryDevice(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamGB = memInfo.totalMem / (1024L * 1024L * 1024L)
        return totalRamGB < 8
    }

    /**
     * Get available system memory in MB.
     */
    fun getAvailableMemoryMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / BYTES_IN_MB
    }
}
