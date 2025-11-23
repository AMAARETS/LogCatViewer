package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import LogEntry

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LogItemWithSelection(
    log: LogEntry,
    isSelected: Boolean,
    isDragging: Boolean,
    onSelectionStart: (isCtrlPressed: Boolean) -> Unit,
    onHover: (isHovering: Boolean) -> Unit,
    onDragEnd: () -> Unit,
    onContextMenu: (DpOffset) -> Unit
) {
    // Memoize colors and modifiers to avoid recreating them
    val backgroundColor = remember { Color(0xFF252525) }
    val selectedColor = remember { Color(0xFF1E3A5F) }
    val hoverColor = remember { Color(0xFF2D2D2D) }
    val messageColor = remember { Color(0xFFE0E0E0) }
    val tagColor = remember { Color(0xFF64B5F6) }
    val metaColor = remember { Color(0xFF888888) }
    val shape = remember { RoundedCornerShape(2.dp) }
    
    val pidTidText = remember(log.pid, log.tid) { "${log.pid}/${log.tid}" }
    val fullLogText = remember(log) {
        "${log.timestamp} ${log.pid}/${log.tid} ${log.level.displayName}/${log.tag}: ${log.message}"
    }
    
    var isHovered by remember { mutableStateOf(false) }
    
    val rowContent = @Composable {
        LogItemRow(
            log = log,
            isSelected = isSelected,
            isHovered = isHovered,
            isDragging = isDragging,
            backgroundColor = backgroundColor,
            selectedColor = selectedColor,
            hoverColor = hoverColor,
            shape = shape,
            metaColor = metaColor,
            tagColor = tagColor,
            messageColor = messageColor,
            pidTidText = pidTidText,
            fullLogText = fullLogText,
            onHoverChange = { hovering ->
                isHovered = hovering
                onHover(hovering)
            },
            onSelectionStart = onSelectionStart,
            onDragEnd = onDragEnd,
            onContextMenu = onContextMenu
        )
    }
    
    // Only wrap non-selected items with ContextMenuArea
    if (!isSelected) {
        ContextMenuArea(
            items = {
                listOf(
                    ContextMenuItem("העתק הודעה") {
                        copyToClipboard(log.message)
                    },
                    ContextMenuItem("העתק שורה מלאה") {
                        copyToClipboard(fullLogText)
                    }
                )
            }
        ) {
            rowContent()
        }
    } else {
        rowContent()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun LogItem(log: LogEntry) {
    // Memoize colors and modifiers to avoid recreating them
    val backgroundColor = remember { Color(0xFF252525) }
    val hoverColor = remember { Color(0xFF2D2D2D) }
    val messageColor = remember { Color(0xFFE0E0E0) }
    val tagColor = remember { Color(0xFF64B5F6) }
    val metaColor = remember { Color(0xFF888888) }
    val shape = remember { RoundedCornerShape(2.dp) }
    
    val pidTidText = remember(log.pid, log.tid) { "${log.pid}/${log.tid}" }
    val fullLogText = remember(log) {
        "${log.timestamp} ${log.pid}/${log.tid} ${log.level.displayName}/${log.tag}: ${log.message}"
    }
    
    var isHovered by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isHovered) hoverColor else backgroundColor, shape)
                .pointerInput(log.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter -> isHovered = true
                                PointerEventType.Exit -> isHovered = false
                                PointerEventType.Press -> {
                                    if (event.button == PointerButton.Secondary) {
                                        val position = event.changes.first().position
                                        contextMenuOffset = DpOffset(
                                            (position.x / density).dp,
                                            (position.y / density).dp
                                        )
                                        showContextMenu = true
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LogItemContent(log, metaColor, tagColor, messageColor, pidTidText)
        }
        
        // Context menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = contextMenuOffset
        ) {
            DropdownMenuItem(
                onClick = {
                    copyToClipboard(log.message)
                    showContextMenu = false
                }
            ) {
                Text("העתק הודעה")
            }
            
            DropdownMenuItem(
                onClick = {
                    copyToClipboard(fullLogText)
                    showContextMenu = false
                }
            ) {
                Text("העתק שורה מלאה")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun LogItemRow(
    log: LogEntry,
    isSelected: Boolean,
    isHovered: Boolean,
    isDragging: Boolean,
    backgroundColor: Color,
    selectedColor: Color,
    hoverColor: Color,
    shape: RoundedCornerShape,
    metaColor: Color,
    tagColor: Color,
    messageColor: Color,
    pidTidText: String,
    fullLogText: String,
    onHoverChange: (Boolean) -> Unit,
    onSelectionStart: (isCtrlPressed: Boolean) -> Unit,
    onDragEnd: () -> Unit,
    onContextMenu: (DpOffset) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        isSelected -> selectedColor
                        isHovered -> hoverColor
                        else -> backgroundColor
                    },
                    shape
                )
                // Combined pointer input for all interactions
                .pointerInput(log.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter -> {
                                    onHoverChange(true)
                                }
                                PointerEventType.Exit -> {
                                    onHoverChange(false)
                                }
                                PointerEventType.Press -> {
                                    if (event.button == PointerButton.Primary) {
                                        val isCtrlPressed = event.keyboardModifiers.isCtrlPressed
                                        onSelectionStart(isCtrlPressed)
                                    } else if (event.button == PointerButton.Secondary && isSelected) {
                                        // Right click on selected item - trigger callback
                                        onContextMenu(DpOffset.Zero)
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (event.button == PointerButton.Primary) {
                                        onDragEnd()
                                    }
                                }
                                PointerEventType.Move -> {
                                    // Continuously notify hover during drag
                                    if (event.buttons.isPrimaryPressed && isDragging) {
                                        onHoverChange(true)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LogItemContent(log, metaColor, tagColor, messageColor, pidTidText)
        }
    }
}

@Composable
private fun RowScope.LogItemContent(
    log: LogEntry,
    metaColor: Color,
    tagColor: Color,
    messageColor: Color,
    pidTidText: String
) {
    // LTR layout: Timestamp -> PID/TID -> Level -> Tag -> Message
    
    // Timestamp (leftmost)
    Text(
        text = log.timestamp,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = metaColor,
        modifier = Modifier.width(140.dp)
    )
    
    // PID/TID
    Text(
        text = pidTidText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = metaColor,
        modifier = Modifier.width(80.dp)
    )
    
    // Level
    Text(
        text = log.level.displayName,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = log.level.color,
        modifier = Modifier.width(20.dp)
    )
    
    // Tag
    Text(
        text = log.tag,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        color = tagColor,
        modifier = Modifier.width(180.dp),
        maxLines = 1
    )
    
    // Message (rightmost, takes remaining space)
    Text(
        text = log.message,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = messageColor,
        modifier = Modifier.weight(1f),
        maxLines = 1
    )
}
