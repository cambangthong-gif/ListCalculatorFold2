(() => {
  if (window.__ALAD_DUB_STANDALONE__) return;
  window.__ALAD_DUB_STANDALONE__ = true;

  const state = {
    running: false,
    settings: null,
    video: null,
    originalVolume: null,
    mode: "",
    chunks: new Map(),
    loadingChunks: new Set(),
    spokenKeys: new Set(),
    lastSpokenKey: "",
    timer: null,
    observer: null,
    lastGenericCaption: "",
    pageCaptionBusy: false,
    overlay: null,
    button: null,
    status: "Sẵn sàng"
  };

  function sendStatus(text, detail = "") {
    state.status = text;
    chrome.runtime.sendMessage({
      type: "ALAD_STATUS",
      text,
      detail,
      url: location.href
    }).catch(() => {});
  }

  function isYoutubeWatch() {
    return /(^|\.)youtube\.com$/.test(location.hostname) &&
      location.pathname === "/watch" &&
      new URL(location.href).searchParams.has("v");
  }

  function findVideo() {
    const list = [...document.querySelectorAll("video")];
    if (!list.length) return null;
    list.sort((a, b) => {
      const ar = a.getBoundingClientRect();
      const br = b.getBoundingClientRect();
      const aa = ar.width * ar.height + (!a.paused ? 1e9 : 0);
      const ba = br.width * br.height + (!b.paused ? 1e9 : 0);
      return ba - aa;
    });
    return list[0];
  }

  function ensureOverlay() {
    const video = state.video || findVideo();
    if (!video) return null;
    let host = video.parentElement;
    if (!host) return null;

    let overlay = document.getElementById("alad-dub-overlay");
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.id = "alad-dub-overlay";
      overlay.innerHTML = '<div class="alad-dub-translated"></div><div class="alad-dub-source"></div>';
      host.appendChild(overlay);
    }
    state.overlay = overlay;
    return overlay;
  }

  function showCaption(translated, source = "") {
    if (!state.settings?.showTranslatedCaptions) return;
    const overlay = ensureOverlay();
    if (!overlay) return;
    overlay.querySelector(".alad-dub-translated").textContent = translated || "";
    overlay.querySelector(".alad-dub-source").textContent = source || "";
    overlay.classList.toggle("show", !!translated);
  }

  function hideCaption() {
    if (state.overlay) state.overlay.classList.remove("show");
  }

  function voiceForLanguage(lang, preferred) {
    const voices = speechSynthesis.getVoices();
    if (preferred) {
      const exact = voices.find(v => v.name === preferred);
      if (exact) return exact;
    }
    const prefix = String(lang || "").toLowerCase().split("-")[0];
    return voices.find(v => v.lang.toLowerCase().startsWith(prefix)) ||
           voices.find(v => v.default) ||
           voices[0] || null;
  }

  function estimatedSpeechSeconds(text) {
    const words = String(text).trim().split(/\s+/).filter(Boolean).length;
    if (!words) return 0.5;
    return Math.max(0.7, words / 2.65);
  }

  function restoreOriginalVolume() {
    if (state.video && state.originalVolume != null) {
      try { state.video.volume = state.originalVolume; } catch {}
    }
  }

  function speakCue(cue) {
    if (!state.running || !state.video || state.video.paused) return;

    speechSynthesis.cancel();

    const utter = new SpeechSynthesisUtterance(cue.translated_text);
    const voice = voiceForLanguage(state.settings.targetLanguage, state.settings.voiceName);
    if (voice) {
      utter.voice = voice;
      utter.lang = voice.lang;
    } else {
      utter.lang = state.settings.targetLanguage;
    }

    const cueDuration = Math.max(0.7, cue.end - cue.start);
    const estimate = estimatedSpeechSeconds(cue.translated_text);
    const adaptive = Math.max(0.72, Math.min(1.85, estimate / cueDuration));
    utter.rate = Math.max(0.5, Math.min(2.0,
      Number(state.settings.voiceRate || 1) *
      Number(state.video.playbackRate || 1) *
      adaptive
    ));
    utter.volume = Math.max(0, Math.min(1, Number(state.settings.dubVolume ?? 1)));

    const base = state.originalVolume ?? state.video.volume;
    try {
      state.video.volume = Math.max(0, Math.min(1,
        base * Number(state.settings.duckLevel ?? 0.16)
      ));
    } catch {}

    showCaption(cue.translated_text, cue.source_text);
    sendStatus("Đang lồng tiếng", `${cue.start.toFixed(1)}s · ${cue.translated_text.slice(0, 70)}`);

    const done = () => {
      restoreOriginalVolume();
      setTimeout(() => {
        if (!speechSynthesis.speaking) hideCaption();
      }, 180);
    };
    utter.onend = done;
    utter.onerror = done;
    speechSynthesis.speak(utter);
  }

  function allSegments() {
    const result = [];
    for (const value of state.chunks.values()) result.push(...value);
    result.sort((a, b) => a.start - b.start);
    return result;
  }

  function cueAtTime(time) {
    const segments = allSegments();
    let best = null;
    for (const s of segments) {
      if (s.start <= time + 0.12 && s.end >= time - 0.18) best = s;
      if (s.start > time + 0.2) break;
    }
    return best;
  }

  async function requestChunk(start) {
    const chunkSeconds = Number(state.settings.chunkSeconds || 90);
    const end = start + chunkSeconds;
    const key = `${start}`;
    if (state.chunks.has(key) || state.loadingChunks.has(key)) return;

    state.loadingChunks.add(key);
    sendStatus("Đang chuẩn bị bản dịch", `${Math.round(start)}–${Math.round(end)} giây`);

    try {
      const res = await chrome.runtime.sendMessage({
        type: "ALAD_GENERATE_YOUTUBE_CHUNK",
        payload: {
          url: location.href,
          start,
          end,
          targetLanguage: state.settings.targetLanguage
        }
      });
      if (!res?.ok) throw new Error(res?.error || "Không tạo được phụ đề.");
      state.chunks.set(key, res.data.segments || []);
      sendStatus(
        res.data.fromCache ? "Đã nạp cache" : "Đã dịch xong",
        `${res.data.segments?.length || 0} đoạn · ${Math.round(start)}–${Math.round(end)}s`
      );
    } catch (e) {
      sendStatus("Lỗi tạo bản dịch", e?.message || String(e));
    } finally {
      state.loadingChunks.delete(key);
    }
  }

  function ensureYoutubeChunks() {
    if (!state.running || state.mode !== "youtube-ai" || !state.video) return;
    const size = Number(state.settings.chunkSeconds || 90);
    const t = Math.max(0, state.video.currentTime || 0);
    const current = Math.floor(t / size) * size;
    requestChunk(current);
    if (t > current + size * 0.38) requestChunk(current + size);
  }

  function resetSync(replayCurrent = true) {
    speechSynthesis.cancel();
    restoreOriginalVolume();
    hideCaption();
    state.lastSpokenKey = "";
    if (!replayCurrent) {
      const t = state.video?.currentTime || 0;
      const cue = cueAtTime(t);
      if (cue) state.lastSpokenKey = cue.id || `${cue.start}-${cue.translated_text}`;
    }
  }

  function tickYoutube() {
    if (!state.running || state.mode !== "youtube-ai") return;
    if (!state.video || !document.contains(state.video)) {
      state.video = findVideo();
      if (!state.video) return;
      attachVideoEvents();
    }

    ensureYoutubeChunks();
    if (state.video.paused) return;

    const cue = cueAtTime(state.video.currentTime);
    if (!cue) return;
    const key = cue.id || `${cue.start.toFixed(2)}|${cue.translated_text}`;
    if (key === state.lastSpokenKey) return;

    state.lastSpokenKey = key;
    speakCue(cue);
  }

  function activeTextTrackCaption() {
    const v = state.video;
    if (!v?.textTracks) return "";
    const out = [];
    for (const track of v.textTracks) {
      if (track.kind !== "subtitles" && track.kind !== "captions") continue;
      try { if (track.mode === "disabled") track.mode = "hidden"; } catch {}
      const cues = track.activeCues;
      if (!cues) continue;
      for (let i = 0; i < cues.length; i++) {
        const text = cues[i]?.text;
        if (text) out.push(text);
      }
    }
    return out.join(" ").replace(/\s+/g, " ").trim();
  }

  function domCaption() {
    const selectors = isYoutubeWatch()
      ? [".ytp-caption-segment"]
      : [
          "[class*='subtitle' i]",
          "[class*='caption' i]",
          "[data-testid*='subtitle' i]",
          "[data-testid*='caption' i]"
        ];

    const nodes = [...document.querySelectorAll(selectors.join(","))];
    return nodes
      .filter(n => {
        const r = n.getBoundingClientRect();
        return r.width > 0 && r.height > 0;
      })
      .map(n => n.textContent || "")
      .join(" ")
      .replace(/\s+/g, " ")
      .trim();
  }

  async function translateGenericCaption(text) {
    text = String(text || "").trim();
    if (!state.running || !text || text === state.lastGenericCaption || state.pageCaptionBusy) return;
    state.lastGenericCaption = text;
    state.pageCaptionBusy = true;
    try {
      const res = await chrome.runtime.sendMessage({
        type: "ALAD_TRANSLATE_TEXT",
        text,
        targetLanguage: state.settings.targetLanguage
      });
      if (!res?.ok) throw new Error(res?.error || "Dịch phụ đề lỗi");
      speakCue({
        start: state.video?.currentTime || 0,
        end: (state.video?.currentTime || 0) + Math.max(1.2, estimatedSpeechSeconds(res.translated)),
        source_text: text,
        translated_text: res.translated
      });
    } catch (e) {
      sendStatus("Dịch phụ đề trang lỗi", e?.message || String(e));
    } finally {
      state.pageCaptionBusy = false;
    }
  }

  function tickGeneric() {
    if (!state.running || state.mode !== "page-captions") return;
    state.video = state.video || findVideo();
    if (!state.video) return;
    const text = activeTextTrackCaption() || domCaption();
    if (text) translateGenericCaption(text);
  }

  function attachVideoEvents() {
    if (!state.video) return;
    const v = state.video;
    if (v.__aladEventsAttached) return;
    v.__aladEventsAttached = true;

    v.addEventListener("pause", () => {
      if (!state.running) return;
      speechSynthesis.cancel();
      restoreOriginalVolume();
      sendStatus("Tạm dừng");
    });

    v.addEventListener("play", () => {
      if (!state.running) return;
      resetSync(true);
      sendStatus("Đang phát");
      if (state.mode === "youtube-ai") ensureYoutubeChunks();
    });

    v.addEventListener("seeked", () => {
      if (!state.running) return;
      resetSync(true);
      state.lastGenericCaption = "";
      sendStatus("Đã đồng bộ sau khi tua", `${v.currentTime.toFixed(1)}s`);
      if (state.mode === "youtube-ai") ensureYoutubeChunks();
    });

    v.addEventListener("ratechange", () => {
      if (!state.running) return;
      resetSync(true);
      sendStatus("Tốc độ video", `${v.playbackRate}×`);
    });
  }

  async function start(settings) {
    stop(false);
    state.settings = settings;
    state.video = findVideo();
    if (!state.video) {
      sendStatus("Không tìm thấy video trên trang");
      return;
    }

    state.originalVolume = state.video.volume;
    state.running = true;
    state.chunks.clear();
    state.loadingChunks.clear();
    state.spokenKeys.clear();
    state.lastSpokenKey = "";
    state.lastGenericCaption = "";

    attachVideoEvents();

    if (isYoutubeWatch()) {
      state.mode = "youtube-ai";
      sendStatus("YouTube AI Dubbing", "Đang tạo bản dịch theo timeline...");
      ensureYoutubeChunks();
      state.timer = setInterval(tickYoutube, 120);
    } else {
      state.mode = "page-captions";
      sendStatus("Universal Dubbing", "Dùng phụ đề/TextTrack của trang");
      state.timer = setInterval(tickGeneric, 250);
    }

    updateButton();
  }

  function stop(notify = true) {
    state.running = false;
    if (state.timer) clearInterval(state.timer);
    state.timer = null;
    speechSynthesis.cancel();
    restoreOriginalVolume();
    hideCaption();
    state.loadingChunks.clear();
    state.pageCaptionBusy = false;
    if (notify) sendStatus("Đã dừng ALAD Dub");
    updateButton();
  }

  async function getVoices() {
    let voices = speechSynthesis.getVoices();
    if (!voices.length) {
      await new Promise(resolve => {
        const timeout = setTimeout(resolve, 700);
        speechSynthesis.addEventListener("voiceschanged", () => {
          clearTimeout(timeout);
          resolve();
        }, { once: true });
      });
      voices = speechSynthesis.getVoices();
    }
    return voices.map(v => ({ name: v.name, lang: v.lang, default: v.default }));
  }

  function ensurePlayerButton() {
    if (!isYoutubeWatch()) return;
    if (document.getElementById("alad-dub-player-button")) {
      state.button = document.getElementById("alad-dub-player-button");
      updateButton();
      return;
    }
    const controls = document.querySelector(".ytp-right-controls");
    if (!controls) return;

    const btn = document.createElement("button");
    btn.id = "alad-dub-player-button";
    btn.type = "button";
    btn.className = "ytp-button alad-dub-player-button";
    btn.title = "ALAD Dub";
    btn.textContent = "ALAD";
    btn.addEventListener("click", async e => {
      e.preventDefault();
      e.stopPropagation();
      if (state.running) {
        stop();
        return;
      }

      const cfg = await chrome.runtime.sendMessage({ type: "ALAD_GET_CONFIG" });
      if (!cfg?.hasKey) {
        await chrome.runtime.sendMessage({ type: "ALAD_OPEN_PANEL" });
        return;
      }
      await start(cfg.settings);
    });

    controls.prepend(btn);
    state.button = btn;
    updateButton();
  }

  function updateButton() {
    if (!state.button) return;
    state.button.classList.toggle("active", state.running);
    state.button.textContent = state.running ? "DUB" : "ALAD";
  }

  chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
    (async () => {
      if (msg?.type === "ALAD_START") {
        await start(msg.settings);
        sendResponse({ ok: true, mode: state.mode });
        return;
      }
      if (msg?.type === "ALAD_STOP") {
        stop();
        sendResponse({ ok: true });
        return;
      }
      if (msg?.type === "ALAD_GET_VOICES") {
        sendResponse({ ok: true, voices: await getVoices() });
        return;
      }
      if (msg?.type === "ALAD_GET_STATE") {
        sendResponse({
          ok: true,
          running: state.running,
          mode: state.mode,
          status: state.status,
          time: state.video?.currentTime || 0
        });
        return;
      }
      sendResponse({ ok: false });
    })().catch(e => sendResponse({ ok: false, error: e?.message || String(e) }));
    return true;
  });

  const rootObserver = new MutationObserver(() => {
    state.video = state.video && document.contains(state.video) ? state.video : findVideo();
    ensurePlayerButton();
    if (state.running && state.video) attachVideoEvents();
  });
  rootObserver.observe(document.documentElement, { childList: true, subtree: true });

  setInterval(ensurePlayerButton, 1200);
  ensurePlayerButton();
})();
