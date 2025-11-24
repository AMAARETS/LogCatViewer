package scroll

import models.LogEntry
import LogcatViewModelNew
import cache.LogCache
import settings.PerformanceSettings
import kotlinx.coroutines.*

/**
 * מנהל גלילה חכם עם אופטימיזציות זיכרון מותאמות למשתמש
 */
class ScrollManager(
    private val viewModel: LogcatViewModelNew,
    private val scope: CoroutineScope,
    private var settings: PerformanceSettings = PerformanceSettings.load()
) {
    private var cache = LogCache(maxMemoryMB = settings.cacheSize, cleanupThresholdMB = settings.cacheSize - 2)
    
    // הגדרות גלילה דינמיות לפי הגדרות המשתמש
    private var baseWindowSize = settings.getWindowSize()
    private var maxWindowSize = settings.getWindowSize() * 2
    private var ultraFastWindowSize = settings.getWindowSize() * 3
    
    private var currentWindowSize = baseWindowSize
    private var isLoading = false
    private var loadingJob: Job? = null
    
    // מעקב מהירות גלילה
    private var lastScrollTime = 0L
    private var lastScrollIndex = 0
    private var scrollVelocity = 0f
    private val scrollHistory = mutableListOf<Pair<Long, Int>>()
    private var isUltraFastScrolling = false
    
    /**
     * עדכון הגדרות ביצועים
     */
    fun updateSettings(newSettings: PerformanceSettings) {
        settings = newSettings.validate()
        
        // עדכן cache
        cache = LogCache(maxMemoryMB = settings.cacheSize, cleanupThresholdMB = settings.cacheSize - 2)
        
        // עדכן גדלי חלונות
        baseWindowSize = settings.getWindowSize()
        maxWindowSize = settings.getWindowSize() * 2
        ultraFastWindowSize = settings.getWindowSize() * 3
        
        // שמור הגדרות
        PerformanceSettings.save(settings)
    }
    
    /**
     * טוען לוגים לטווח נתון עם אופטימיזציות זיכרון ומניעת דף ריק
     * עכשיו עם הגבלת כמות שורות לפי הגדרות המשתמש
     */
    suspend fun loadLogsForRange(
        centerIndex: Int, 
        displayCount: Int,
        isScrollInProgress: Boolean,
        force: Boolean = false,
        isDragScrolling: Boolean = false
    ): Map<Int, LogEntry> {
        
        // החלון מוגבל תמיד לגודל הבאצ'
        val windowDisplayCount = minOf(displayCount, settings.batchSize)
        
        updateScrollMetrics(centerIndex, isScrollInProgress)
        
        val windowSize = calculateOptimalWindowSize(isScrollInProgress, isDragScrolling)
        val range = calculateLoadRange(centerIndex, windowDisplayCount, windowSize)
        
        // בדוק אם צריך לטעון
        if (!force && cache.isRangeCached(range, 0.7f) && !isLoading) {
            val cachedLogs = cache.getLogs(range)
            
            // וודא שיש לוגים בטווח הנראה
            val visibleRange = (centerIndex - 15)..(centerIndex + 15)
            val hasVisibleLogs = visibleRange.any { cachedLogs.containsKey(it) }
            
            if (hasVisibleLogs) {
                return cachedLogs
            }
        }
        
        // ביטול job קודם אם צריך
        if ((isScrollInProgress || force) && loadingJob?.isActive == true) {
            loadingJob?.cancel()
        }
        
        // טעינה מיידית לטווח הנראה - מותאם לחלון הנוכחי
        val visibleRange = if (isDragScrolling) {
            val size = minOf(settings.getWindowSize() / 4, windowDisplayCount / 4)
            (centerIndex - size)..(centerIndex + size)
        } else if (isScrollInProgress) {
            val size = minOf(settings.getWindowSize() / 6, windowDisplayCount / 6)
            (centerIndex - size)..(centerIndex + size)
        } else {
            val size = minOf(settings.getWindowSize() / 8, windowDisplayCount / 8)
            (centerIndex - size)..(centerIndex + size)
        }
        
        val visibleLogs = loadVisibleRangeImmediate(visibleRange, windowDisplayCount)
        
        // טעינה ברקע לשאר הטווח - רק בתוך החלון הנוכחי
        if (settings.enablePreloading && loadingJob?.isActive != true) {
            loadingJob = scope.launch {
                if (isActive) {
                    loadRangeWithPriority(range, centerIndex, isScrollInProgress)
                }
            }
        }
        
        // החזר את הלוגים הנראים מיד + מה שיש ב-cache
        val allLogs = cache.getLogs(range).toMutableMap()
        allLogs.putAll(visibleLogs)
        
        return allLogs
    }
    
    /**
     * עדכון מטריקות גלילה
     */
    private fun updateScrollMetrics(centerIndex: Int, isScrollInProgress: Boolean) {
        val now = System.currentTimeMillis()
        val scrollDistance = centerIndex - lastScrollIndex
        val timeDelta = now - lastScrollTime
        
        if (timeDelta > 0 && scrollDistance != 0 && isScrollInProgress) {
            scrollVelocity = kotlin.math.abs(scrollDistance.toFloat() / timeDelta.toFloat())
            scrollHistory.add(now to centerIndex)
            
            // זיהוי גלילה מהירה מאוד (יותר מ-100 שורות בשנייה)
            isUltraFastScrolling = scrollVelocity > 0.1f
            
            // שמור רק 3 מדידות אחרונות (חיסכון בזיכרון)
            if (scrollHistory.size > 3) {
                scrollHistory.removeAt(0)
            }
        } else if (!isScrollInProgress) {
            // כשהגלילה נעצרת, אפס את הדגל
            isUltraFastScrolling = false
        }
        
        lastScrollTime = now
        lastScrollIndex = centerIndex
    }
    
    /**
     * חישוב גודל חלון אופטימלי לפי מהירות גלילה והגדרות המשתמש
     */
    private fun calculateOptimalWindowSize(isScrollInProgress: Boolean, isDragScrolling: Boolean = false): Int {
        if (!isScrollInProgress) return baseWindowSize
        
        // אם הטעינה החכמה מבוטלת, השתמש בגודל קבוע
        if (!settings.enableSmartLoading) {
            return baseWindowSize
        }
        
        // בגלילה בעת גרירה לבחירה, השתמש בחלון מתון לחיסכון במשאבים
        if (isDragScrolling) {
            return maxWindowSize // חלון מתון לגלילה חלקה אך חסכונית
        }
        
        val avgVelocity = calculateAverageVelocity()
        val speedMultiplier = settings.scrollSpeed / 5.0f // נרמול לפי הגדרות המשתמש
        
        return when {
            avgVelocity > 1.5f * speedMultiplier -> ultraFastWindowSize
            avgVelocity > 0.6f * speedMultiplier -> maxWindowSize
            avgVelocity > 0.2f * speedMultiplier -> baseWindowSize * 2
            else -> baseWindowSize
        }
    }
    
    /**
     * חישוב מהירות ממוצעת
     */
    private fun calculateAverageVelocity(): Float {
        if (scrollHistory.size < 2) return scrollVelocity
        
        val recent = scrollHistory.takeLast(3)
        val totalDistance = kotlin.math.abs(recent.last().second - recent.first().second)
        val totalTime = recent.last().first - recent.first().first
        
        return if (totalTime > 0) totalDistance.toFloat() / totalTime.toFloat() else 0f
    }
    
    /**
     * חישוב טווח טעינה עם bias לכיוון גלילה
     */
    private fun calculateLoadRange(centerIndex: Int, displayCount: Int, windowSize: Int): IntRange {
        val scrollDirection = if (lastScrollIndex != 0) {
            if (centerIndex > lastScrollIndex) 1 else -1
        } else 0
        
        // bias קטן יותר לחיסכון בזיכרון
        val bias = if (scrollDirection != 0) scrollDirection * (windowSize / 6) else 0
        
        val halfWindow = windowSize / 2
        val startIndex = maxOf(0, centerIndex - halfWindow + bias)
        val endIndex = minOf(displayCount - 1, centerIndex + halfWindow + bias)
        
        return startIndex..endIndex
    }
    
    /**
     * טעינה מיידית לטווח הנראה למניעת דף ריק - מותאמת לחלונות
     */
    private suspend fun loadVisibleRangeImmediate(
        visibleRange: IntRange,
        displayCount: Int
    ): Map<Int, LogEntry> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, LogEntry>()
        
        try {
            val startIdx = maxOf(0, visibleRange.first)
            val endIdx = minOf(displayCount - 1, visibleRange.last)
            val count = endIdx - startIdx + 1
            
            if (count > 0) {
                // טען בחלקים מותאמים למהירות הגלילה
                val chunkSize = when {
                    isUltraFastScrolling -> 100  // חלקים גדולים יותר לגלילה מהירה
                    scrollVelocity > 0.05f -> 75  // חלקים בינוניים
                    else -> 50  // חלקים קטנים לגלילה רגילה
                }
                
                // טען בחלקים מקבילים אם מותר
                val maxConcurrentLoads = settings.getMaxConcurrentLoads()
                
                if (maxConcurrentLoads > 1 && count > chunkSize * 2) {
                    // טעינה מקבילית לביצועים טובים יותר
                    val chunks = (startIdx..endIdx step chunkSize).map { chunkStart ->
                        val chunkEnd = minOf(chunkStart + chunkSize - 1, endIdx)
                        chunkStart to chunkEnd
                    }
                    
                    chunks.chunked(maxConcurrentLoads).forEach { chunkBatch ->
                        val jobs = chunkBatch.map { (chunkStart, chunkEnd) ->
                            async {
                                val chunkCount = chunkEnd - chunkStart + 1
                                if (chunkCount > 0) {
                                    val logs = viewModel.getLogsPage(chunkStart, chunkCount)
                                    logs.forEachIndexed { idx, log ->
                                        result[chunkStart + idx] = log
                                    }
                                    cache.putLogs(chunkStart, logs)
                                }
                            }
                        }
                        jobs.forEach { it.await() }
                    }
                } else {
                    // טעינה סדרתית רגילה
                    for (chunkStart in startIdx..endIdx step chunkSize) {
                        val chunkEnd = minOf(chunkStart + chunkSize - 1, endIdx)
                        val chunkCount = chunkEnd - chunkStart + 1
                        
                        if (chunkCount > 0) {
                            val logs = viewModel.getLogsPage(chunkStart, chunkCount)
                            logs.forEachIndexed { idx, log ->
                                result[chunkStart + idx] = log
                            }
                            
                            // שמור ב-cache מיידית
                            cache.putLogs(chunkStart, logs)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // במקרה של שגיאה, לא נחזיר רשימה ריקה אלא נתעלם מהשגיאה
        }
        
        return@withContext result
    }

    /**
     * טעינה בעדיפויות עם חיסכון בזיכרון - מותאמת לחלונות
     */
    private suspend fun loadRangeWithPriority(
        range: IntRange, 
        centerIndex: Int, 
        isScrollInProgress: Boolean
    ) = withContext(Dispatchers.IO) {
        if (!coroutineContext.isActive) return@withContext
        
        isLoading = true
        try {
            // עדיפות 1: טווח נראה מיידי - מותאם למהירות גלילה
            val visibleSize = when {
                isUltraFastScrolling -> 30  // טווח גדול יותר לגלילה מהירה
                scrollVelocity > 0.05f -> 20  // טווח בינוני
                else -> 15  // טווח קטן לגלילה רגילה
            }
            
            val visibleRange = (centerIndex - visibleSize)..(centerIndex + visibleSize)
            val priorityRange = range.intersect(visibleRange.toSet()).let { 
                if (it.isEmpty()) range.take(visibleSize * 2) else it.toList() 
            }
            
            if (priorityRange.isNotEmpty()) {
                val startIdx = priorityRange.minOrNull() ?: range.first
                val endIdx = priorityRange.maxOrNull() ?: range.first + (visibleSize * 2 - 1)
                
                // בדוק אם הטווח הזה כבר נטען
                val needsLoading = (startIdx..endIdx).any { cache.getLog(it) == null }
                
                if (needsLoading) {
                    val logs = viewModel.getLogsPage(startIdx, endIdx - startIdx + 1)
                    if (logs.isNotEmpty()) {
                        cache.putLogs(startIdx, logs)
                    }
                }
            }
            
            // עדיפות 2: שאר הטווח בחלקים - מותאם למהירות
            val delayTime = when {
                isUltraFastScrolling -> 5L   // עיכוב מינימלי לגלילה מהירה
                isScrollInProgress -> 10L    // עיכוב קטן לגלילה רגילה
                else -> 30L                  // עיכוב גדול יותר כשלא גוללים
            }
            delay(delayTime)
            
            val remainingRange = range.subtract(priorityRange.toSet())
            val chunkSize = when {
                isUltraFastScrolling -> 150  // חלקים גדולים לגלילה מהירה
                isScrollInProgress -> 100    // חלקים בינוניים
                else -> 75                   // חלקים קטנים יותר
            }
            
            // טעינה מקבילית אם מותר ויש הרבה נתונים
            val maxConcurrentLoads = settings.getMaxConcurrentLoads()
            val chunks = remainingRange.chunked(chunkSize)
            
            if (maxConcurrentLoads > 1 && chunks.size > 2) {
                // טעינה מקבילית
                chunks.chunked(maxConcurrentLoads).forEach { chunkBatch ->
                    if (!coroutineContext.isActive) return@forEach
                    
                    val jobs = chunkBatch.map { chunk ->
                        async {
                            val chunkStart = chunk.minOrNull() ?: return@async
                            val chunkEnd = chunk.maxOrNull() ?: return@async
                            
                            val chunkNeedsLoading = (chunkStart..chunkEnd).any { cache.getLog(it) == null }
                            
                            if (chunkNeedsLoading) {
                                val logs = viewModel.getLogsPage(chunkStart, chunkEnd - chunkStart + 1)
                                if (logs.isNotEmpty()) {
                                    cache.putLogs(chunkStart, logs)
                                }
                            }
                        }
                    }
                    
                    jobs.forEach { it.await() }
                    
                    // עיכוב קטן בין batch-ים
                    if (isScrollInProgress) {
                        delay(3)
                    } else {
                        delay(15)
                    }
                }
            } else {
                // טעינה סדרתית
                chunks.forEach { chunk ->
                    if (!coroutineContext.isActive) return@forEach
                    
                    val chunkStart = chunk.minOrNull() ?: return@forEach
                    val chunkEnd = chunk.maxOrNull() ?: return@forEach
                    
                    val chunkNeedsLoading = (chunkStart..chunkEnd).any { cache.getLog(it) == null }
                    
                    if (chunkNeedsLoading) {
                        val logs = viewModel.getLogsPage(chunkStart, chunkEnd - chunkStart + 1)
                        if (logs.isNotEmpty()) {
                            cache.putLogs(chunkStart, logs)
                        }
                    }
                    
                    // עיכוב קטן בין chunks
                    if (isScrollInProgress) {
                        delay(2)
                    } else {
                        delay(10)
                    }
                }
            }
            
        } finally {
            isLoading = false
        }
    }
    
    /**
     * טוען חלון ספציפי של לוגים - מותאם לביצועים מקסימליים
     */
    suspend fun loadWindow(windowIndex: Int, windowSize: Int): Map<Int, LogEntry> {
        // נקה cache קודם כדי לחסוך זיכרון
        clearCache()
        
        // אפס מטריקות גלילה לחלון החדש
        lastScrollTime = 0L
        lastScrollIndex = 0
        scrollVelocity = 0f
        scrollHistory.clear()
        isUltraFastScrolling = false
        
        // חישוב טווח החלון (יחסי לחלון, לא מוחלט)
        val range = 0 until windowSize
        
        // טען את החלון החדש בחלקים אופטימליים
        return loadVisibleRangeImmediate(range, windowSize)
    }
    
    /**
     * מחזיר לוג לפי אינדקס
     */
    fun getLog(index: Int): LogEntry? = cache.getLog(index)
    
    /**
     * ניקוי cache
     */
    fun clearCache() = cache.clear()
    
    /**
     * סטטיסטיקות
     */
    fun getCacheStats() = cache.getStats()
    
    /**
     * ניקוי משאבים
     */
    fun cleanup() {
        loadingJob?.cancel()
        cache.clear()
    }
}

// Extension function לחישוב intersection
private fun IntRange.intersect(other: Set<Int>): Set<Int> {
    return this.filter { it in other }.toSet()
}