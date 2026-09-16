package com.vinh.livedubvi

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, LiveDubService::class.java).apply {
                action = LiveDubService.ACTION_START
                putExtra(LiveDubService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(LiveDubService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, service)
            setStatus("Đang khởi động mô hình nhận dạng…")
        } else {
            setStatus("Chưa cấp quyền bắt âm thanh nội bộ.")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioOk) requestProjection() else setStatus("Cần quyền Âm thanh để Android cho phép bắt playback nội bộ.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handler.post(statusTicker)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            status.text = LiveDubService.lastStatus
            startButton.isEnabled = !LiveDubService.running
            handler.postDelayed(this, 700)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(32))
            setBackgroundColor(Color.rgb(247, 242, 250))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "LiveDub Việt"
            textSize = 30f
            setTextColor(Color.rgb(31, 31, 31))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Lồng tiếng Việt trực tiếp từ X, YouTube và video đang phát trên máy"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(22))
        })

        startButton = Button(this).apply {
            text = "BẬT LỒNG TIẾNG"
            textSize = 17f
            minHeight = dp(58)
            setOnClickListener { startLiveDub() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(62)))

        root.addView(Button(this).apply {
            text = "DỪNG"
            textSize = 16f
            setOnClickListener {
                startService(Intent(this@MainActivity, LiveDubService::class.java).apply {
                    action = LiveDubService.ACTION_STOP
                })
            }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })

        status = TextView(this).apply {
            text = LiveDubService.lastStatus
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        root.addView(info("• Nguồn: âm thanh nội bộ Android\n• Nhận dạng: Whisper tiny.en chạy ngay trên Z Fold 6\n• Dịch: English → Tiếng Việt bằng ML Kit\n• Đọc: TTS tiếng Việt qua loa\n• Live Sync: luôn giữ đoạn mới nhất, bỏ đoạn cũ nếu xử lý không kịp"))
        root.addView(info("Lần đầu dùng, ML Kit sẽ tải mô hình dịch Anh–Việt. APK đã kèm mô hình Whisper nên phần nhận dạng không cần API key."))
        root.addView(info("Android vẫn yêu cầu quyền Microphone để API AudioPlaybackCapture hoạt động. Ứng dụng này không tạo AudioRecord từ nguồn MIC; nó chỉ tạo AudioRecord từ MediaProjection playback capture."))
        root.addView(info("Một số ứng dụng/video có thể chủ động cấm playback capture. Khi đó Android trả về im lặng và app không thể vượt chặn bằng API chuẩn."))

        setContentView(scroll)
    }

    private fun info(textValue: String): View = TextView(this).apply {
        text = textValue
        textSize = 14.5f
        setTextColor(Color.rgb(65, 65, 65))
        setPadding(dp(4), dp(18), dp(4), 0)
    }

    private fun startLiveDub() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray()) else requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun setStatus(s: String) {
        LiveDubService.lastStatus = s
        status.text = s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
