import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipInputStream

/**
 * מנהל ADB מוטמע - מוריד ומפעיל ADB server אוטומטית
 */
object EmbeddedAdb {
    
    private var adbProcess: Process? = null
    private val userHome = System.getProperty("user.home")
    private val appDir = File(userHome, ".logcat-viewer")
    private val adbDir = File(appDir, "platform-tools")
    
    private fun getAdbExecutableName(): String {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) "adb.exe" else "adb"
    }
    
    private fun getPlatformToolsUrl(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> 
                "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
            os.contains("mac") -> 
                "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
            else -> 
                "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"
        }
    }
    
    fun getAdbPath(): String {
        return File(adbDir, getAdbExecutableName()).absolutePath
    }
    
    fun isAdbInstalled(): Boolean {
        val adbFile = File(getAdbPath())
        return adbFile.exists() && adbFile.canExecute()
    }
    
    suspend fun ensureAdbInstalled(onProgress: (String) -> Unit): Boolean {
        if (isAdbInstalled()) {
            return true
        }
        
        return downloadAndExtractAdb(onProgress)
    }
    
    private suspend fun downloadAndExtractAdb(onProgress: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                appDir.mkdirs()
                
                val zipFile = File(appDir, "platform-tools.zip")
                val url = getPlatformToolsUrl()
                
                onProgress("מוריד Android Platform Tools...")
                
                // Download
                URL(url).openStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (totalBytes % (1024 * 1024) == 0L) {
                                onProgress("הורדה: ${totalBytes / (1024 * 1024)} MB")
                            }
                        }
                    }
                }
                
                onProgress("מחלץ קבצים...")
                
                // Extract
                ZipInputStream(zipFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val file = File(appDir, entry.name)
                        
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                zip.copyTo(output)
                            }
                            
                            // Make executable on Unix-like systems
                            if (!System.getProperty("os.name").lowercase().contains("win")) {
                                if (file.name == "adb" || file.name.endsWith(".so") || file.name.endsWith(".dylib")) {
                                    try {
                                        val perms = Files.getPosixFilePermissions(file.toPath()).toMutableSet()
                                        perms.add(PosixFilePermission.OWNER_EXECUTE)
                                        perms.add(PosixFilePermission.GROUP_EXECUTE)
                                        perms.add(PosixFilePermission.OTHERS_EXECUTE)
                                        Files.setPosixFilePermissions(file.toPath(), perms)
                                    } catch (e: Exception) {
                                        // Ignore if can't set permissions
                                    }
                                }
                            }
                        }
                        
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                
                // Clean up zip file
                zipFile.delete()
                
                val adbPath = getAdbPath()
                val success = File(adbPath).exists()
                
                if (success) {
                    onProgress("ADB הותקן בהצלחה!")
                } else {
                    onProgress("שגיאה: ADB לא נמצא אחרי החילוץ")
                }
                
                success
            } catch (e: Exception) {
                onProgress("שגיאה בהורדת ADB: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
    
    suspend fun startAdbServer(onProgress: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Ensure ADB is installed
                if (!ensureAdbInstalled(onProgress)) {
                    return@withContext false
                }
                
                val adbPath = getAdbPath()
                
                // Check if ADB server is already running
                if (isAdbServerRunning()) {
                    onProgress("ADB server כבר רץ")
                    return@withContext true
                }
                
                onProgress("מפעיל ADB server...")
                
                // Start ADB server
                val processBuilder = ProcessBuilder(adbPath, "start-server")
                processBuilder.redirectErrorStream(true)
                
                val process = processBuilder.start()
                val exitCode = process.waitFor()
                
                if (exitCode == 0) {
                    onProgress("ADB server הופעל בהצלחה")
                    
                    // Wait a bit for server to fully start
                    Thread.sleep(1000)
                    
                    true
                } else {
                    val error = process.inputStream.bufferedReader().readText()
                    onProgress("שגיאה בהפעלת ADB server: $error")
                    false
                }
            } catch (e: Exception) {
                onProgress("שגיאה: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }
    
    private fun isAdbServerRunning(): Boolean {
        return try {
            val adbPath = getAdbPath()
            val process = ProcessBuilder(adbPath, "devices")
                .redirectErrorStream(true)
                .start()
            
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun stopAdbServer() {
        try {
            if (isAdbInstalled()) {
                val adbPath = getAdbPath()
                ProcessBuilder(adbPath, "kill-server")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getAdbVersion(): String? {
        return try {
            if (!isAdbInstalled()) return null
            
            val adbPath = getAdbPath()
            val process = ProcessBuilder(adbPath, "version")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            output.lines().firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
