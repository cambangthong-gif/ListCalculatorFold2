# LiveDub Việt v0.1

Prototype Android: internal playback capture → Whisper tiny.en → ML Kit EN-VI translation → Vietnamese TTS.

Designed for Samsung Galaxy Z Fold 6 / Android 16, minSdk 29, arm64-v8a.

Notes:
- Android requires RECORD_AUDIO permission for AudioPlaybackCapture even though this app does not create a microphone audio source.
- Playback capture depends on the source app allowing capture.
- First run downloads the ML Kit translation model.
- Live Sync keeps only the newest waiting audio chunk to avoid ever-growing dubbing delay.
