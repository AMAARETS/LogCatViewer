package services

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import models.LogEntry

/**
 * שירות ניהול לוגים - אחראי על קבלה, עיבוד ושמירה של לוגים
 */
class LogService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val isProcessing = AtomicBoolean(false)
    private val totalProcessed = AtomicLong(0)
    
    private var flushJob: Job? = null
    private val batchSize = 25
    
    // Callbacks
    var onLogProcessed: ((LogEntry) -> Unit)? = null
    var onBatchProcessed: ((List<LogEntry>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    fun startProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            startPeriodicFlush()
        }
    }
    
    fun stopProcessing() {
        isProcessing.set(false)
        flushJob?.cancel()
        
        // שטיפה אחרונה
        scope.launch {
            flushRemainingLogs()
        }
    }
    
    fun addLog(logEntry: LogEntry) {
        if (!isProcessing.get()) return
        
        logBuffer.offer(logEntry)
        onLogProcessed?.invoke(logEntry)
        
        // flush מיידי אם הbuffer מלא
        if (logBuffer.size >= batchSize) {
            scope.launch { flushLogBuffer() }
        }
    }
    
    private fun startPeriodicFlush() {
        flushJob = scope.launch {
            while (isProcessing.get() && isActive) {
                delay(100)
                flushLogBuffer()
                
                // ניקוי זיכרון תקופתי
                if (totalProcessed.get() % 1000 == 0L) {
                    System.gc()
                }
            }
        }
    }
    
    private suspend fun flushLogBuffer() {
        val toFlush = mutableListOf<LogEntry>()
        
        // שליפת כל הלוגים מהbuffer
        while (logBuffer.isNotEmpty() && toFlush.size < batchSize) {
            logBuffer.poll()?.let { toFlush.add(it) }
        }
        
        if (toFlush.isNotEmpty()) {
            try {
                onBatchProcessed?.invoke(toFlush)
                totalProcessed.addAndGet(toFlush.size.toLong())
            } catch (e: Exception) {
                onError?.invoke("שגיאה בעיבוד batch: ${e.message}")
            }
        }
    }
    
    private suspend fun flushRemainingLogs() {
        val remaining = mutableListOf<LogEntry>()
        while (logBuffer.isNotEmpty()) {
            logBuffer.poll()?.let { remaining.add(it) }
        }
        
        if (remaining.isNotEmpty()) {
            onBatchProcessed?.invoke(remaining)
        }
    }
    
    fun getTotalProcessed(): Long = totalProcessed.get()
    
    fun cleanup() {
        stopProcessing()
        scope.cancel()
    }
}