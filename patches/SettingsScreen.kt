package com.alad.app.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alad.app.R
import com.alad.app.core.audio.TtsCatalog
import com.alad.app.ui.theme.AmbientBackground
import com.alad.app.ui.theme.GlassCard
import com.alad.app.ui.theme.GlassIconButton
import com.alad.app.ui.theme.NeonBlue
import com.alad.app.ui.theme.NeonCyan
import com.alad.app.ui.theme.NeonPurple
import com.alad.app.ui.theme.NeonViolet
import com.alad.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class Choice(val value: String, val label: String)

private val dubbingModes = listOf(
    Choice("auto_duck", "Auto Ducking"),
    Choice("voice_over", "Voice-over"),
    Choice("parallel", "Song song"),
    Choice("full_dub", "Lồng hoàn toàn")
)

private val voiceSources = listOf(
    Choice("gemini", "Gemini Voice"),
    Choice("device_tts", "TTS thiết bị")
)

private val geminiSyncModes = listOf(
    Choice("continuous", "Continuous · dịch liên tục, khuyên dùng"),
    Choice("stable", "Stable · mượt, ít can thiệp"),
    Choice("ultra_fast", "Ultra Fast · phản hồi sớm nhất"),
    Choice("hybrid_fast", "Hybrid Fast · ưu tiên độ trễ thấp"),
    Choice("balanced", "Balanced · nhanh + giữ chất bản cũ")
)

private val geminiInterruptionModes = listOf(
    Choice("no_interruption", "Không ngắt câu dịch · khuyên dùng"),
    Choice("interrupt", "Cho phép nguồn mới ngắt câu dịch")
)

private val voices = listOf(
    Choice("Kore", "Kore · chắc, rõ"),
    Choice("Puck", "Puck · trẻ, sinh động"),
    Choice("Aoede", "Aoede · nhẹ, thoáng"),
    Choice("Charon", "Charon · thuyết minh"),
    Choice("Leda", "Leda · trẻ trung"),
    Choice("Gacrux", "Gacrux · trưởng thành"),
    Choice("Achernar", "Achernar · mềm"),
    Choice("Sulafat", "Sulafat · ấm")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val apiKey by viewModel.apiKey.collectAsState()
    val aiVolume by viewModel.volumeRatio.collectAsState()
    val dubMode by viewModel.dubMode.collectAsState()
    val voiceName by viewModel.voiceName.collectAsState()
    val voiceSource by viewModel.voiceSource.collectAsState()
    val ttsEnginePackage by viewModel.ttsEnginePackage.collectAsState()
    val ttsVoiceName by viewModel.ttsVoiceName.collectAsState()
    val ttsRate by viewModel.ttsRate.collectAsState()
    val manualSyncMs by viewModel.manualSyncMs.collectAsState()
    val autoSync by viewModel.autoSync.collectAsState()
    val catchUp by viewModel.catchUp.collectAsState()
    val lowLatency by viewModel.lowLatency.collectAsState()
    val maxCatchUpSpeed by viewModel.maxCatchUpSpeed.collectAsState()
    val geminiSyncMode by viewModel.geminiSyncMode.collectAsState()
    val geminiMicroCatchUp by viewModel.geminiMicroCatchUp.collectAsState()
    val geminiTailFinish by viewModel.geminiTailFinish.collectAsState()
    val geminiInterruptionMode by viewModel.geminiInterruptionMode.collectAsState()
    val geminiAdaptiveVad by viewModel.geminiAdaptiveVad.collectAsState()

    var ttsEngines by remember { mutableStateOf<List<TtsCatalog.EngineItem>>(emptyList()) }
    var ttsVoices by remember { mutableStateOf<List<TtsCatalog.VoiceItem>>(emptyList()) }
    var loadingVoices by remember { mutableStateOf(false) }

    LaunchedEffect(voiceSource) {
        if (voiceSource == "device_tts") {
            TtsCatalog.loadEngines(context) { items ->
                ttsEngines = items
                if (ttsEnginePackage.isBlank() && items.isNotEmpty()) {
                    viewModel.updateTtsEnginePackage(items.first().packageName)
                }
            }
        }
    }

    LaunchedEffect(voiceSource, ttsEnginePackage) {
        if (voiceSource == "device_tts" && ttsEnginePackage.isNotBlank()) {
            loadingVoices = true
            TtsCatalog.loadVoices(
                context = context,
                enginePackage = ttsEnginePackage,
                preferredLanguageTag = "vi-VN"
            ) { items ->
                ttsVoices = items
                loadingVoices = false
                if (items.isNotEmpty() && items.none { it.name == ttsVoiceName }) {
                    viewModel.updateTtsVoiceName(items.first().name)
                }
            }
        } else {
            ttsVoices = emptyList()
            loadingVoices = false
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var apiKeyVisible by remember { mutableStateOf(false) }

    AmbientBackground {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.gemini_api_key),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text("Google AI Studio Live API", color = TextSecondary, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = viewModel::updateApiKey,
                            placeholder = { Text("AIzaSy...", color = TextSecondary.copy(alpha = 0.6f)) },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x25FFFFFF),
                                unfocusedContainerColor = Color(0x12FFFFFF),
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = if (apiKeyVisible) NeonCyan else TextSecondary
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(NeonCyan.copy(alpha = 0.10f))
                                .clickable { uriHandler.openUri("https://aistudio.google.com/app/apikey") }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Lấy API key", color = NeonCyan, fontWeight = FontWeight.Bold)
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                SettingsGlassSection("Lồng tiếng") {
                    SettingLabel("Nguồn giọng")
                    ChoiceDropdown(
                        selectedValue = voiceSource,
                        choices = voiceSources,
                        onSelected = viewModel::updateVoiceSource
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    SettingLabel("Kiểu lồng tiếng")
                    ChoiceDropdown(
                        selectedValue = dubMode,
                        choices = dubbingModes,
                        onSelected = viewModel::updateDubMode
                    )

                    if (voiceSource == "gemini") {
                        Spacer(modifier = Modifier.height(14.dp))
                        SettingLabel("Giọng Gemini")
                        ChoiceDropdown(
                            selectedValue = voiceName,
                            choices = voices,
                            onSelected = viewModel::updateVoiceName
                        )
                        Text(
                            "Gemini tự tạo audio dịch. Đổi giọng cần Start lại phiên.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.height(14.dp))
                        SettingLabel("TTS Engine trên thiết bị")
                        if (ttsEngines.isEmpty()) {
                            Text(
                                "Đang tìm TTS engine… Nếu danh sách vẫn trống, máy chưa có engine TTS khả dụng.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        } else {
                            ChoiceDropdown(
                                selectedValue = ttsEnginePackage,
                                choices = ttsEngines.map { Choice(it.packageName, "${it.label} · ${it.packageName}") },
                                onSelected = viewModel::updateTtsEnginePackage
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        SettingLabel("Voice của TTS Engine")
                        when {
                            loadingVoices -> Text("Đang đọc danh sách voice…", color = TextSecondary, fontSize = 12.sp)
                            ttsVoices.isEmpty() -> Text(
                                "Engine này chưa trả về danh sách voice. ALAD sẽ dùng giọng mặc định của engine.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                            else -> ChoiceDropdown(
                                selectedValue = ttsVoiceName,
                                choices = ttsVoices.map { Choice(it.name, it.label) },
                                onSelected = viewModel::updateTtsVoiceName
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        SettingLabel("Tốc độ TTS ${String.format("%.2f", ttsRate)}×")
                        Slider(
                            value = ttsRate,
                            onValueChange = viewModel::updateTtsRate,
                            valueRange = 0.70f..1.50f,
                            steps = 15
                        )
                        Text(
                            "Voice tiếng Việt được ưu tiên lên đầu. offline = chạy trong máy; online = engine có thể cần mạng.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    SettingLabel("Âm lượng tiếng lồng ${(aiVolume * 100).roundToInt()}%")
                    Slider(
                        value = aiVolume,
                        onValueChange = viewModel::updateVolumeRatio,
                        valueRange = 0.20f..1.0f
                    )
                }

                SettingsGlassSection("Đồng bộ tiếng - hình") {
                    SettingLabel("Bù trễ thủ công ${if (manualSyncMs >= 0) "+" else ""}${manualSyncMs} ms")
                    Slider(
                        value = manualSyncMs.toFloat().coerceIn(
                            if (voiceSource == "gemini") -1200f else -2000f,
                            if (voiceSource == "gemini") 2500f else 5000f
                        ),
                        onValueChange = { viewModel.updateManualSyncMs((it / 100f).roundToInt() * 100) },
                        valueRange = if (voiceSource == "gemini") -1200f..2500f else -2000f..5000f,
                        steps = if (voiceSource == "gemini") 36 else 69
                    )
                    Text(
                        if (voiceSource == "gemini") {
                            "Số dương làm tiếng lồng trễ thêm; số âm tạo áp lực bắt kịp nhẹ, không xóa câu."
                        } else {
                            "Số dương làm tiếng lồng trễ thêm. Catch-up ưu tiên giữ lời đọc gần video."
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    if (voiceSource == "gemini") {
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingLabel("Chế độ đồng bộ Gemini")
                        ChoiceDropdown(
                            selectedValue = geminiSyncMode,
                            choices = geminiSyncModes,
                            onSelected = viewModel::updateGeminiSyncMode
                        )
                        Text(
                            when (geminiSyncMode) {
                                "continuous" -> "Continuous: PCM 30ms liên tục, không chốt từng câu bằng audioStreamEnd; chỉ flush khi nguồn thật sự pause/hết."
                                "stable" -> "Stable: server VAD ~800ms, buffer ~110ms, không Hybrid end-turn, không tăng tốc."
                                "ultra_fast" -> "Ultra Fast: client chốt lượt khoảng 320ms im lặng, buffer ~25ms. Nhanh nhất nhưng câu có thể bị chia ngắn hơn."
                                "hybrid_fast" -> "Hybrid Fast: chốt câu khoảng 500ms im lặng, buffer ~45ms, không tăng tốc."
                                else -> "Balanced: Hybrid Fast + micro catch-up nhẹ 1.03–1.08×, không drop nội dung."
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        SettingLabel("Xử lý gián đoạn khi nguồn nói tiếp")
                        ChoiceDropdown(
                            selectedValue = geminiInterruptionMode,
                            choices = geminiInterruptionModes,
                            onSelected = viewModel::updateGeminiInterruptionMode
                        )
                        Text(
                            if (geminiInterruptionMode == "no_interruption") {
                                "Giữ nguyên câu dịch đang phát dù nguồn đã nói câu kế tiếp; giảm hụt nội dung."
                            } else {
                                "Ưu tiên bám live: câu dịch hiện tại có thể bị cắt khi phát hiện lời nói mới."
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )

                        if (geminiSyncMode != "stable") {
                            ToggleRow(
                                title = "Adaptive VAD",
                                subtitle = "Tự học nhịp nghỉ ngắn của người nói để chốt câu sớm khi nói nhanh và chờ lâu hơn khi nói chậm.",
                                checked = geminiAdaptiveVad,
                                onCheckedChange = viewModel::updateGeminiAdaptiveVad
                            )
                        }

                        if (geminiSyncMode == "balanced") {
                            ToggleRow(
                                title = "Micro catch-up",
                                subtitle = "Khi queue tích trễ, chỉ tăng rất nhẹ 1.03–1.08×; không bỏ câu.",
                                checked = geminiMicroCatchUp,
                                onCheckedChange = viewModel::updateGeminiMicroCatchUp
                            )
                        }

                        ToggleRow(
                            title = "Tail Finish",
                            subtitle = "Khi video dừng/hết, cho tiếng dịch cuối chạy nốt thay vì cắt ngay.",
                            checked = geminiTailFinish,
                            onCheckedChange = viewModel::updateGeminiTailFinish
                        )
                    } else {
                        ToggleRow(
                            title = "Auto Sync",
                            subtitle = "Tự điều chỉnh khi hàng đợi audio/TTS bắt đầu tích trễ.",
                            checked = autoSync,
                            onCheckedChange = viewModel::updateAutoSync
                        )
                        ToggleRow(
                            title = "Catch-up",
                            subtitle = "Tăng tốc TTS khi bị tụt xa video.",
                            checked = catchUp,
                            onCheckedChange = viewModel::updateCatchUp
                        )
                        ToggleRow(
                            title = "Low Latency",
                            subtitle = "Giữ buffer ngắn, ưu tiên khớp hình hơn độ mượt.",
                            checked = lowLatency,
                            onCheckedChange = viewModel::updateLowLatency
                        )

                        if (catchUp) {
                            Spacer(modifier = Modifier.height(6.dp))
                            SettingLabel("Tốc độ catch-up tối đa ${String.format("%.2f", maxCatchUpSpeed)}×")
                            Slider(
                                value = maxCatchUpSpeed,
                                onValueChange = viewModel::updateMaxCatchUpSpeed,
                                valueRange = 1.05f..1.25f,
                                steps = 3
                            )
                        }
                    }
                }

                Text(
                    text = "Sau khi đổi Engine/Voice TTS, bấm Lưu rồi Dừng/Start lồng tiếng để áp dụng.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.horizontalGradient(listOf(NeonCyan, NeonBlue, NeonViolet)))
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                            RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            viewModel.saveSettings()
                            scope.launch {
                                snackbarHostState.showSnackbar("Đã lưu TTS engine/voice. Start lại lồng tiếng để áp dụng.")
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Lưu cài đặt", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsGlassSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        borderColor = NeonPurple,
        borderAlpha = 0.22f
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(modifier = Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text = text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun ChoiceDropdown(
    selectedValue: String,
    choices: List<Choice>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.value == selectedValue } ?: choices.firstOrNull()

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (choices.isNotEmpty()) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected?.label ?: "Không có lựa chọn", modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(8.dp))
                Text("▾", color = NeonCyan)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onSelected(choice.value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
