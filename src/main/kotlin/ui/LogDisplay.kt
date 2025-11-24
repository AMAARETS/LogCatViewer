package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import models.LogEntry
import LogcatViewModelNew
import scroll.ScrollManager
import utils.copyToClipboard

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogDisplay(viewModel: LogcatViewModelNew) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Use derivedStateOf to avoid unnecessary recompositions
    val displayCount by remember {
        derivedStateOf { viewModel.state.filteredLogCount.value }
    }
    
    val autoScroll by remember {
        derivedStateOf { viewModel.state.autoScroll.value }
    }
    
    // Selection state for multi-select
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var lastHoveredIndex by remember { mutableStateOf<Int?>(null) }
    
    // Auto-scroll state
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var lastDragLoadTime by remember { mutableStateOf(0L) }
    
    // Pre-loading job for smoother scrolling
    var preloadJob by remember { mutableStateOf<Job?>(null) }
    
    // מערכת גלילה חכמה עם ניהול זיכרון
    val scrollManager = remember { scroll.ScrollManager(viewModel, scope) }
    var cachedLogs by remember { mutableStateOf<Map<Int, LogEntry>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    
    // State for immediate UI updates
    var immediateCache by remember { mutableStateOf<Map<Int, LogEntry>>(emptyMap()) }
    
    // טעינת לוגים מותאמת לחיסכון במשאבים
    var loadingJob by remember { mutableStateOf<Job?>(null) }
    
    fun loadLogsForRange(centerIndex: Int, force: Boolean = false) {
        // מנע טעינות מרובות במקביל
        if (isLoading && !force) return
        
        // בטל job קודם אם יש
        loadingJob?.cancel()
        
        loadingJob = scope.launch {
            isLoading = true
            try {
                val newLogs = scrollManager.loadLogsForRange(
                    centerIndex = centerIndex,
                    displayCount = displayCount,
                    isScrollInProgress = listState.isScrollInProgress,
                    force = force,
                    isDragScrolling = isDragging
                )
                
                // עדכון מיידי של ה-cache
                immediateCache = newLogs
                cachedLogs = newLogs
                
            } finally {
                isLoading = false
            }
        }
    }
    
    // Ultra-responsive scroll monitoring with velocity tracking
    LaunchedEffect(Unit) {
        snapshotFlow { 
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (index, _, isScrolling) ->
            val visibleIndex = index + 15  // Slightly ahead for smoother experience
            
            // Immediate loading for fast scrolling - no delay
            if (isScrolling) {
                loadLogsForRange(visibleIndex)
            } else {
                // Immediate loading when idle too - no delay
                loadLogsForRange(visibleIndex, force = true)
            }
        }
    }
    
    // Reduced frequency monitoring to prevent resource overload
    LaunchedEffect(Unit) {
        while (true) {
            if (listState.isScrollInProgress && isDragging) {
                val currentIndex = listState.firstVisibleItemIndex + 15
                
                // Load only during drag scrolling to reduce overhead
                loadLogsForRange(currentIndex, force = true)
                
                delay(30)  // Reduced frequency to prevent coroutine cancellations
            } else {
                delay(200)  // Much slower when not dragging
            }
        }
    }
    
    // מנגנון נוסף לוודא שהתצוגה מתעדכנת אחרי גלילה מהירה
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }.collect { isScrolling ->
            if (!isScrolling) {
                // כשהגלילה נעצרת, כפה טעינה מחדש של הטווח הנראה
                delay(100) // המתן קצת שהגלילה תתייצב
                val centerIndex = listState.firstVisibleItemIndex + 15
                loadLogsForRange(centerIndex, force = true)
            }
        }
    }
    
    // זיהוי מצב "דף ריק" ותיקון אוטומטי + ניקוי זיכרון
    LaunchedEffect(Unit) {
        var cleanupCounter = 0
        while (true) {
            delay(1000) // בדוק כל שנייה (פחות תכוף)
            
            if (!listState.isScrollInProgress && displayCount > 0 && !isLoading) {
                val visibleRange = listState.firstVisibleItemIndex..(listState.firstVisibleItemIndex + 30)
                val visibleLogsCount = visibleRange.count { cachedLogs.containsKey(it) }
                
                // אם יש פחות מ-3 לוגים נראים מתוך 30, זה כנראה "דף ריק"
                if (visibleLogsCount < 3) {
                    val centerIndex = listState.firstVisibleItemIndex + 15
                    loadLogsForRange(centerIndex, force = true)
                }
                
                // ניקוי זיכרון תקופתי - כל 10 שניות
                cleanupCounter++
                if (cleanupCounter >= 10) {
                    cleanupCounter = 0
                    // נקה את ה-cache הישן
                    scrollManager.clearCache()
                    // טען מחדש את הטווח הנוכחי
                    val centerIndex = listState.firstVisibleItemIndex + 15
                    loadLogsForRange(centerIndex, force = true)
                }
            }
        }
    }
    
    // טעינה ראשונית ושינויי פילטרים + ניקוי זיכרון
    LaunchedEffect(
        viewModel.filterState.searchText.value,
        viewModel.filterState.selectedLevels.value,
        viewModel.filterState.tagFilter.value,
        displayCount
    ) {
        // ניקוי מלא של הזיכרון
        scrollManager.clearCache()
        immediateCache = emptyMap()
        cachedLogs = emptyMap()
        
        // כפה garbage collection
        System.gc()
        
        val targetIndex = if (autoScroll) maxOf(0, displayCount - 1) else listState.firstVisibleItemIndex
        loadLogsForRange(targetIndex, force = true)
    }
    
    // Auto-scroll to bottom on new logs
    LaunchedEffect(viewModel.state.lastLogUpdate.value) {
        if (autoScroll && displayCount > 0) {
            listState.scrollToItem(maxOf(0, displayCount - 1))
        }
    }
    
    // ניקוי משאבים כשהרכיב נהרס
    DisposableEffect(Unit) {
        onDispose {
            // ביטול כל ה-jobs
            autoScrollJob?.cancel()
            preloadJob?.cancel()
            loadingJob?.cancel()
            scrollManager.cleanup()
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        if (displayCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "אין לוגים להצגה",
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        } else {
            val backgroundColor = remember { Color(0xFF1E1E1E) }
            val placeholderColor = remember { Color(0xFF252525) }
            val placeholderShape = remember { RoundedCornerShape(2.dp) }
            
            // Wrap with ContextMenuArea for multi-select menu
            ContextMenuArea(
                items = {
                    if (selectedIndices.isNotEmpty()) {
                        listOf(
                            ContextMenuItem("העתק ${selectedIndices.size} שורות") {
                                val selectedLogs = selectedIndices.sorted().mapNotNull { 
                                    immediateCache[it] ?: cachedLogs[it] ?: scrollManager.getLog(it)
                                }
                                val text = selectedLogs.joinToString("\n") { log ->
                                    "${log.timestamp} ${log.pid}/${log.tid} ${log.level.displayName}/${log.tag}: ${log.message}"
                                }
                                copyToClipboard(text)
                                selectedIndices = emptySet()
                            },
                            ContextMenuItem("בטל בחירה") {
                                selectedIndices = emptySet()
                            }
                        )
                    } else {
                        emptyList()
                    }
                }
            ) {
                // Force LTR layout for log items
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    var currentMouseY: Float
                                    var currentViewportHeight: Float
                                    
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        when (event.type) {
                                            PointerEventType.Release -> {
                                                if (event.button == PointerButton.Primary && isDragging) {
                                                    isDragging = false
                                                    dragStartIndex = null
                                                    // Stop auto-scroll and preloading
                                                    autoScrollJob?.cancel()
                                                    autoScrollJob = null
                                                    preloadJob?.cancel()
                                                    preloadJob = null
                                                    loadingJob?.cancel()
                                                    loadingJob = null
                                                }
                                            }
                                            PointerEventType.Move -> {
                                                if (event.buttons.isPrimaryPressed && isDragging && dragStartIndex != null) {
                                                    val position = event.changes.first().position
                                                    currentMouseY = position.y
                                                    currentViewportHeight = size.height.toFloat()
                                                    
                                                    val itemHeight = 30.dp.toPx()
                                                    val scrollOffset = listState.firstVisibleItemScrollOffset.toFloat()
                                                    val firstVisibleIndex = listState.firstVisibleItemIndex
                                                    
                                                    // Auto-scroll zones - larger zones for better UX
                                                    val autoScrollZone = 80.dp.toPx()
                                                    val extremeScrollZone = 30.dp.toPx()
                                                    
                                                    // Calculate scroll speed based on distance from edge - much more aggressive
                                                    val scrollSpeed = when {
                                                        currentMouseY < autoScrollZone -> {
                                                            // Scrolling up - exponential speed increase
                                                            val distance = autoScrollZone - currentMouseY
                                                            val speedFactor = (distance / autoScrollZone).coerceIn(0f, 1f)
                                                            val baseSpeed = if (distance < extremeScrollZone) {
                                                                // Extreme zone - moderate fast scrolling
                                                                -(4 + speedFactor * 8)
                                                            } else {
                                                                // Normal zone - progressive speed
                                                                -(1 + speedFactor * 6)
                                                            }
                                                            baseSpeed
                                                        }
                                                        currentMouseY > currentViewportHeight - autoScrollZone -> {
                                                            // Scrolling down - exponential speed increase
                                                            val distance = currentMouseY - (currentViewportHeight - autoScrollZone)
                                                            val speedFactor = (distance / autoScrollZone).coerceIn(0f, 1f)
                                                            val baseSpeed = if (distance < extremeScrollZone) {
                                                                // Extreme zone - moderate fast scrolling
                                                                4 + speedFactor * 8
                                                            } else {
                                                                // Normal zone - progressive speed
                                                                1 + speedFactor * 6
                                                            }
                                                            baseSpeed
                                                        }
                                                        else -> 0f
                                                    }
                                                    
                                                    // Start or stop auto-scroll
                                                    if (scrollSpeed != 0f && autoScrollJob == null) {
                                                        autoScrollJob = scope.launch {
                                                            while (isDragging) {
                                                                val mouseY = currentMouseY
                                                                val viewHeight = currentViewportHeight
                                                                val zone = 80.dp.toPx()
                                                                val extremeZone = 30.dp.toPx()
                                                                
                                                                val speed = when {
                                                                    mouseY < zone -> {
                                                                        val dist = zone - mouseY
                                                                        val factor = (dist / zone).coerceIn(0f, 1f)
                                                                        if (dist < extremeZone) {
                                                                            // Extreme zone - moderate fast scrolling
                                                                            -(4 + factor * 8)
                                                                        } else {
                                                                            // Normal zone - progressive speed
                                                                            -(1 + factor * 6)
                                                                        }
                                                                    }
                                                                    mouseY > viewHeight - zone -> {
                                                                        val dist = mouseY - (viewHeight - zone)
                                                                        val factor = (dist / zone).coerceIn(0f, 1f)
                                                                        if (dist < extremeZone) {
                                                                            // Extreme zone - moderate fast scrolling
                                                                            4 + factor * 8
                                                                        } else {
                                                                            // Normal zone - progressive speed
                                                                            1 + factor * 6
                                                                        }
                                                                    }
                                                                    else -> 0f
                                                                }
                                                                
                                                                if (speed != 0f && dragStartIndex != null) {
                                                                    val currentIndex = listState.firstVisibleItemIndex
                                                                    val targetIndex = (currentIndex + speed.toInt())
                                                                        .coerceIn(0, displayCount - 1)
                                                                    
                                                                    // Only scroll if we're not at the edge or if we can still move
                                                                    if (targetIndex != currentIndex) {
                                                                        // Smooth scrolling with immediate log loading
                                                                        listState.scrollToItem(targetIndex)
                                                                    
                                                                    // Force immediate loading of logs for smooth display during fast scroll
                                                                        // Throttled loading to prevent resource overload
                                                                        val currentTime = System.currentTimeMillis()
                                                                        if (currentTime - lastDragLoadTime > 100) { // Max 10 loads per second to prevent cancellations
                                                                            lastDragLoadTime = currentTime
                                                                            loadLogsForRange(targetIndex, force = true)
                                                                        }
                                                                    
                                                                        // Update selection based on current scroll position
                                                                        val relY = mouseY + listState.firstVisibleItemScrollOffset.toFloat()
                                                                        val hovIdx = listState.firstVisibleItemIndex + (relY / itemHeight).toInt()
                                                                        val clampIdx = hovIdx.coerceIn(0, displayCount - 1)
                                                                        
                                                                        lastHoveredIndex = clampIdx
                                                                        val start = minOf(dragStartIndex!!, clampIdx)
                                                                        val end = maxOf(dragStartIndex!!, clampIdx)
                                                                        selectedIndices = (start..end).toSet()
                                                                    } else {
                                                                        // At edge - still update selection but don't scroll
                                                                        val relY = mouseY + listState.firstVisibleItemScrollOffset.toFloat()
                                                                        val hovIdx = listState.firstVisibleItemIndex + (relY / itemHeight).toInt()
                                                                        val clampIdx = hovIdx.coerceIn(0, displayCount - 1)
                                                                        
                                                                        lastHoveredIndex = clampIdx
                                                                        val start = minOf(dragStartIndex!!, clampIdx)
                                                                        val end = maxOf(dragStartIndex!!, clampIdx)
                                                                        selectedIndices = (start..end).toSet()
                                                                    }
                                                                    
                                                                    // Balanced delay to prevent resource overload while maintaining smoothness
                                                                    val absSpeed = kotlin.math.abs(speed)
                                                                    val delayMs = when {
                                                                        absSpeed > 15f -> 20L  // Fast scrolling - reasonable delay
                                                                        absSpeed > 8f -> 30L   // Medium-fast scrolling
                                                                        absSpeed > 4f -> 40L   // Medium scrolling
                                                                        else -> 60L            // Slow scrolling
                                                                    }
                                                                    delay(delayMs)
                                                                } else {
                                                                    break
                                                                }
                                                            }
                                                            autoScrollJob = null
                                                        }
                                                    } else if (scrollSpeed == 0f && autoScrollJob != null) {
                                                        autoScrollJob?.cancel()
                                                        autoScrollJob = null
                                                    }
                                                    
                                                    // Calculate index based on Y position
                                                    val relativeY = position.y + scrollOffset
                                                    val hoveredIndex = firstVisibleIndex + (relativeY / itemHeight).toInt()
                                                    val clampedIndex = hoveredIndex.coerceIn(0, displayCount - 1)
                                                    
                                                    if (clampedIndex != lastHoveredIndex) {
                                                        lastHoveredIndex = clampedIndex
                                                        val start = minOf(dragStartIndex!!, clampedIndex)
                                                        val end = maxOf(dragStartIndex!!, clampedIndex)
                                                        selectedIndices = (start..end).toSet()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                    LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(8.dp)
                ) {
                    items(
                        count = displayCount,
                        key = { index -> 
                            // Use log ID if available for better key stability
                            cachedLogs[index]?.id ?: scrollManager.getLog(index)?.id ?: index 
                        },
                        contentType = { "LogEntry" }
                    ) { index ->
                        // קבל לוג מה-cache המיידי או מה-cache הרגיל או מה-ScrollManager
                        val log = immediateCache[index] ?: cachedLogs[index] ?: scrollManager.getLog(index)
                        if (log != null) {
                            LogItemWithSelection(
                                log = log,
                                isSelected = selectedIndices.contains(index),
                                isDragging = isDragging,
                                onSelectionStart = { modifiers ->
                                    when {
                                        modifiers.isCtrlPressed -> {
                                            // Toggle selection with Ctrl
                                            selectedIndices = if (selectedIndices.contains(index)) {
                                                selectedIndices - index
                                            } else {
                                                selectedIndices + index
                                            }
                                            dragStartIndex = null
                                            isDragging = false
                                        }
                                        modifiers.isShiftPressed && dragStartIndex != null -> {
                                            // Extend selection with Shift
                                            val start = minOf(dragStartIndex!!, index)
                                            val end = maxOf(dragStartIndex!!, index)
                                            selectedIndices = (start..end).toSet()
                                            isDragging = false
                                        }
                                        modifiers.isShiftPressed && selectedIndices.isNotEmpty() -> {
                                            // Extend from last selected item
                                            val lastSelected = selectedIndices.maxOrNull() ?: index
                                            val start = minOf(lastSelected, index)
                                            val end = maxOf(lastSelected, index)
                                            selectedIndices = (start..end).toSet()
                                            dragStartIndex = lastSelected
                                            isDragging = false
                                        }
                                        else -> {
                                            // Start new selection (for dragging)
                                            selectedIndices = setOf(index)
                                            dragStartIndex = index
                                            isDragging = true
                                            
                                            // Simplified preloading to reduce resource usage
                                            preloadJob = scope.launch {
                                                // Smaller preload range to prevent overload
                                                val preloadRange = (index - 50)..(index + 50)
                                                for (preloadIndex in preloadRange step 25) {
                                                    if (isDragging && preloadIndex in 0 until displayCount) {
                                                        scrollManager.loadLogsForRange(
                                                            centerIndex = preloadIndex,
                                                            displayCount = displayCount,
                                                            isScrollInProgress = false,
                                                            force = false,
                                                            isDragScrolling = true
                                                        )
                                                        delay(50) // Longer delay to prevent overwhelming
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onDragHover = {
                                    // Drag hover is now handled globally in the Box modifier above
                                    // This callback is kept for compatibility but does nothing
                                },
                                onDragEnd = {
                                    // End dragging but keep selection
                                    isDragging = false
                                    dragStartIndex = null
                                },
                                onContextMenu = { _ ->
                                    // Context menu is handled by ContextMenuArea wrapper
                                }
                            )
                        } else {
                            // אם אין לוג, נסה לטעון אותו מיידית
                            LaunchedEffect(index) {
                                if (!isLoading) {
                                    loadLogsForRange(index, force = true)
                                }
                            }
                            
                            // Ultra-lightweight placeholder with minimal overhead
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .background(placeholderColor, placeholderShape)
                            )
                        }
                    }
                }
                    }
                }
            }
            
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(listState)
            )
            
            // Loading indicator
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF2196F3)
                    )
                }
            }
            

        }
    }
}
