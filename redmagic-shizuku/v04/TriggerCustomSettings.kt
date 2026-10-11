package com.redtrigger.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redtrigger.TriggerActionEngine

@Composable
fun TriggerCustomSettings() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE) }
    var haptic by remember { mutableStateOf(prefs.getBoolean("haptic_enabled", true)) }
    var strength by remember { mutableFloatStateOf(prefs.getInt("haptic_strength", 130).toFloat()) }
    var duration by remember { mutableFloatStateOf(prefs.getInt("haptic_duration", 16).toFloat()) }
    var longMs by remember { mutableFloatStateOf(prefs.getInt("long_ms", 550).toFloat()) }
    var doubleMs by remember { mutableFloatStateOf(prefs.getInt("double_ms", 280).toFloat()) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Tùy chỉnh giống One Hand Operation+",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Hai nút vai: nhấn, nhấn đúp và nhấn giữ. Cài đặt riêng từng nút.",
                style = MaterialTheme.typography.bodySmall)
            listOf("left" to "NÚT VAI TRÁI", "right" to "NÚT VAI PHẢI")
                .forEach { (side, sideName) ->
                    HorizontalDivider()
                    Text(sideName, fontWeight = FontWeight.Bold)
                    listOf("tap" to "Nhấn một lần",
                        "double" to "Nhấn đúp",
                        "long" to "Nhấn giữ")
                        .forEach { (gesture, gestureName) ->
                            val preference = side + "_" + gesture
                            val fallback = if (gesture == "tap") {
                                if (side == "left") "VOL_DOWN" else "VOL_UP"
                            } else "NONE"
                            var selected by remember(preference) {
                                mutableStateOf(prefs.getString(preference, fallback) ?: fallback)
                            }
                            var expanded by remember(preference) { mutableStateOf(false) }
                            Column {
                                Text(gestureName, style = MaterialTheme.typography.labelMedium)
                                Box {
                                    OutlinedButton(onClick = { expanded = true },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text((TriggerActionEngine.actions[selected] ?: selected) + "  ▾")
                                    }
                                    DropdownMenu(expanded = expanded,
                                        onDismissRequest = { expanded = false }) {
                                        TriggerActionEngine.actions.forEach { (key, title) ->
                                            DropdownMenuItem(text = { Text(title) },
                                                onClick = {
                                                    selected = key
                                                    prefs.edit().putString(preference, key).apply()
                                                    expanded = false
                                                })
                                        }
                                    }
                                }
                            }
                        }
                }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Rung khi chạm")
                Switch(checked = haptic, onCheckedChange = {
                    haptic = it
                    prefs.edit().putBoolean("haptic_enabled", it).apply()
                })
            }
            Text("Độ rung: " + strength.toInt() + " / 255")
            Slider(value = strength, onValueChange = {
                strength = it
                prefs.edit().putInt("haptic_strength", it.toInt()).apply()
            }, valueRange = 1f..255f)
            Text("Thời gian rung: " + duration.toInt() + " ms")
            Slider(value = duration, onValueChange = {
                duration = it
                prefs.edit().putInt("haptic_duration", it.toInt()).apply()
            }, valueRange = 5f..70f)
            Text("Thời gian nhấn giữ: " + longMs.toInt() + " ms")
            Slider(value = longMs, onValueChange = {
                longMs = it
                prefs.edit().putInt("long_ms", it.toInt()).apply()
            }, valueRange = 350f..1400f)
            Text("Khoảng cách nhấn đúp: " + doubleMs.toInt() + " ms")
            Slider(value = doubleMs, onValueChange = {
                doubleMs = it
                prefs.edit().putInt("double_ms", it.toInt()).apply()
            }, valueRange = 180f..500f)
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Cấp quyền Back / Home / Đa nhiệm")
            }
            Text("Tùy chọn điều hướng và mở thông báo cần quyền Trợ năng. " +
                "Chỉnh âm lượng và nhạc không cần quyền này. " +
                "Độ rung phụ thuộc phần cứng và cài đặt hệ thống.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
