import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import ui.*
import models.*

fun main() = application {
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "Logcat Viewer - Android Debug Monitor",
        state = windowState
    ) {
        MaterialTheme(
            colors = darkColors(
                primary = Color(0xFF2196F3),
                primaryVariant = Color(0xFF1976D2),
                secondary = Color(0xFF03DAC6),
                background = Color(0xFF1E1E1E),
                surface = Color(0xFF2D2D2D),
                onPrimary = Color.White,
                onSecondary = Color.Black,
                onBackground = Color(0xFFE0E0E0),
                onSurface = Color(0xFFE0E0E0)
            )
        ) {
            LogcatViewerApp()
        }
    }
}

@Composable
@Preview
fun LogcatViewerApp() {
    val viewModel = remember { LogcatViewModelNew() }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        // Toolbar
        TopAppBar(
            backgroundColor = MaterialTheme.colors.surface,
            elevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Logcat Viewer",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(Modifier.width(24.dp))
                
                // Device selector
                Box(modifier = Modifier.width(250.dp)) {
                    DeviceSelector(viewModel)
                }
                
                Spacer(Modifier.width(16.dp))
                
                // Control buttons
                IconButton(
                    onClick = { scope.launch { viewModel.startLogcat() } },
                    enabled = viewModel.state.selectedDevice.value != null && !viewModel.state.isRunning.value
                ) {
                    Icon(Icons.Default.PlayArrow, "התחל", tint = Color(0xFF4CAF50))
                }
                
                IconButton(
                    onClick = { viewModel.stopLogcat() },
                    enabled = viewModel.state.isRunning.value
                ) {
                    Text("⏹", fontSize = 20.sp, color = Color(0xFFF44336))
                }
                
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(Icons.Default.Delete, "נקה")
                }
                
                Spacer(Modifier.width(8.dp))
                
                IconButton(
                    onClick = { scope.launch { viewModel.exportLogs() } }
                ) {
                    Text("💾", fontSize = 20.sp)
                }
                
                Spacer(Modifier.weight(1f))
                
                // Show filtered count if filters are active
                val hasFilters = viewModel.filterState.hasActiveFilters()
                
                Text(
                    if (hasFilters) {
                        "רשומות: ${viewModel.state.filteredLogCount.value} מתוך ${viewModel.state.totalLogCount.value}"
                    } else {
                        "רשומות: ${viewModel.state.totalLogCount.value}"
                    },
                    style = MaterialTheme.typography.body2
                )
            }
        }
        
        // Filters
        FilterBar(viewModel)
        
        // Log display
        LogDisplay(viewModel)
        
        // Status bar
        StatusBar(viewModel)
    }
}
