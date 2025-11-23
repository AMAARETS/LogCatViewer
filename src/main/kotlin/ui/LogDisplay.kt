package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import LogEntry
import LogcatViewModel

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
    
    // Cache with larger window for smoother scrolling
    val windowSize = 1000
    var cachedLogs by remember { mutableStateOf<Map<Int, LogEntry>>(emptyMap()) }
    var cachedRange by remember { mutableStateOf(0..0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingJob by remember { mutableStateOf<Job?>(null) }
    
    // Load logs with debouncing and smart caching
    fun loadLogsForRange(centerIndex: Int, force: Boolean = false) {
        // Cancel previous loading job
        loadingJob?.cancel()
        
        loadingJob = scope.launch {
            // Debounce - wait a bit before loading
            if (!force) {
                delay(50)
            }
            
            if (isLoading && !force) return@launch
            
            isLoading = true
            try {
                val currentCount = displayCount
                if (currentCount == 0) {
                    cachedLogs = emptyMap()
                    cachedRange = 0..0
                    return@launch
                }
                
                // Calculate window around current position
                val startIndex = maxOf(0, centerIndex - windowSize / 2)
                val endIndex = minOf(currentCount - 1, centerIndex + windowSize / 2)
                val newRange = startIndex..endIndex
                
                // Only reload if we're outside the cached range
                val needsReload = force || centerIndex !in cachedRange || 
                    startIndex < cachedRange.first - 200 || 
                    endIndex > cachedRange.last + 200
                
                if (needsReload) {
                    val logs = viewModel.getLogsPage(startIndex, endIndex - startIndex + 1)
                    
                    // Build new cache efficiently
                    val newCache = HashMap<Int, LogEntry>(logs.size)
                    logs.forEachIndexed { idx, log ->
                        newCache[startIndex + idx] = log
                    }
                    
                    cachedLogs = newCache
                    cachedRange = newRange
                }
            } finally {
                isLoading = false
            }
        }
    }
    
    // Monitor scroll position with debouncing
    LaunchedEffect(listState.firstVisibleItemIndex) {
        delay(100)
        if (!listState.isScrollInProgress) {
            loadLogsForRange(listState.firstVisibleItemIndex + 25)
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
                            key(log.id) {
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
                                    onHover = { isHovering ->
                                        if (isHovering) {
                                            lastHoveredIndex = index
                                            if (isDragging && dragStartIndex != null) {
                                                // Update selection range during drag
                                                val start = minOf(dragStartIndex!!, index)
                                                val end = maxOf(dragStartIndex!!, index)
                                                selectedIndices = (start..end).toSet()
                                            }
                                        }
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
                            }
                        } else {
                            // Placeholder without triggering loads
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
