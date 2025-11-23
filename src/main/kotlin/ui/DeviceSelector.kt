package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import LogcatViewModel

@Composable
fun DeviceSelector(viewModel: LogcatViewModel) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        OutlinedButton(
            onClick = { 
                viewModel.refreshDevices()
                expanded = true 
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    viewModel.selectedDevice.value?.let { 
                        "${it.model} (${it.serial})" 
                    } ?: "בחר מכשיר",
                    maxLines = 1
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (viewModel.devices.isEmpty()) {
                DropdownMenuItem(onClick = {}) {
                    Text("אין מכשירים מחוברים")
                }
            } else {
                viewModel.devices.forEach { deviceInfo ->
                    DropdownMenuItem(
                        onClick = {
                            viewModel.selectedDevice.value = deviceInfo
                            expanded = false
                        }
                    ) {
                        Column {
                            Text(deviceInfo.model, fontWeight = FontWeight.Bold)
                            Text(
                                deviceInfo.serial,
                                style = MaterialTheme.typography.caption,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
