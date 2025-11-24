package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import LogcatViewModelNew

@Composable
fun StatusBar(viewModel: LogcatViewModelNew) {
    // Use derivedStateOf to minimize recompositions
    val statusMessage by remember { derivedStateOf { viewModel.state.statusMessage.value } }
    val isRunning by remember { derivedStateOf { viewModel.state.isRunning.value } }
    val filteredCount by remember { derivedStateOf { viewModel.state.filteredLogCount.value } }
    val totalCount by remember { derivedStateOf { viewModel.state.totalLogCount.value } }
    
    val hasFilters by remember {
        derivedStateOf {
            viewModel.filterState.hasActiveFilters()
        }
    }
    
    val countText by remember {
        derivedStateOf {
            if (hasFilters) {
                "מציג $filteredCount מתוך $totalCount רשומות"
            } else {
                "סה\"כ $totalCount רשומות"
            }
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        color = MaterialTheme.colors.surface,
        elevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.caption
                )
                
                if (isRunning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp
                        )
                        Text("פעיל", style = MaterialTheme.typography.caption)
                    }
                }
            }
            
            Text(
                countText,
                style = MaterialTheme.typography.caption,
                color = Color.Gray
            )
        }
    }
}
