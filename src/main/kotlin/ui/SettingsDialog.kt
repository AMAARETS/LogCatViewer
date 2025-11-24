package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import settings.PerformanceSettings

@Composable
fun SettingsDialog(
    isOpen: Boolean,
    onClose: () -> Unit,
    settings: PerformanceSettings,
    onSettingsChange: (PerformanceSettings) -> Unit
) {
    if (isOpen) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .width(500.dp)
                    .padding(16.dp),
                backgroundColor = Color(0xFF2B2B2B),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // כותרת
                    Text(
                        text = "הגדרות ביצועים",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Divider(color = Color.Gray)
                    
                    // הגדרת כמות שורות לטעינה
                    Column {
                        Text(
                            text = "כמות שורות לטעינה בכל פעם:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        var batchSizeText by remember { mutableStateOf(settings.batchSize.toString()) }
                        
                        OutlinedTextField(
                            value = batchSizeText,
                            onValueChange = { newValue ->
                                batchSizeText = newValue
                                newValue.toIntOrNull()?.let { intValue ->
                                    if (intValue in 1000..50000) {
                                        onSettingsChange(settings.copy(batchSize = intValue))
                                    }
                                }
                            },
                            label = { Text("שורות (1,000 - 50,000)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color.White,
                                backgroundColor = Color(0xFF3C3C3C),
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFF2196F3),
                                unfocusedLabelColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Text(
                            text = "ברירת מחדל: 10,000 שורות. זה הגודל המקסימלי של כל חלון.\nאם יש יותר לוגים, יחולקו לחלונות נפרדים עם כפתורי ניווט.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    // הגדרת מהירות גלילה
                    Column {
                        Text(
                            text = "מהירות גלילה מקסימלית:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("איטי", fontSize = 12.sp, color = Color.Gray)
                            
                            Slider(
                                value = settings.scrollSpeed.toFloat(),
                                onValueChange = { newValue ->
                                    onSettingsChange(settings.copy(scrollSpeed = newValue.toInt()))
                                },
                                valueRange = 1f..10f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF2196F3),
                                    activeTrackColor = Color(0xFF2196F3),
                                    inactiveTrackColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            
                            Text("מהיר", fontSize = 12.sp, color = Color.Gray)
                        }
                        
                        Text(
                            text = "נוכחי: ${settings.scrollSpeed}/10",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    
                    // הגדרת cache
                    Column {
                        Text(
                            text = "גודל זיכרון מטמון (MB):",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        var cacheSizeText by remember { mutableStateOf(settings.cacheSize.toString()) }
                        
                        OutlinedTextField(
                            value = cacheSizeText,
                            onValueChange = { newValue ->
                                cacheSizeText = newValue
                                newValue.toIntOrNull()?.let { intValue ->
                                    if (intValue in 4..64) {
                                        onSettingsChange(settings.copy(cacheSize = intValue))
                                    }
                                }
                            },
                            label = { Text("MB (4 - 64)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color.White,
                                backgroundColor = Color(0xFF3C3C3C),
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFF2196F3),
                                unfocusedLabelColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Text(
                            text = "ברירת מחדל: 8MB. ערך גבוה יותר = פחות טעינות אבל יותר זיכרון",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    // הגדרות מתקדמות
                    Column {
                        Text(
                            text = "הגדרות מתקדמות:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = settings.enablePreloading,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enablePreloading = it))
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF2196F3),
                                    uncheckedColor = Color.Gray
                                )
                            )
                            Text(
                                text = "טעינה מוקדמת (משפר חלקות אבל צורך יותר זיכרון)",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = settings.enableSmartLoading,
                                onCheckedChange = { 
                                    onSettingsChange(settings.copy(enableSmartLoading = it))
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF2196F3),
                                    uncheckedColor = Color.Gray
                                )
                            )
                            Text(
                                text = "טעינה חכמה (מתאים את הטעינה לפי מהירות הגלילה)",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    
                    Divider(color = Color.Gray)
                    
                    // כפתורים
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = {
                                // איפוס להגדרות ברירת מחדל
                                onSettingsChange(PerformanceSettings.default())
                            }
                        ) {
                            Text("איפוס", color = Color.Gray)
                        }
                        
                        TextButton(onClick = onClose) {
                            Text("סגור", color = Color(0xFF2196F3))
                        }
                    }
                }
            }
        }
    }
}