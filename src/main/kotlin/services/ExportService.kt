package services

import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * שירות ייצוא - אחראי על ייצוא לוגים לפורמטים שונים
 */
class ExportService(
    private val databaseService: DatabaseService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    suspend fun exportToText(filters: LogFilters): String {
        return withContext(Dispatchers.IO) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "logcat_export_$timestamp.txt"
            val file = File(fileName)
            
            databaseService.exportLogs(file.absolutePath, filters)
            file.absolutePath
        }
    }
    
    suspend fun exportToJson(filters: LogFilters): String {
        return withContext(Dispatchers.IO) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "logcat_export_$timestamp.json"
            val file = File(fileName)
            
            // TODO: הוסף ייצוא JSON
            file.absolutePath
        }
    }
    
    suspend fun exportToCsv(filters: LogFilters): String {
        return withContext(Dispatchers.IO) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "logcat_export_$timestamp.csv"
            val file = File(fileName)
            
            // TODO: הוסף ייצוא CSV
            file.absolutePath
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}