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
import LogEntry
import LogcatViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogDisplay(viewModel: LogcatViewModel) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Use derivedStateOf to avoid unnecessary recompositions
    val displayCount by remember {
        derivedStateOf { viewModel.filteredLogCount.value }
    }
    
    val autoScroll by remember {
        derivedStateOf { viewModel.autoScroll.value }
    }
    
    // Selection state for multi-select
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var lastHoveredIndex by remember { mutableStateOf<Int?>(null) }
    
    // Auto-scroll state
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    
    // Smart adaptive cache - grows/shrinks based on scroll speed
    val baseWindowSize = 400  // Optimized for smooth scrolling
    val maxWindowSize = 2500  // Large enough for fast scrolling, not wasteful
    var currentWindowSize by remember { mutableStateOf(baseWindowSize) }
    var cachedLogs by remember { mutableStateOf<Map<Int, LogEntry>>(emptyMap()) }
    var cachedRange by remember { mutableStateOf(0..0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingJob by remember { mutableStateOf<Job?>(null) }
    var lastScrollTime by remember { mutableStateOf(0L) }
    var lastScrollIndex by remember { mutableStateOf(0) }
    var lastScrollDirection by remember { mutableStateOf(0) } // 1=down, -1=up, 0=none
    
    // Smart load with adaptive window and predictive prefetching
    fun loadLogsForRange(centerIndex: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val scrollDistance = centerIndex - lastScrollIndex
        
        // Detect scroll direction and speed
        if (scrollDistance != 0) {
            lastScrollDirection = if (scrollDistance > 0) 1 else -1
        }
        
        val isScrollingFast = listState.isScrollInProgress && kotlin.math.abs(scrollDistance) > 50
        val isScrollingSlow = listState.isScrollInProgress && kotlin.math.abs(scrollDistance) > 0
        val isIdle = !listState.isScrollInProgress
        
        // Adjust window size based on scroll speed - MUCH larger for fast scrolling
        currentWindowSize = when {
            isScrollingFast -> maxWindowSize  // Very large window
            isScrollingSlow -> baseWindowSize * 4  // Large for slow scroll
            else -> baseWindowSize  // Normal when idle
        }
        
        // Predictive prefetching - load ahead in scroll direction
        val prefetchBias = if (listState.isScrollInProgress) {
            lastScrollDirection * (currentWindowSize / 3)  // Load more in scroll direction
        } else 0
        
        val halfWindow = currentWindowSize / 2
        val startIndex = maxOf(0, centerIndex - halfWindow + prefetchBias)
        val endIndex = minOf(displayCount - 1, centerIndex + halfWindow + prefetchBias)
        
        // More aggressive preload margin
        val preloadMargin = currentWindowSize / 3
        
        val needsReload = force || 
            centerIndex !in cachedRange || 
            centerIndex < cachedRange.first + preloadMargin || 
            centerIndex > cachedRange.last - preloadMargin
        
        // Skip if not needed or already loading same range
        if (!needsReload || (isLoading && !force)) return
        
        // Cancel previous job
        loadingJob?.cancel()
        
        lastScrollTime = now
        lastScrollIndex = centerIndex
        
        loadingJob = scope.launch {
            // NO delay during scroll - immediate loading!
            if (isIdle && !force) {
                delay(30)  // Tiny delay only when idle
            }
            
            if (!isActive) return@launch
            
            isLoading = true
            try {
                val currentCount = displayCount
                if (currentCount == 0) {
                    cachedLogs = emptyMap()
                    cachedRange = 0..0
                    return@launch
                }
                
                val newRange = startIndex..endIndex
                
                // Smart progressive loading with priority
                // Start with existing cache - keep overlapping entries
                val baseCache = cachedLogs.filterKeys { it in newRange }.toMutableMap()
                
                // Calculate visible range for priority loading
                val visibleStart = listState.firstVisibleItemIndex
                val visibleEnd = visibleStart + 50  // ~50 visible items
                
                // Priority 1: Load visible area FIRST (small chunk for instant display)
                val priorityStart = maxOf(startIndex, visibleStart - 10)
                val priorityEnd = minOf(endIndex, visibleEnd + 10)
                
                if (priorityStart <= priorityEnd) {
                    val priorityLogs = viewModel.getLogsPage(priorityStart, priorityEnd - priorityStart + 1)
                    priorityLogs.forEachIndexed { idx, log ->
                        baseCache[priorityStart + idx] = log
                    }
                    // Update UI immediately with visible content
                    cachedLogs = baseCache.toMap()
                    cachedRange = priorityStart..priorityEnd
                }
                
                // Priority 2: Load rest in larger chunks (background fill)
                val chunkSize = 800  // Larger chunks for non-visible area
                
                // Load before visible area
                var currentOffset = startIndex
                while (currentOffset < priorityStart && isActive) {
                    val chunkEnd = minOf(priorityStart - 1, currentOffset + chunkSize - 1)
                    val chunkLogs = viewModel.getLogsPage(currentOffset, chunkEnd - currentOffset + 1)
                    
                    chunkLogs.forEachIndexed { idx, log ->
                        baseCache[currentOffset + idx] = log
                    }
                    
                    cachedLogs = baseCache.toMap()
                    cachedRange = startIndex..maxOf(cachedRange.last, chunkEnd)
                    
                    currentOffset = chunkEnd + 1
                }
                
                // Load after visible area
                currentOffset = priorityEnd + 1
                while (currentOffset <= endIndex && isActive) {
                    val chunkEnd = minOf(endIndex, currentOffset + chunkSize - 1)
                    val chunkLogs = viewModel.getLogsPage(currentOffset, chunkEnd - currentOffset + 1)
                    
                    chunkLogs.forEachIndexed { idx, log ->
                        baseCache[currentOffset + idx] = log
                    }
                    
                    cachedLogs = baseCache.toMap()
                    cachedRange = startIndex..chunkEnd
                    
                    currentOffset = chunkEnd + 1
                }
                
                // Final update
                cachedLogs = baseCache
                cachedRange = newRange
            } finally {
                isLoading = false
            }
        }
    }
    
    // Monitor scroll position with snapshotFlow for immediate response
    LaunchedEffect(Unit) {
        snapshotFlow { 
            listState.firstVisibleItemIndex to listState.isScrollInProgress 
        }.collect { (index, _) ->
            val visibleIndex = index + 10
            loadLogsForRange(visibleIndex)
        }
    }
    
    // Initial load and filter changes
    LaunchedEffect(
        viewModel.searchText.value,
        viewModel.selectedLevels.value,
        viewModel.tagFilter.value,
        displayCount
    ) {
        cachedLogs = emptyMap()
        cachedRange = 0..0
        val targetIndex = if (autoScroll) maxOf(0, displayCount - 1) else listState.firstVisibleItemIndex
        loadLogsForRange(targetIndex, force = true)
    }
    
    // Auto-scroll to bottom on new logs
    LaunchedEffect(viewModel.lastLogUpdate.value) {
        if (autoScroll && displayCount > 0) {
            listState.scrollToItem(maxOf(0, displayCount - 1))
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
                                val selectedLogs = selectedIndices.sorted().mapNotNull { cachedLogs[it] }
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
                                                    // Stop auto-scroll
                                                    autoScrollJob?.cancel()
                                                    autoScrollJob = null
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
                                                    
                                                    // Auto-scroll zones (top and bottom 50px)
                                                    val autoScrollZone = 50.dp.toPx()
                                                    
                                                    // Calculate scroll speed based on distance from edge
                                                    val scrollSpeed = when {
                                                        currentMouseY < autoScrollZone -> {
                                                            // Scrolling up
                                                            val distance = autoScrollZone - currentMouseY
                                                            val speedFactor = (distance / autoScrollZone).coerceIn(0f, 1f)
                                                            -(1 + speedFactor * 4)
                                                        }
                                                        currentMouseY > currentViewportHeight - autoScrollZone -> {
                                                            // Scrolling down
                                                            val distance = currentMouseY - (currentViewportHeight - autoScrollZone)
                                                            val speedFactor = (distance / autoScrollZone).coerceIn(0f, 1f)
                                                            1 + speedFactor * 4
                                                        }
                                                        else -> 0f
                                                    }
                                                    
                                                    // Start or stop auto-scroll
                                                    if (scrollSpeed != 0f && autoScrollJob == null) {
                                                        autoScrollJob = scope.launch {
                                                            while (isDragging) {
                                                                val mouseY = currentMouseY
                                                                val viewHeight = currentViewportHeight
                                                                val zone = 50.dp.toPx()
                                                                
                                                                val speed = when {
                                                                    mouseY < zone -> {
                                                                        val dist = zone - mouseY
                                                                        val factor = (dist / zone).coerceIn(0f, 1f)
                                                                        -(1 + factor * 4)
                                                                    }
                                                                    mouseY > viewHeight - zone -> {
                                                                        val dist = mouseY - (viewHeight - zone)
                                                                        val factor = (dist / zone).coerceIn(0f, 1f)
                                                                        1 + factor * 4
                                                                    }
                                                                    else -> 0f
                                                                }
                                                                
                                                                if (speed != 0f && dragStartIndex != null) {
                                                                    val targetIndex = (listState.firstVisibleItemIndex + speed.toInt())
                                                                        .coerceIn(0, displayCount - 1)
                                                                    listState.scrollToItem(targetIndex)
                                                                    
                                                                    // Update selection based on current scroll position
                                                                    val relY = mouseY + listState.firstVisibleItemScrollOffset.toFloat()
                                                                    val hovIdx = listState.firstVisibleItemIndex + (relY / itemHeight).toInt()
                                                                    val clampIdx = hovIdx.coerceIn(0, displayCount - 1)
                                                                    
                                                                    lastHoveredIndex = clampIdx
                                                                    val start = minOf(dragStartIndex!!, clampIdx)
                                                                    val end = maxOf(dragStartIndex!!, clampIdx)
                                                                    selectedIndices = (start..end).toSet()
                                                                    
                                                                    // Delay based on speed
                                                                    val delayMs = (100 / kotlin.math.abs(speed).coerceAtLeast(1f)).toLong()
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
                            cachedLogs[index]?.id ?: index 
                        },
                        contentType = { "LogEntry" }
                    ) { index ->
                        val log = cachedLogs[index]
                        if (log != null) {
                            LogItemWithSelection(
                                log = log,
                                isSelected = selectedIndices.contains(index),
                                isDragging = isDragging,
                                onSelectionStart = { isCtrlPressed ->
                                    if (isCtrlPressed) {
                                        // Toggle selection with Ctrl
                                        selectedIndices = if (selectedIndices.contains(index)) {
                                            selectedIndices - index
                                        } else {
                                            selectedIndices + index
                                        }
                                        dragStartIndex = null
                                        isDragging = false
                                    } else {
                                        // Start new selection (for dragging)
                                        selectedIndices = setOf(index)
                                        dragStartIndex = index
                                        isDragging = true
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
                            // Lightweight placeholder - no LaunchedEffect to avoid coroutine overhead
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
