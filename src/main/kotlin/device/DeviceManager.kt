package device

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import se.vidstige.jadb.JadbConnection
import se.vidstige.jadb.JadbDevice
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import models.DeviceInfo
import models.DeviceStatus

/**
 * מנהל מכשירים - אחראי על חיבור וניהול מכשירי Android
 */
class DeviceManager {
    val devices = mutableStateListOf<DeviceInfo>()
    val selectedDevice = mutableStateOf<DeviceInfo?>(null)
    val statusMessage = mutableStateOf("מוכן")
    
    private var jadb: JadbConnection? = null
    private var deviceScanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * אתחול מנהל המכשירים
     */
    suspend fun initialize(): Boolean {
        return try {
            statusMessage.value = "מאתחל..."
            
            // הפעלת ADB server
            val adbStarted = EmbeddedAdb.startAdbServer { progress ->
                statusMessage.value = progress
            }
            
            if (!adbStarted) {
                statusMessage.value = "שגיאה: לא ניתן להפעיל ADB"
                return false
            }
            
            statusMessage.value = "מתחבר ל-ADB..."
            
            // חיבור ל-ADB server
            jadb = try {
                JadbConnection()
            } catch (e: Exception) {
                statusMessage.value = "שגיאה בחיבור ל-ADB: ${e.message}"
                return false
            }
            
            statusMessage.value = "מחובר ל-ADB"
            
            // התחלת סריקה תקופתית
            startDeviceScanning()
            
            // סריקה ראשונית
            refreshDevices()
            
            true
        } catch (e: Exception) {
            statusMessage.value = "שגיאה: ${e.message}"
            false
        }
    }
    
    /**
     * התחלת סריקה תקופתית של מכשירים
     */
    private fun startDeviceScanning() {
        deviceScanJob = scope.launch {
            while (isActive) {
                try {
                    refreshDevices()
                    delay(3000) // סריקה כל 3 שניות
                } catch (e: Exception) {
                    // התעלמות משגיאות בסריקה
                }
            }
        }
    }
    
    /**
     * רענון רשימת המכשירים
     */
    suspend fun refreshDevices() {
        try {
            val connection = jadb ?: return
            val deviceList = connection.devices
            
            val deviceInfos = deviceList.mapNotNull { device ->
                try {
                    val serial = device.serial
                    val model = getDeviceModel(device)
                    DeviceInfo(
                        id = serial,
                        name = model,
                        model = model,
                        status = DeviceStatus.DEVICE,
                        jadbDevice = device
                    )
                } catch (e: Exception) {
                    null
                }
            }
            
            devices.clear()
            devices.addAll(deviceInfos)
            
            // בחירה אוטומטית של המכשיר הראשון
            if (selectedDevice.value == null && devices.isNotEmpty()) {
                selectedDevice.value = devices.first()
            }
            
            statusMessage.value = "נמצאו ${devices.size} מכשירים"
        } catch (e: Exception) {
            statusMessage.value = "שגיאה בסריקת מכשירים: ${e.message}"
        }
    }
    
    /**
     * קבלת מודל המכשיר
     */
    private suspend fun getDeviceModel(device: JadbDevice): String {
        return try {
            withContext(Dispatchers.IO) {
                val stream = device.executeShell("getprop", "ro.product.model")
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readLine()?.trim() ?: "Unknown"
                }
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * קבלת המכשיר הנבחר
     */
    fun getSelectedDevice(): DeviceInfo? = selectedDevice.value
    
    /**
     * ניקוי משאבים
     */
    fun cleanup() {
        deviceScanJob?.cancel()
        scope.cancel()
    }
}