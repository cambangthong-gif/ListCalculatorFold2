package com.alad.app.core.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alad.app.ui.theme.ALADTheme
import com.alad.app.ui.theme.DeepSpace
import com.alad.app.ui.theme.GlassSurfaceDark
import com.alad.app.ui.theme.NeonBlue
import com.alad.app.ui.theme.NeonCoral
import com.alad.app.ui.theme.NeonCyan
import com.alad.app.ui.theme.NeonRose

class OverlayWidgetService : LifecycleService() {

    companion object {
        val isWidgetActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private var params: WindowManager.LayoutParams? = null

    private val savedStateRegistryOwner by lazy { ServiceSavedStateRegistryOwner(this) }
    private val viewModelStoreOwner by lazy { ServiceViewModelStoreOwner() }

    override fun onCreate() {
        super.onCreate()
        isWidgetActive.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ALADTheme {
                    OverlayContent(
                        onDrag = { dx, dy ->
                            params?.x = (params?.x ?: 0) + dx.toInt()
                            params?.y = (params?.y ?: 0) + dy.toInt()
                            windowManager.updateViewLayout(composeView, params)
                        },
                        onClose = { stopSelf() },
                        onToggle = { isCurrentlyRunning ->
                            if (isCurrentlyRunning) {
                                sendDubbingAction(AudioDubbingForegroundService.ACTION_STOP)
                            } else {
                                val intent = Intent(
                                    this@OverlayWidgetService,
                                    com.alad.app.TransparentCaptureActivity::class.java
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            }
                        },
                        onSyncMinus = {
                            sendDubbingAction(AudioDubbingForegroundService.ACTION_SYNC_MINUS)
                        },
                        onSyncAuto = {
                            sendDubbingAction(AudioDubbingForegroundService.ACTION_SYNC_AUTO)
                        },
                        onSyncPlus = {
                            sendDubbingAction(AudioDubbingForegroundService.ACTION_SYNC_PLUS)
                        }
                    )
                }
            }
        }

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)

        windowManager.addView(composeView, params)
    }

    private fun sendDubbingAction(actionName: String) {
        val intent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = actionName
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isWidgetActive.value = false
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }
}

@Composable
fun OverlayContent(
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSyncMinus: () -> Unit,
    onSyncAuto: () -> Unit,
    onSyncPlus: () -> Unit
) {
    val isRunning by AudioDubbingForegroundService.isRunning.collectAsState()
    val syncOffset by AudioDubbingForegroundService.syncOffsetMs.collectAsState()
    val autoSync by AudioDubbingForegroundService.autoSyncActive.collectAsState()
    val queueLatency by AudioDubbingForegroundService.queueLatencyMs.collectAsState()
    val smartPosition by AudioDubbingForegroundService.smartSyncPositionMs.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "widget_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepSpace.copy(alpha = 0.88f),
                        GlassSurfaceDark.copy(alpha = 0.94f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        onToggle(isRunning)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(50.dp)
        ) {
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .size((46 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(NeonCoral.copy(alpha = 0.35f))
                )
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                    onToggle(isRunning)
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            if (isRunning) listOf(NeonRose, NeonCoral)
                            else listOf(NeonCyan, NeonBlue)
                        )
                    )
            ) {
                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color.White, shape = RoundedCornerShape(3.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (isRunning) {
            Spacer(modifier = Modifier.width(5.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncMiniButton("−", onSyncMinus)

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (autoSync) NeonCyan.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .clickable { onSyncAuto() }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val smartLabel = if (smartPosition >= 0L) {
                        val totalSeconds = smartPosition / 1000L
                        "SMART " + (totalSeconds / 60L) + ":" +
                            "%02d".format(totalSeconds % 60L)
                    } else {
                        "LIVE"
                    }
                    Text(
                        text = "SYNC ${if (syncOffset >= 0) "+" else ""}${syncOffset}ms\nAUTO · q${queueLatency}ms · " + smartLabel,
                        color = if (autoSync) NeonCyan else Color.White,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                SyncMiniButton("+", onSyncPlus)
            }
        }

        Spacer(modifier = Modifier.width(5.dp))

        IconButton(
            onClick = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
                onClose()
            },
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Widget",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SyncMiniButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
