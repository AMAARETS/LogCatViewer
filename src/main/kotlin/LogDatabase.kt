import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                created_at INTEGER NOT NULL
            )
        """)
        
        // Create composite indexes for faster queries
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_id_level ON logs(id, level)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_id_tag ON logs(id, tag)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_level_id ON logs(level, id)
        """)
        connection?.createStatement()?.execute("""
            CREATE INDEX IF NOT EXISTS idx_tag_id ON logs(tag, id)
        """)
    }
    
    private fun prepareStatements() {
        insertStmt = connection?.prepareStatement("""
            INSERT INTO logs (timestamp, pid, tid, level, tag, message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """)
    }
    
    suspend fun insertLog(log: LogEntry) = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO logs (timestamp, pid, tid, level, tag, message, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, log.timestamp)
            stmt.setString(2, log.pid)
            stmt.setString(3, log.tid)
            stmt.setString(4, log.level.displayName)
            stmt.setString(5, log.tag)
            stmt.setString(6, log.message)
            stmt.setLong(7, System.currentTimeMillis())
            stmt.executeUpdate()
        }
    }
    
    suspend fun insertLogsBatch(logs: List<LogEntry>) = withContext(Dispatchers.IO) {
        if (logs.isEmpty()) return@withContext
        
        val conn = connection ?: return@withContext
        
        try {
            conn.autoCommit = false
            val stmt = insertStmt ?: throw IllegalStateException("Database not initialized")
            val timestamp = System.currentTimeMillis()
            
            logs.forEach { log ->
                stmt.setString(1, log.timestamp)
                stmt.setString(2, log.pid)
                stmt.setString(3, log.tid)
                stmt.setString(4, log.level.displayName)
                stmt.setString(5, log.tag)
                stmt.setString(6, log.message)
                stmt.setLong(7, timestamp)
                stmt.addBatch()
            }
            stmt.executeBatch()
            stmt.clearBatch()
            conn.commit()
        } catch (e: Exception) {
            try {
                if (!conn.autoCommit) {
                    conn.rollback()
                }
            } catch (rollbackEx: Exception) {
                // Ignore rollback errors
            }
            throw e
        } finally {
            try {
                conn.autoCommit = true
            } catch (ex: Exception) {
                // Ignore
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
        tagFilter: String = ""
    ): Int = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(searchText, levels, tagFilter)
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
        tagFilter: String = ""
    ): List<LogEntry> = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(searchText, levels, tagFilter)
        val sql = """
            SELECT id, timestamp, pid, tid, level, tag, message
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
    
    private fun buildWhereClause(
        searchText: String,
        levels: Set<LogLevel>,
        tagFilter: String
    ): String {
        val conditions = mutableListOf<String>()
        
        if (searchText.isNotEmpty()) {
            val escaped = searchText.replace("'", "''")
            conditions.add("(message LIKE '%$escaped%' OR tag LIKE '%$escaped%')")
        }
        
        if (levels.isNotEmpty()) {
            val levelStr = levels.joinToString("','", "'", "'") { it.displayName }
            conditions.add("level IN ($levelStr)")
        }
        
        if (tagFilter.isNotEmpty()) {
            val escaped = tagFilter.replace("'", "''")
            conditions.add("tag LIKE '%$escaped%'")
        }
        
        return if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
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
            message = rs.getString("message")
        )
    }
    
    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        connection?.createStatement()?.execute("DELETE FROM logs")
        connection?.createStatement()?.execute("VACUUM")
    }
    
    suspend fun exportLogs(filePath: String, filters: LogFilters) = withContext(Dispatchers.IO) {
        val conditions = buildWhereClause(filters.searchText, filters.levels, filters.tagFilter)
        val sql = """
            SELECT timestamp, pid, tid, level, tag, message
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
                        writer.write("$timestamp $pid/$tid $level/$tag: $message\n")
                    }
                }
            }
        }
    }
    
    fun close() {
        insertStmt?.close()
        connection?.close()
    }
}

data class LogFilters(
    val searchText: String = "",
    val levels: Set<LogLevel> = emptySet(),
    val tagFilter: String = ""
)
