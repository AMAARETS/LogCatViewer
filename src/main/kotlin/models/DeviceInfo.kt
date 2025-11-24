package models

/**
 * מידע על מכשיר Android
 */
data class DeviceInfo(
    val id: String,
    val name: String,
    val model: String = "",
    val androidVersion: String = "",
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val jadbDevice: Any? = null // שמירת ה-JadbDevice (Any כדי לא ליצור תלות)
) {
    fun getDisplayName(): String {
        return if (name.isNotEmpty() && model.isNotEmpty()) {
            "$name ($model)"
        } else if (model.isNotEmpty()) {
            model
        } else if (name.isNotEmpty()) {
            name
        } else {
            id
        }
    }
    
    fun isConnected(): Boolean {
        return status == DeviceStatus.DEVICE || status == DeviceStatus.EMULATOR
    }
}

/**
 * סטטוס מכשיר
 */
enum class DeviceStatus(val displayName: String) {
    DEVICE("מכשיר"),
    EMULATOR("אמולטור"),
    OFFLINE("לא מחובר"),
    UNAUTHORIZED("לא מורשה"),
    UNKNOWN("לא ידוע");
    
    companion object {
        fun fromAdbStatus(status: String): DeviceStatus {
            return when (status.lowercase()) {
                "device" -> DEVICE
                "emulator" -> EMULATOR
                "offline" -> OFFLINE
                "unauthorized" -> UNAUTHORIZED
                else -> UNKNOWN
            }
        }
    }
}