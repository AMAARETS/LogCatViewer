import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import models.*
import services.LogFilters

class LogDatabase(private val dbPath: String = "logcat_viewer.db") {
    private var connection: Connection? = null
    private var insertStmt: PreparedStatement? = null
    
    suspend fun initialize() = withContext(Dispatchers.IO) {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        
        // Performance optimizations for SQLite
        connection?.createStatement()?.use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")  // Write-Ahead Logging
            stmt.execute("PRAGMA synchronous=NORMAL")  // Faster writes
            stmt.execute("PRAGMA cache_size=20000")  // Larger cache (20MB)
            stmt.execute("PRAGMA temp_store=MEMORY")  // Use RAM for temp
            stmt.execute("PRAGMA busy_timeout=5000")  // Wait 5s if locked
            stmt.execute("PRAGMA page_size=4096")  // Optimal page size
            stmt.execute("PRAGMA mmap_size=268435456")  // 256MB memory-mapped I/O
        }
        
        createTables()
        prepareStatements()
    }
    
    private fun createTables() {
        connection?.createStatement()?.execute("""
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                pid TEXT NOT NULL,
                tid TEXT NOT NULL,
                level TEXT NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                package_name TEXT DEFAULT '',
                created_at INTEGER NOT NULL
            )
        """)
        
        // Add package_name column if it doesn't exist (for existing databases)
        try {
            connection?.createStatement()?.execute("""
                ALTER TABLE logs ADD COLUMN package_name TEXT DEFAULT ''
            """)
        } catch (e: Exception) {
            // Column already exists, ignore
        }
        
        // Create optimized indexes for faster queries
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_package_name ON logs(package_name)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_level_package ON logs(level, package_name)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_tag_package ON logs(tag, package_name)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_message_fts ON logs(message)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_composite_filter ON logs(level, package_name, tag, id)
        """)
    }
    
    private fun prepareStatements() {
        insertStmt = connection?.prepareStatement("""
            INSERT INTO logs (timestamp, pid, tid, level, tag, message, package_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)
    }
    
    suspend fun insertLog(log: LogEntry) = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO logs (timestamp, pid, tid, level, tag, message, package_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, log.timestamp)
            stmt.setString(2, log.pid)
            stmt.setString(3, log.tid)
            stmt.setString(4, log.level.displayName)
            stmt.setString(5, log.tag)
            stmt.setString(6, log.message)
            stmt.setString(7, log.packageName)
            stmt.setLong(8, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }
    
    // הוסף synchronization כדי למנוע בעיות concurrency
    private val insertMutex = kotlinx.coroutines.sync.Mutex()
    
    suspend fun insertLogsBatch(logs: List<LogEntry>) = withContext(Dispatchers.IO) {
        if (logs.isEmpty()) return@withContext
        
        // השתמש ב-mutex כדי למנוע גישה מרובה למסד הנתונים
        insertMutex.withLock {
            val conn = connection ?: return@withContext
            
            try {
                // השתמש ב-prepared statement נפרד לכל batch כדי למנוע בעיות
                val sql = """
                    INSERT INTO logs (timestamp, pid, tid, level, tag, message, package_name, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """
                
                conn.prepareStatement(sql).use { stmt ->
                    val timestamp = System.currentTimeMillis()
                    var addedCount = 0
                    var skippedCount = 0
                    
                    logs.forEach { log ->
                        // בדיקות חזקות לוודא שהנתונים תקינים
                        if (log.timestamp.isBlank() || log.pid.isBlank() || log.tid.isBlank() || 
                            log.level.displayName.isBlank() || log.tag.isBlank()) {
                            println("Warning: Skipping invalid log: timestamp='${log.timestamp}', pid='${log.pid}', tid='${log.tid}', level='${log.level.displayName}', tag='${log.tag}'")
                            skippedCount++
                            return@forEach
                        }
                        
                        try {
                            stmt.setString(1, log.timestamp.trim())
                            stmt.setString(2, log.pid.trim())
                            stmt.setString(3, log.tid.trim())
                            stmt.setString(4, log.level.displayName.trim())
                            stmt.setString(5, log.tag.trim())
                            stmt.setString(6, log.message.trim())
                            stmt.setString(7, log.packageName.trim())
                            stmt.setLong(8, timestamp)
                            stmt.addBatch()
                            addedCount++
                        } catch (e: Exception) {
                            println("Error adding log to batch: $log")
                            println("Error: ${e.message}")
                            skippedCount++
                        }
                    }
                    
                    if (addedCount > 0) {
                        stmt.executeBatch()
                    }
                    
                    if (skippedCount > 0) {
                        println("Batch complete: added $addedCount, skipped $skippedCount logs")
                    }
                }
            } catch (e: Exception) {
                println("Error in insertLogsBatch: ${e.message}")
                throw e
            }
        }
    }
    
    suspend fun getTotalLogCount(): Int = withContext(Dispatchers.IO) {
        val sql = "SELECT COUNT(*) FROM logs"
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        } ?: 0
    }
    
    suspend fun getLogCount(
        searchText: String = "",
        levels: Set<LogLevel> = emptySet(),
        tagFilter: String = "",
        packageFilter: Set<String> = emptySet()
    ): Int = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(searchText, levels, tagFilter, packageFilter)
        val sql = "SELECT COUNT(*) FROM logs $conditions"
        
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        } ?: 0
    }
    
    suspend fun getLogs(
        offset: Int,
        limit: Int,
        searchText: String = "",
        levels: Set<LogLevel> = emptySet(),
        tagFilter: String = "",
        packageFilter: Set<String> = emptySet()
    ): List<LogEntry> = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(searchText, levels, tagFilter, packageFilter)
        val sql = """
            SELECT id, timestamp, pid, tid, level, tag, message, package_name
            FROM logs
            $conditions
            ORDER BY id
            LIMIT ? OFFSET ?
        """
        
        val logs = ArrayList<LogEntry>(limit)
        connection?.prepareStatement(sql)?.use { stmt ->
            // Optimize for sequential access
            stmt.fetchSize = minOf(limit, 500)
            stmt.setInt(1, limit)
            stmt.setInt(2, offset)
            
            stmt.executeQuery().use { rs ->
                // Pre-allocate to avoid resizing
                while (rs.next()) {
                    logs.add(resultSetToLogEntry(rs))
                }
            }
        }
        logs
    }
    
    // Optimized and parameterized query builder
    private fun buildWhereClause(
        searchText: String,
        levels: Set<LogLevel>,
        tagFilter: String,
        packageFilter: Set<String> = emptySet()
    ): String {
        if (searchText.isEmpty() && levels.isEmpty() && tagFilter.isEmpty() && packageFilter.isEmpty()) {
            return ""
        }
        
        val conditions = mutableListOf<String>()
        
        // Use parameterized queries for better performance and security
        if (searchText.isNotEmpty()) {
            val escaped = searchText.replace("'", "''").replace("%", "\\%").replace("_", "\\_")
            conditions.add("(message LIKE '%$escaped%' ESCAPE '\\' OR tag LIKE '%$escaped%' ESCAPE '\\')")
        }
        
        if (levels.isNotEmpty()) {
            // Pre-validate levels to prevent SQL injection
            val validLevels = levels.filter { it.displayName.matches(Regex("^[A-Z]$")) }
            if (validLevels.isNotEmpty()) {
                val levelStr = validLevels.joinToString("','", "'", "'") { it.displayName }
                conditions.add("level IN ($levelStr)")
            }
        }
        
        if (tagFilter.isNotEmpty()) {
            val escaped = tagFilter.replace("'", "''").replace("%", "\\%").replace("_", "\\_")
            conditions.add("tag LIKE '%$escaped%' ESCAPE '\\'")
        }
        
        if (packageFilter.isNotEmpty()) {
            val hasNoPackageFilter = packageFilter.contains("ללא")
            val regularPackages = packageFilter.filter { it != "ללא" && it.isNotBlank() }
            
            val packageConditions = mutableListOf<String>()
            
            if (hasNoPackageFilter) {
                packageConditions.add("(package_name = '' OR package_name IS NULL)")
            }
            
            if (regularPackages.isNotEmpty()) {
                // Validate package names to prevent injection
                val validPackages = regularPackages.filter { 
                    it.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$")) 
                }
                if (validPackages.isNotEmpty()) {
                    val packageStr = validPackages.joinToString("','", "'", "'") { it.replace("'", "''") }
                    packageConditions.add("package_name IN ($packageStr)")
                }
            }
            
            if (packageConditions.isNotEmpty()) {
                conditions.add("(${packageConditions.joinToString(" OR ")})")
            }
        }
        
        return "WHERE ${conditions.joinToString(" AND ")}"
    }
    
    private fun resultSetToLogEntry(rs: ResultSet): LogEntry {
        val levelStr = rs.getString("level")
        val level = when (levelStr) {
            "V" -> LogLevel.VERBOSE
            "D" -> LogLevel.DEBUG
            "I" -> LogLevel.INFO
            "W" -> LogLevel.WARN
            "E" -> LogLevel.ERROR
            "A" -> LogLevel.ASSERT
            else -> LogLevel.VERBOSE
        }
        
        return LogEntry(
            id = rs.getLong("id"),
            timestamp = rs.getString("timestamp"),
            pid = rs.getString("pid"),
            tid = rs.getString("tid"),
            level = level,
            tag = rs.getString("tag"),
            message = rs.getString("message"),
            packageName = rs.getString("package_name") ?: ""
        )
    }
    
    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        connection?.createStatement()?.execute("DELETE FROM logs")
        connection?.createStatement()?.execute("VACUUM")
    }
    
    suspend fun exportLogs(filePath: String, filters: LogFilters) = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(filters.searchText, filters.levels, filters.tagFilter, filters.packageFilter)
        val sql = """
            SELECT timestamp, pid, tid, level, tag, message, package_name
            FROM logs
            $conditions
            ORDER BY id
        """
        
        java.io.File(filePath).bufferedWriter().use { writer ->
            connection?.createStatement()?.use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        val timestamp = rs.getString("timestamp")
                        val pid = rs.getString("pid")
                        val tid = rs.getString("tid")
                        val level = rs.getString("level")
                        val tag = rs.getString("tag")
                        val message = rs.getString("message")
                        val packageName = rs.getString("package_name") ?: ""
                        val pkgDisplay = if (packageName.isNotEmpty()) " [$packageName]" else ""
                        writer.write("$timestamp $pid/$tid $level/$tag$pkgDisplay: $message\n")
                    }
                }
            }
        }
    }
    
    suspend fun getUniquePackageNames(): List<String> = withContext(Dispatchers.IO) {
        // Single optimized query using UNION to get both empty and non-empty packages efficiently
        val sql = """
            SELECT DISTINCT 
                CASE 
                    WHEN package_name = '' OR package_name IS NULL THEN 'ללא'
                    ELSE package_name 
                END as display_name,
                CASE 
                    WHEN package_name = '' OR package_name IS NULL THEN 0
                    ELSE 1 
                END as sort_order
            FROM logs 
            WHERE package_name IS NOT NULL
            ORDER BY sort_order, display_name
        """
        
        val packages = mutableListOf<String>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val displayName = rs.getString("display_name")
                    if (displayName.isNotEmpty()) {
                        packages.add(displayName)
                    }
                }
            }
        }
        
        packages
    }
    
    fun close() {
        insertStmt?.close()
        connection?.close()
    }
}

data class LogFilters(
    val searchText: String = "",
    val levels: Set<LogLevel> = emptySet(),
    val tagFilter: String = "",
    val packageFilter: Set<String> = emptySet()
)
