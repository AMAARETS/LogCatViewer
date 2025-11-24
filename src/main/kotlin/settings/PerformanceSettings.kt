package settings

import java.io.File
import java.util.Properties

data class PerformanceSettings(
    val batchSize: Int = 10000,           // כמות שורות לטעינה בכל פעם
    val scrollSpeed: Int = 5,             // מהירות גלילה (1-10)
    val cacheSize: Int = 8,               // גודל cache ב-MB
    val enablePreloading: Boolean = true,  // טעינה מוקדמת
    val enableSmartLoading: Boolean = true // טעינה חכמה
) {
    companion object {
        private val settingsFile = File(System.getProperty("user.home"), ".logcat_viewer_settings.properties")
        
        fun default() = PerformanceSettings()
        
        fun load(): PerformanceSettings {
            return try {
                if (settingsFile.exists()) {
                    val props = Properties()
                    settingsFile.inputStream().use { props.load(it) }
                    
                    PerformanceSettings(
                        batchSize = props.getProperty("batchSize", "10000").toIntOrNull() ?: 10000,
                        scrollSpeed = props.getProperty("scrollSpeed", "5").toIntOrNull() ?: 5,
                        cacheSize = props.getProperty("cacheSize", "8").toIntOrNull() ?: 8,
                        enablePreloading = props.getProperty("enablePreloading", "true").toBoolean(),
                        enableSmartLoading = props.getProperty("enableSmartLoading", "true").toBoolean()
                    )
                } else {
                    default()
                }
            } catch (e: Exception) {
                println("שגיאה בטעינת הגדרות: ${e.message}")
                default()
            }
        }
        
        fun save(settings: PerformanceSettings) {
            try {
                val props = Properties()
                props.setProperty("batchSize", settings.batchSize.toString())
                props.setProperty("scrollSpeed", settings.scrollSpeed.toString())
                props.setProperty("cacheSize", settings.cacheSize.toString())
                props.setProperty("enablePreloading", settings.enablePreloading.toString())
                props.setProperty("enableSmartLoading", settings.enableSmartLoading.toString())
                
                settingsFile.outputStream().use { props.store(it, "Logcat Viewer Performance Settings") }
            } catch (e: Exception) {
                println("שגיאה בשמירת הגדרות: ${e.message}")
            }
        }
    }
    
    // וולידציה של הגדרות
    fun validate(): PerformanceSettings {
        return copy(
            batchSize = batchSize.coerceIn(1000, 50000),
            scrollSpeed = scrollSpeed.coerceIn(1, 10),
            cacheSize = cacheSize.coerceIn(4, 64)
        )
    }
    
    // חישוב פרמטרים נגזרים
    fun getWindowSize(): Int = when (scrollSpeed) {
        in 1..3 -> batchSize / 20  // גלילה איטית - חלון קטן
        in 4..6 -> batchSize / 15  // גלילה בינונית
        in 7..8 -> batchSize / 10  // גלילה מהירה
        else -> batchSize / 8      // גלילה מהירה מאוד
    }
    
    fun getPreloadSize(): Int = if (enablePreloading) {
        (batchSize * 0.3).toInt() // 30% מהבאצ' לטעינה מוקדמת
    } else {
        0
    }
    
    fun getMaxConcurrentLoads(): Int = when (scrollSpeed) {
        in 1..4 -> 1  // טעינה סדרתית לגלילה איטית
        in 5..7 -> 2  // טעינה מקבילית מוגבלת
        else -> 3     // טעינה מקבילית מלאה לגלילה מהירה
    }
}