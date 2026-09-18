(() => {
  let connected = false;
  let video = null;
  let observer = null;
  let lastCaption = "";
  let lastCaptionAt = 0;
  let lastVideoId = "";
  let lastStateSent = 0;
  let originalVolumeBeforeAlad = null;

  function send(payload) {
    if (!connected) return;
    chrome.runtime.sendMessage({ type: "aladBridgeMessage", payload }).catch(() => {});
  }

  function videoId() {
    try { return new URL(location.href).searchParams.get("v") || ""; }
    catch { return ""; }
  }

  function currentState(type = "state") {
    if (!video) return;
    send({
      type,
      videoId: videoId(),
      currentTime: Number(video.currentTime || 0),
      duration: Number(video.duration || 0),
      playbackRate: Number(video.playbackRate || 1),
      paused: Boolean(video.paused)
    });
  }

  function ensureCaptionsEnabled() {
    if (!connected) return;
    const button = document.querySelector(".ytp-subtitles-button");
    if (!button) return;
    const pressed = button.getAttribute("aria-pressed");
    if (pressed === "false") {
      try { button.click(); } catch {}
    }
  }

  function captionText() {
    const segments = [...document.querySelectorAll(".ytp-caption-segment")];
    if (segments.length) {
      return segments.map(x => (x.textContent || "").trim()).filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
    }

    const windows = [...document.querySelectorAll(".ytp-caption-window-container .caption-visual-line")];
    return windows.map(x => (x.textContent || "").trim()).filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
  }

  function emitCaption() {
    if (!connected || !video) return;
    const text = captionText();
    if (!text || text === lastCaption) return;

    const now = performance.now();
    const elapsed = lastCaptionAt ? Math.max(0.2, (now - lastCaptionAt) / 1000) : 0.8;
    lastCaptionAt = now;
    lastCaption = text;

    send({
      type: "caption",
      text,
      videoId: videoId(),
      currentTime: Number(video.currentTime || 0),
      duration: Math.min(4, elapsed),
      playbackRate: Number(video.playbackRate || 1),
      paused: Boolean(video.paused)
    });
  }

  function attachCaptionObserver() {
    if (observer) observer.disconnect();
    observer = new MutationObserver(() => emitCaption());
    const root = document.querySelector(".ytp-caption-window-container") || document.body;
    observer.observe(root, { subtree: true, childList: true, characterData: true });
  }

  function detachVideo() {
    if (!video) return;
    video.removeEventListener("play", onPlay);
    video.removeEventListener("pause", onPause);
    video.removeEventListener("seeked", onSeeked);
    video.removeEventListener("ratechange", onRate);
    video.removeEventListener("loadedmetadata", onLoaded);
    video = null;
  }

  function onPlay() { currentState("state"); }
  function onPause() { currentState("state"); }
  function onSeeked() {
    lastCaption = "";
    currentState("seek");
  }
  function onRate() { currentState("state"); }
  function onLoaded() {
    lastCaption = "";
    send({
      type: "video",
      videoId: videoId(),
      currentTime: Number(video?.currentTime || 0),
      duration: Number(video?.duration || 0),
      playbackRate: Number(video?.playbackRate || 1),
      paused: Boolean(video?.paused)
    });
  }

  function attachVideo() {
    const found = document.querySelector("video");
    if (!found || found === video) return;
    detachVideo();
    video = found;
    video.addEventListener("play", onPlay);
    video.addEventListener("pause", onPause);
    video.addEventListener("seeked", onSeeked);
    video.addEventListener("ratechange", onRate);
    video.addEventListener("loadedmetadata", onLoaded);
    onLoaded();
  }

  function setVolume(value) {
    attachVideo();
    if (!video) return;
    if (originalVolumeBeforeAlad == null) originalVolumeBeforeAlad = video.volume;
    const v = Math.max(0, Math.min(1, Number(value)));
    if (Number.isFinite(v)) video.volume = v;
  }

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type === "aladBridgeStatus") {
      connected = Boolean(message.connected);
      if (connected) {
        attachVideo();
        ensureCaptionsEnabled();
        attachCaptionObserver();
        currentState("state");
        emitCaption();
      } else if (video && originalVolumeBeforeAlad != null) {
        video.volume = originalVolumeBeforeAlad;
        originalVolumeBeforeAlad = null;
      }
    }

    if (message?.type === "aladSetVolume") {
      setVolume(message.value);
    }

    if (message?.type === "aladConfig") {
      connected = true;
      attachVideo();
      ensureCaptionsEnabled();
      setVolume(message.originalVolume ?? 1);
      attachCaptionObserver();
      currentState("state");
      emitCaption();
    }
  });

  chrome.runtime.sendMessage({ type: "aladBridgeProbe" }).then(r => {
    connected = Boolean(r?.connected);
    if (connected) {
      attachVideo();
      ensureCaptionsEnabled();
      attachCaptionObserver();
      currentState("state");
      emitCaption();
    }
  }).catch(() => {});

  setInterval(() => {
    attachVideo();

    const id = videoId();
    if (id !== lastVideoId) {
      lastVideoId = id;
      lastCaption = "";
      if (connected && video) {
        send({
          type: "video",
          videoId: id,
          currentTime: Number(video.currentTime || 0),
          duration: Number(video.duration || 0),
          playbackRate: Number(video.playbackRate || 1),
          paused: Boolean(video.paused)
        });
        ensureCaptionsEnabled();
        attachCaptionObserver();
      }
    }

    if (connected && video && performance.now() - lastStateSent > 500) {
      lastStateSent = performance.now();
      currentState("state");
    }
  }, 250);
})();