package com.alad.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alad.app.core.service.AudioDubbingForegroundService
import com.alad.app.core.service.YouTubeCompanionService
import com.alad.app.data.repository.UserPreferencesRepository
import com.alad.app.presentation.main.MainScreen
import com.alad.app.presentation.main.MainViewModel
import com.alad.app.presentation.main.MainViewModelFactory
import com.alad.app.presentation.settings.SettingsScreen
import com.alad.app.presentation.settings.SettingsViewModel
import com.alad.app.presentation.settings.SettingsViewModelFactory

class MainActivity : ComponentActivity() {

    private lateinit var mainViewModel: MainViewModel
    
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDubbingService(result.resultCode, result.data!!)
            mainViewModel.updateStatus("Connected")
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            mainViewModel.updateStatus("Disconnected")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            launchScreenCapture()
        } else {
            Toast.makeText(this, "Audio permission is required", Toast.LENGTH_SHORT).show()
            mainViewModel.updateStatus("Disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val repository = UserPreferencesRepository(applicationContext)
        YouTubeCompanionService.restoreSharedVideo(this)
        handleShareIntent(intent)
        
        setContent {
            com.alad.app.ui.theme.ALADTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("MAIN") }
                    
                    when (currentScreen) {
                        "MAIN" -> {
                            mainViewModel = viewModel(
                                factory = MainViewModelFactory(repository)
                            )
                            MainScreen(
                                viewModel = mainViewModel,
                                onNavigateToSettings = { currentScreen = "SETTINGS" },
                                onConnectClicked = { checkPermissionsAndConnect() },
                                onDisconnectClicked = { stopDubbingService() }
                            )
                        }
                        "SETTINGS" -> {
                            val settingsViewModel: SettingsViewModel = viewModel(
                                factory = SettingsViewModelFactory(repository)
                            )
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { currentScreen = "MAIN" }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type != "text/plain") return
        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Regex("""https?://(?:www\.)?(?:youtube\.com/\S+|youtu\.be/\S+)""")
            .find(sharedText)
            ?.value
            ?.trim()
            .orEmpty()

        if (url.isBlank()) {
            Toast.makeText(this, "Không tìm thấy link YouTube trong nội dung chia sẻ.", Toast.LENGTH_SHORT).show()
            return
        }

        YouTubeCompanionService.setSharedVideo(this, url)

        if (!YouTubeCompanionService.hasNotificationAccess(this)) {
            Toast.makeText(
                this,
                "Bật quyền Truy cập thông báo cho ALAD để bám Play/Pause/Seek của YouTube.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Throwable) {
            }
        } else {
            Toast.makeText(
                this,
                "YouTube Companion đã liên kết. Nhấn Start ALAD rồi quay lại YouTube.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            launchScreenCapture()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun launchScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startDubbingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = AudioDubbingForegroundService.ACTION_START
            putExtra(AudioDubbingForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioDubbingForegroundService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDubbingService() {
        val intent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = AudioDubbingForegroundService.ACTION_STOP
        }
        startService(intent) // stopService or passing ACTION_STOP is fine
        mainViewModel.updateStatus("Disconnected")
    }
}
