package com.vinh.livedubvi

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.abs

class LiveDubService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.vinh.livedubvi.START"
        const val ACTION_STOP = "com.vinh.livedubvi.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val CHANNEL_ID = "livedub_capture"
        const val NOTIFICATION_ID = 7186

        @Volatile var lastStatus: String = "Sẵn sàng."
        @Volatile var running: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var whisperModel: WhisperModel? = null
    private var translator: Translator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val latestChunk = AtomicReference<File?>(null)
    private val pendingSpeech = AtomicReference<String?>(null)
    private var lastOriginal = ""
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_START -> {
                if (running) return START_STICKY
                startForegroundNow()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    lastStatus = "Không nhận được quyền MediaProjection."
                    stopEverything()
                } else {
                    running = true
                    scope.launch { startPipeline(resultCode, resultData) }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNow() {
        val stopIntent = Intent(this, LiveDubService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LiveDub Việt")
            .setContentText("Đang chuẩn bị lồng tiếng từ âm thanh nội bộ")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startPipeline(resultCode: Int, resultData: Intent) {
        try {
            lastStatus = "Đang nạp Whisper tiny.en…"
            whisperModel = Whisper.loadModelFromAsset(this, "models/ggml-tiny.en.bin")
            lastStatus = "Đang chuẩn bị mô hình dịch Anh → Việt…"
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                    .build()
            )
            awaitTranslatorModel(translator!!)

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    lastStatus = "Android đã dừng phiên bắt âm thanh."
                    stopEverything()
                }
            }, null)

            startAudioCapture()
            scope.launch { transcriptionWorker() }
            lastStatus = "Đang nghe âm thanh nội bộ • EN → VI • Live Sync"
        } catch (t: Throwable) {
            lastStatus = "Lỗi khởi động: ${t.message ?: t.javaClass.simpleName}"
            stopEverything()
        }
    }

    private fun startAudioCapture() {
        val p = projection ?: error("MediaProjection chưa sẵn sàng")
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 4, 32768))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        audioRecord?.startRecording()
        scope.launch(Dispatchers.IO) { captureLoop() }
    }

    private suspend fun captureLoop() {
        val record = audioRecord ?: return
        val readBuf = ByteArray(4096)
        val chunk = ByteArrayOutputStream(96000)
        var voicedSamples = 0L
        val targetBytes = 16000 * 2 * 24 / 10
        val overlapBytes = 16000 * 2 * 35 / 100
        var carry = ByteArray(0)

        while (running && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val n = record.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue
            if (chunk.size() == 0 && carry.isNotEmpty()) chunk.write(carry)
            chunk.write(readBuf, 0, n)
            voicedSamples += countVoiced(readBuf, n)

            if (chunk.size() >= targetBytes) {
                val pcm = chunk.toByteArray()
                val voiceRatio = voicedSamples.toDouble() / (pcm.size / 2).coerceAtLeast(1)
                if (voiceRatio > 0.035) {
                    val file = File(cacheDir, "cap_${System.nanoTime()}.wav")
                    writeWav(file, pcm, 16000, 1)
                    submitLatest(file)
                    lastStatus = "Đã bắt âm thanh • đang nhận dạng…"
                } else {
                    lastStatus = "Đang nghe… chưa thấy giọng nói rõ"
                }
                carry = pcm.copyOfRange((pcm.size - overlapBytes).coerceAtLeast(0), pcm.size)
                chunk.reset()
                voicedSamples = 0
            }
        }
    }

    private fun countVoiced(bytes: ByteArray, length: Int): Long {
        var voiced = 0L
        var i = 0
        while (i + 1 < length) {
            val s = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            if (abs(s) > 520) voiced++
            i += 2
        }
        return voiced
    }

    private fun submitLatest(file: File) {
        val old = latestChunk.getAndSet(file)
        if (old != null && old != file) old.delete()
    }

    private suspend fun transcriptionWorker() {
        while (running) {
            val file = latestChunk.getAndSet(null)
            if (file == null) {
                delay(80)
                continue
            }
            try {
                val model = whisperModel ?: continue
                val started = System.currentTimeMillis()
                val result = Whisper.transcribe(
                    model,
                    file.absolutePath,
                    WhisperConfig(language = "en", threads = 6, printTimestamps = false)
                )
                val raw = cleanWhisper(result.text)
                val fresh = dedupe(lastOriginal, raw)
                if (raw.isNotBlank()) lastOriginal = raw
                if (fresh.isBlank() || isWhisperNoise(fresh)) continue

                lastStatus = "EN: ${fresh.take(90)}"
                val vi = translate(fresh)
                if (vi.isNotBlank()) {
                    val elapsed = System.currentTimeMillis() - started
                    lastStatus = "VI: ${vi.take(90)} • xử lý ${elapsed}ms"
                    speakLatest(vi, elapsed)
                }
            } catch (t: Throwable) {
                lastStatus = "Nhận dạng lỗi: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                file.delete()
            }
        }
    }

    private fun cleanWhisper(s: String): String = s
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\([^)]*(music|applause|laughter)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isWhisperNoise(s: String): Boolean {
        val x = s.lowercase(Locale.US).trim().trim('.', '!', '?', ' ')
        return x.isBlank() || x in setOf("thank you", "thanks for watching", "you")
    }

    private fun dedupe(previous: String, current: String): String {
        if (current.isBlank() || previous.isBlank()) return current
        val p = previous.trim().split(Regex("\\s+"))
        val c = current.trim().split(Regex("\\s+"))
        val max = minOf(10, p.size, c.size)
        for (k in max downTo 2) {
            val a = p.takeLast(k).joinToString(" ").lowercase(Locale.US).trimPunct()
            val b = c.take(k).joinToString(" ").lowercase(Locale.US).trimPunct()
            if (a == b) return c.drop(k).joinToString(" ")
        }
        return current
    }

    private fun String.trimPunct(): String = replace(Regex("[^a-z0-9' ]"), "").replace(Regex("\\s+"), " ").trim()

    private suspend fun awaitTranslatorModel(t: Translator) = suspendCancellableCoroutine<Unit> { cont ->
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
            .addOnFailureListener { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun translate(text: String): String {
        val t = translator ?: return text
        return suspendCancellableCoroutine { cont ->
            t.translate(text)
                .addOnSuccessListener { out -> if (cont.isActive) cont.resume(out) }
                .addOnFailureListener { if (cont.isActive) cont.resume(text) }
        }
    }

    private fun speakLatest(text: String, processingMs: Long) {
        if (!ttsReady || text.isBlank()) return
        val engine = tts ?: return
        if (engine.isSpeaking) {
            pendingSpeech.getAndSet(text)
            return
        }
        val speed = when {
            processingMs > 2600 -> 1.32f
            processingMs > 1700 -> 1.24f
            else -> 1.16f
        }
        engine.setSpeechRate(speed)
        requestDuck()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            return
        }
        val engine = tts ?: return
        engine.language = Locale("vi", "VN")
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(1.16f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                abandonDuck()
                val next = pendingSpeech.getAndSet(null)
                if (!next.isNullOrBlank() && running) speakLatest(next, 2200)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonDuck()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                abandonDuck()
            }
        })
        ttsReady = true
    }

    private fun requestDuck() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonDuck() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        FileOutputStream(file).use { out ->
            val byteRate = sampleRate * channels * 2
            val dataSize = pcm.size
            val totalSize = dataSize + 36
            fun le32(v: Int) {
                out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
            }
            fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
            out.write("RIFF".toByteArray()); le32(totalSize); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(sampleRate); le32(byteRate); le16(channels * 2); le16(16)
            out.write("data".toByteArray()); le32(dataSize); out.write(pcm)
        }
    }

    private fun stopEverything() {
        if (!running && projection == null && audioRecord == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = false
        lastStatus = "Đã dừng."
        try { audioRecord?.stop() } catch (_: Throwable) { }
        try { audioRecord?.release() } catch (_: Throwable) { }
        audioRecord = null
        try { projection?.stop() } catch (_: Throwable) { }
        projection = null
        latestChunk.getAndSet(null)?.delete()
        pendingSpeech.set(null)
        try { tts?.stop() } catch (_: Throwable) { }
        abandonDuck()
        translator?.close(); translator = null
        whisperModel?.let { try { Whisper.releaseModel(it) } catch (_: Throwable) { } }
        whisperModel = null
        scope.coroutineContext.cancelChildren()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { audioRecord?.release() } catch (_: Throwable) { }
        try { projection?.stop() } catch (_: Throwable) { }
        translator?.close()
        whisperModel?.let { try { Whisper.releaseModel(it) } catch (_: Throwable) { } }
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "LiveDub Việt", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Giữ dịch vụ bắt âm thanh nội bộ hoạt động"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
