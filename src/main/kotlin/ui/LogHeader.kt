package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import LogcatViewModelNew
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

@Composable
fun LogHeader(viewModel: LogcatViewModelNew) {
    val headerColor = remember { Color(0xFF424242) }
    val textColor = remember { Color(0xFFE0E0E0) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerColor)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Timestamp header
        Text(
            text = "זמן",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(140.dp)
        )
        
        // PID/TID header
        Text(
            text = "PID/TID",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(80.dp)
        )
        
        // Level header
        Text(
            text = "רמה",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(20.dp)
        )
        
        // Tag header
        Text(
            text = "תג",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.width(150.dp)
        )
        
        // Package header with filter button
        PackageHeaderWithFilter(
            viewModel = viewModel,
            textColor = textColor,
            modifier = Modifier.width(200.dp)
        )
        
        // Message header
        Text(
            text = "הודעה",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PackageHeaderWithFilter(
    viewModel: LogcatViewModelNew,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    var showFilterMenu by remember { mutableStateOf(false) }
    var availablePackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val selectedPackages by viewModel.filterState.selectedPackages
    val focusRequester = remember { FocusRequester() }
    
    // Optimized filtering with memoization
    val filteredPackages = remember(availablePackages, searchText) {
        if (searchText.isEmpty()) {
            availablePackages
        } else {
            val searchLower = searchText.lowercase()
            availablePackages.filter { 
                it.lowercase().contains(searchLower)
            }
        }
    }
    
    // Load available packages when menu is opened with error handling
    LaunchedEffect(showFilterMenu) {
        if (showFilterMenu) {
            isLoading = true
            loadError = null
            try {
                availablePackages = viewModel.getUniquePackageNames()
            } catch (e: Exception) {
                loadError = "שגיאה בטעינת רשימת החבילות: ${e.message}"
                availablePackages = emptyList()
            } finally {
                isLoading = false
            }
        }
    }
    
    // Focus on search field when menu opens - with delay to avoid composition issues
    LaunchedEffect(showFilterMenu) {
        if (showFilterMenu) {
            delay(100) // Small delay to ensure UI is ready
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore focus errors - not critical
            }
        }
    }
    
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (selectedPackages.isNotEmpty()) {
                    "שם חבילה (${selectedPackages.size})"
                } else {
                    "שם חבילה"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (selectedPackages.isNotEmpty()) Color(0xFF2196F3) else textColor,
                modifier = Modifier.weight(1f)
            )
            
            // Filter button
            IconButton(
                onClick = { showFilterMenu = true },
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "סנן חבילות",
                    tint = if (selectedPackages.isNotEmpty()) Color(0xFF2196F3) else textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        // Package filter dropdown
        DropdownMenu(
            expanded = showFilterMenu,
            onDismissRequest = { 
                showFilterMenu = false
                searchText = ""
            },
            modifier = Modifier
                .width(280.dp)
                .heightIn(max = 350.dp)
        ) {
            // Search field - smaller with ESC key handling
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text("חפש חבילה", fontSize = 10.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.key == Key.Escape) {
                            showFilterMenu = false
                            searchText = ""
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
            )
            
            // Header with select all/none options - smaller
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        viewModel.filterState.setSelectedPackages(filteredPackages.toSet())
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("בחר הכל", fontSize = 10.sp)
                }
                
                TextButton(
                    onClick = {
                        viewModel.filterState.setSelectedPackages(emptySet())
                        showFilterMenu = false
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("בטל הכל", fontSize = 10.sp)
                }
            }
            
            Divider()
            
            // Package list with loading state and error handling
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            ) {
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("טוען חבילות...", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                    
                    loadError != null -> {
                        Text(
                            text = loadError!!,
                            fontSize = 10.sp,
                            color = Color.Red,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            filteredPackages.forEach { packageName ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Checkbox(
                                        checked = selectedPackages.contains(packageName),
                                        onCheckedChange = { 
                                            viewModel.filterState.togglePackage(packageName)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    
                                    Text(
                                        text = packageName,
                                        fontSize = 10.sp,
                                        fontFamily = if (packageName == "ללא") FontFamily.Default else FontFamily.Monospace,
                                        color = if (packageName == "ללא") Color(0xFFFF9800) else LocalContentColor.current,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                            
                            if (filteredPackages.isEmpty() && availablePackages.isNotEmpty()) {
                                Text(
                                    text = "לא נמצאו חבילות התואמות לחיפוש",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(12.dp)
                                )
                            } else if (availablePackages.isEmpty()) {
                                Text(
                                    text = "אין חבילות זמינות",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}