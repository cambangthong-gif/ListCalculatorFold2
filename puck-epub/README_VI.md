# Puck EPUB Reader v1.0

Android EPUB reader using Gemini Flash-Lite TTS voice Puck (online) and proactive PCM prefetch.

## Features
- Open local DRM-free EPUB using Android SAF, including zipped EPUB 2/3 with standard OPF spine.
- Show chapters, navigate by chapter or audio segment; highlight the current paragraph; adjustable reading font.
- Gemini 3.8 Flash-Lite TTS voice **Puck** with cheerful style.
- Prefetch up to three audio segments at once using a bounded executor; audio playback in a continuous 24 kHz, 16-bit mono AudioTrack; bounded 16 MB memory audio cache for replay.
- Persist EPUB document access, reading position, and encrypted Gemini API key (Android Keystore).
- Runs on Galaxy Z Fold 6 and RedMagic 8 Pro (Android 8+).
- Separate package vn.puck.epubreader; does not change Moon Gemini TTS.

## Use
1. Install the GitHub Actions generated app-debug.apk.
2. Tap **API key**, create/free key at https://aistudio.google.com/apikey, paste, Save. This is a separate app: key cannot be imported from Moon Gemini TTS.
3. Tap **Mở EPUB**; choose your book (not PDF).
4. Tap **▶ Đọc / ⏸**. First audio must still wait for Gemini; later segments are synthesized during ongoing playback.
5. Use chapter/segment arrows and A-/A+.
6. EPUB copy in app cache is used for extraction and may be reloaded from saved document URI when app restarts.

## Limitations
- MVP supports EPUB **text**, not images, charts, CSS layout, footnotes, DRM, or PDF.
- Cloud Gemini synthesis requires internet, costs API quota and may be throttled. The text is sent directly to Google; key stays in Android Keystore.
- Prefetch is best-effort, not guaranteed gapless with network latency or API quotas. The first segment still takes server latency. Requesting three clips together may encounter API rate limits; user can retry after quota refresh.
- Text controls and audio playback currently operate inside the Activity. Reliable playback with the screen locked/background needs a separate media foreground service (planned).
- **Not tested on a physical device or with user API credentials.** Passing CI means it installs, not that speech is guaranteed to work.
- APK is a debug build signed with runner-generated debug key: install updates may require uninstalling and re-entering your API key.

## Project build
Java 17, Android Gradle Plugin 8.7.3, Gradle 8.10.2, compileSdk 35, targetSdk 34. GitHub Actions: .github/workflows/puck-epub-reader.yml.
