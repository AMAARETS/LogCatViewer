package state

import androidx.compose.runtime.mutableStateOf
import models.*

/**
 * מצב כללי של האפליקציה
 */
class LogcatState {
    // מצב ריצה
    val isRunning = mutableStateOf(false)
    val statusMessage = mutableStateOf("מוכן")
    
    // מונים
    val totalLogCount = mutableStateOf(0)
    val filteredLogCount = mutableStateOf(0)
    val lastLogUpdate = mutableStateOf(0L)
    
    // הגדרות UI
    val autoScroll = mutableStateOf(true)
    val showTimestamp = mutableStateOf(true)
    val showPid = mutableStateOf(true)
    val showTag = mutableStateOf(true)
    
    // מכשיר נבחר
    val selectedDevice = mutableStateOf<DeviceInfo?>(null)
    
    fun updateLogCounts(total: Int, filtered: Int) {
        totalLogCount.value = total
        filteredLogCount.value = filtered
        lastLogUpdate.value = System.currentTimeMillis()
    }
    
    fun setRunning(running: Boolean, message: String = "") {
        isRunning.value = running
        if (message.isNotEmpty()) {
            statusMessage.value = message
        }
    }
    
    fun setSelectedDevice(device: DeviceInfo?) {
        selectedDevice.value = device
    }
}