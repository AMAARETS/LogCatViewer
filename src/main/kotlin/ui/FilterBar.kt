package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import LogLevel
import LogcatViewModel

@Composable
fun FilterBar(viewModel: LogcatViewModel) {
    // Memoize callbacks to avoid recreating them
    val onSearchChange = remember {
        { text: String ->
            viewModel.searchText.value = text
            viewModel.onFiltersChanged()
        }
    }
    
    val onTagFilterChange = remember {
        { text: String ->
            viewModel.tagFilter.value = text
            viewModel.onFiltersChanged()
        }
    }
    
    val onAutoScrollChange = remember {
        { checked: Boolean ->
            viewModel.autoScroll.value = checked
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colors.surface,
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = viewModel.searchText.value,
                    onValueChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("חיפוש בלוגים...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = MaterialTheme.colors.primary
                    )
                )
                
                // Tag filter
                OutlinedTextField(
                    value = viewModel.tagFilter.value,
                    onValueChange = onTagFilterChange,
                    modifier = Modifier.width(200.dp),
                    placeholder = { Text("סינון לפי תג") },
                    singleLine = true
                )
                
                // Auto-scroll toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Checkbox(
                        checked = viewModel.autoScroll.value,
                        onCheckedChange = onAutoScrollChange
                    )
                    Text("גלילה אוטומטית", modifier = Modifier.padding(start = 4.dp))
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Level filters
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("רמות:", modifier = Modifier.align(Alignment.CenterVertically))
                
                LogLevel.values().forEach { level ->
                    val isSelected = viewModel.selectedLevels.value.contains(level)
                    val onClick = remember(level) {
                        {
                            val current = viewModel.selectedLevels.value.toMutableSet()
                            if (current.contains(level)) {
                                current.remove(level)
                            } else {
                                current.add(level)
                            }
                            viewModel.selectedLevels.value = current
                            viewModel.onFiltersChanged()
                        }
                    }
                    
                    FilterChip(
                        selected = isSelected,
                        onClick = onClick,
                        label = { Text(level.displayName) },
                        colors = level.color
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    colors: Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.copy(alpha = 0.3f) else MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, if (selected) colors else Color.Gray),
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            label()
        }
    }
}
