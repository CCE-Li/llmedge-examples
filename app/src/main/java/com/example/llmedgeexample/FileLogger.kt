package com.example.llmedgeexample

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-based logger for users without access to logcat.
 * 
 * Logs are written to: /storage/emulated/0/Android/data/com.example.llmedgeexample/files/logs/
 * 
 * Usage:
 *   FileLogger.init(context)
 *   FileLogger.i("TAG", "message")
 *   FileLogger.e("TAG", "error", exception)
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_FILES = 20
    private const val MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024 // 10MB per file
    
    private var logFile: File? = null
    private var logDir: File? = null
    private val initialized = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    
    // Async writing to avoid blocking UI thread
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val executor = Executors.newSingleThreadExecutor()
    private val isWriting = AtomicBoolean(false)
    
    /**
     * Initialize the file logger. Call this in Application.onCreate()
     */
    fun init(context: Context) {
        if (initialized.getAndSet(true)) return
        
        try {
            // Use external files dir so users can access logs without root
            logDir = File(context.getExternalFilesDir(null), LOG_DIR).apply {
                if (!exists()) mkdirs()
            }
            
            // Create new log file with timestamp
            val timestamp = fileNameFormat.format(Date())
            logFile = File(logDir, "llmedge_$timestamp.log")
            
            // Write header
            val header = buildString {
                appendLine("=" .repeat(60))
                appendLine("LLMEdge Example App Log")
                appendLine("Started: ${dateFormat.format(Date())}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("=" .repeat(60))
                appendLine()
            }
            logFile?.writeText(header)
            
            // Setup uncaught exception handler to flush logs on crash
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e(TAG, "FATAL EXCEPTION in thread ${thread.name}", throwable)
                flush()
                defaultHandler?.uncaughtException(thread, throwable)
            }
            
            // Cleanup old log files
            cleanupOldLogs()
            
            Log.i(TAG, "File logging initialized: ${logFile?.absolutePath}")
            i(TAG, "File logging initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logger", e)
        }
    }
    
    /**
     * Get the path to the logs directory for sharing with users.
     */
    fun getLogDirectory(): String? = logDir?.absolutePath
    
    /**
     * Get the current log file path.
     */
    fun getCurrentLogFile(): String? = logFile?.absolutePath
    
    /**
     * Log at INFO level.
     */
    fun i(tag: String, message: String) {
        log("I", tag, message)
        Log.i(tag, message)
    }
    
    /**
     * Log at DEBUG level.
     */
    fun d(tag: String, message: String) {
        log("D", tag, message)
        Log.d(tag, message)
    }
    
    /**
     * Log at WARNING level.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log("W", tag, message, throwable)
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }
    
    /**
     * Log at ERROR level.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log("E", tag, message, throwable)
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
    
    /**
     * Log at VERBOSE level.
     */
    fun v(tag: String, message: String) {
        log("V", tag, message)
        Log.v(tag, message)
    }
    
    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized.get()) return
        
        val timestamp = dateFormat.format(Date())
        val logLine = buildString {
            append("$timestamp $level/$tag: $message")
            if (throwable != null) {
                appendLine()
                append(throwable.stackTraceToString())
            }
            appendLine()
        }
        
        logQueue.offer(logLine)
        flushAsync()
        // For debugging, also flush immediately if it's an error
        if (level == "E" || level == "W") {
            flush()
        }
    }
    
    private fun flushAsync() {
        if (isWriting.getAndSet(true)) return
        
        executor.execute {
            try {
                val file = logFile ?: return@execute
                
                // Check if we need to rotate
                if (file.length() > MAX_LOG_SIZE_BYTES) {
                    rotateLog()
                }
                
                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    while (true) {
                        val line = logQueue.poll() ?: break
                        writer.write(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            } finally {
                isWriting.set(false)
                // If more logs came in while writing, flush again
                if (!logQueue.isEmpty()) {
                    flushAsync()
                }
            }
        }
    }
    
    private fun rotateLog() {
        try {
            val timestamp = fileNameFormat.format(Date())
            logFile = File(logDir, "llmedge_$timestamp.log")
            cleanupOldLogs()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log", e)
        }
    }
    
    private fun cleanupOldLogs() {
        try {
            val files = logDir?.listFiles { file -> file.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            
            // Keep only the most recent files
            files.drop(MAX_LOG_FILES).forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted old log: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }
    
    /**
     * Force flush all pending logs (call before app exit or crash).
     */
    fun flush() {
        try {
            val file = logFile ?: return
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                while (true) {
                    val line = logQueue.poll() ?: break
                    writer.write(line)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush log", e)
        }
    }
    
    /**
     * Log a separator line for visual clarity.
     */
    fun separator(title: String = "") {
        val line = if (title.isBlank()) {
            "-".repeat(60)
        } else {
            "--- $title ${"-".repeat(maxOf(0, 55 - title.length))}"
        }
        i(TAG, line)
    }
}
