package com.vinh.livedub;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.speech.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class LiveDubService extends Service {
    private static final String CHANNEL_ID = "livedub_channel";
    private static final int NOTIF_ID = 1001;

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running = false;

    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor[] speechPipe;
    private volatile OutputStream speechOut;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Translator translator;
    private volatile boolean translatorReady = false;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    private String lastPartialBase = "";
    private String lastHandledText = "";
    private long lastPartialAt = 0;
    private final AtomicLong seq = new AtomicLong();
    private volatile long lastSpokenSeq = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        initTranslator();
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Đang khởi động…"));
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("resultData");
        if (resultCode != Activity.RESULT_OK || data == null) {
            updateNotification("Thiếu quyền capture");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, main);
            startPipeline();
        } catch (Throwable t) {
            updateNotification("Lỗi khởi tạo: " + shortMsg(t));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startPipeline() throws Exception {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .excludeUid(android.os.Process.myUid())
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min * 4, 32768);

        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(config)
                .build();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord không khởi tạo được");
        }

        setupRecognizer();
        audioRecord.startRecording();
        running = true;
        captureThread = new Thread(this::captureLoop, "LiveDubCapture");
        captureThread.start();
        updateNotification("Đang nghe audio nội bộ • Anh → Việt");
    }

    private void captureLoop() {
        byte[] buffer = new byte[4096];
        while (running) {
            try {
                int n = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                OutputStream out = speechOut;
                if (n > 0 && out != null) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (Throwable e) {
                if (running) main.post(() -> restartRecognizer("Luồng nhận dạng được nối lại"));
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void setupRecognizer() {
        main.post(() -> {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    updateNotification("Máy không có dịch vụ nhận dạng giọng nói");
                    return;
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { updateNotification("Live Sync đang chạy • mở YouTube/X"); }
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onError(int error) {
                        if (running) main.postDelayed(() -> restartRecognizer("Nhận dạng lỗi " + error + ", đang nối lại"), 600);
                    }
                    @Override public void onResults(Bundle results) { handleBundle(results, true); }
                    @Override public void onPartialResults(Bundle partialResults) { handleBundle(partialResults, false); }
                    @Override public void onEvent(int eventType, Bundle params) {}
                    @Override public void onSegmentResults(Bundle segmentResults) { handleBundle(segmentResults, true); }
                    @Override public void onEndOfSegmentedSession() {
                        if (running) main.postDelayed(() -> restartRecognizer("Bắt đầu phiên nhận dạng mới"), 250);
                    }
                });
                startRecognitionSession();
            } catch (Throwable t) {
                updateNotification("Không mở được nhận dạng: " + shortMsg(t));
            }
        });
    }

    private void startRecognitionSession() throws Exception {
        closeSpeechPipe();
        speechPipe = ParcelFileDescriptor.createPipe();
        speechOut = new FileOutputStream(speechPipe[1].getFileDescriptor());

        Intent r = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        r.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        r.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        r.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        r.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, speechPipe[0]);
        r.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        r.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        r.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
        r.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        recognizer.startListening(r);
    }

    private void restartRecognizer(String status) {
        updateNotification(status);
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
        try { startRecognitionSession(); } catch (Throwable t) { updateNotification("Không nối lại được nhận dạng: " + shortMsg(t)); }
    }

    private void handleBundle(Bundle b, boolean isFinal) {
        if (b == null) return;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        String text = list.get(0) == null ? "" : list.get(0).trim();
        if (text.length() < 2) return;

        if (!isFinal) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPartialAt < 1100 || text.length() < 12) return;
            lastPartialAt = now;
            String delta = suffixDelta(lastPartialBase, text);
            if (delta.split("\\s+").length < 3) return;
            lastPartialBase = text;
            processText(delta);
        } else {
            String delta = suffixDelta(lastPartialBase, text);
            lastPartialBase = "";
            if (delta.length() >= 2) processText(delta);
        }
    }

    private String suffixDelta(String oldText, String newText) {
        if (oldText == null || oldText.isEmpty()) return newText;
        if (newText.equalsIgnoreCase(oldText)) return "";
        String[] a = oldText.split("\\s+");
        String[] b = newText.split("\\s+");
        int i = 0;
        while (i < a.length && i < b.length && a[i].equalsIgnoreCase(b[i])) i++;
        if (i >= b.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = i; j < b.length; j++) { if (sb.length() > 0) sb.append(' '); sb.append(b[j]); }
        return sb.toString();
    }

    private void processText(String source) {
        source = source.trim();
        if (source.isEmpty() || source.equalsIgnoreCase(lastHandledText)) return;
        lastHandledText = source;
        if (!translatorReady) { updateNotification("Đang tải mô hình dịch Anh → Việt…"); return; }

        final long id = seq.incrementAndGet();
        translator.translate(source)
                .addOnSuccessListener(vi -> {
                    if (vi == null || vi.trim().isEmpty() || id < lastSpokenSeq) return;
                    lastSpokenSeq = id;
                    speakLive(vi.trim(), id);
                })
                .addOnFailureListener(e -> updateNotification("Lỗi dịch: " + shortMsg(e)));
    }

    private void speakLive(String vi, long id) {
        updateNotification("VI: " + vi);
        if (!ttsReady) return;
        boolean chasing = tts.isSpeaking();
        tts.setSpeechRate(chasing ? 1.28f : 1.10f);
        requestDuck();
        Bundle p = new Bundle();
        p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.speak(vi, chasing ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, p, "dub-" + id);
    }

    private void initTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build();
        translator = Translation.getClient(options);
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> { translatorReady = true; updateNotification("Sẵn sàng • Anh → Việt"); })
                .addOnFailureListener(e -> updateNotification("Không tải được mô hình dịch: " + shortMsg(e)));
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.forLanguageTag("vi-VN"));
                tts.setSpeechRate(1.10f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onDone(String utteranceId) { abandonDuck(); }
                    @Override public void onError(String utteranceId) { abandonDuck(); }
                });
                ttsReady = true;
            }
        });
    }

    private void requestDuck() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(change -> {})
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Throwable ignored) {}
    }

    private void abandonDuck() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "LiveDub Việt", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Trạng thái lồng tiếng thời gian thực");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LiveDub Việt • Live Sync")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification(text)); } catch (Throwable ignored) {}
    }

    private String shortMsg(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    private void closeSpeechPipe() {
        try { if (speechOut != null) speechOut.close(); } catch (Throwable ignored) {}
        speechOut = null;
        if (speechPipe != null) {
            try { speechPipe[0].close(); } catch (Throwable ignored) {}
            try { speechPipe[1].close(); } catch (Throwable ignored) {}
        }
        speechPipe = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Throwable ignored) {}
        try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); } } catch (Throwable ignored) {}
        closeSpeechPipe();
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        try { if (translator != null) translator.close(); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        abandonDuck();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
