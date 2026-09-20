(() => {
  if (window.__ALAD_UNIVERSAL_BRIDGE__) return;
  window.__ALAD_UNIVERSAL_BRIDGE__ = true;

  let connected = false;
  let media = null;
  let captionObserver = null;
  let textTrackHandlers = [];
  let lastCaption = "";
  let lastCaptionAt = 0;
  let lastCaptionSeenAt = 0;
  let lastStateSent = 0;
  let lastCaptionStatus = "";
  let lastMediaKey = "";
  let originalVolumeBeforeAlad = null;
  let scanTimer = null;
  let lastCaptionEnableAttempt = 0;

  const HOST = location.hostname.toLowerCase();

  function send(payload) {
    if (!connected) return;
    chrome.runtime.sendMessage({ type: "aladBridgeMessage", payload }).catch(() => {});
  }

  function mediaKey(el) {
    if (!el) return "";
    const src = el.currentSrc || el.src || "";
    return location.href + "|" + src;
  }

  function visibleScore(el) {
    if (!el) return -1;
    const r = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return -1;
    const area = Math.max(0, r.width) * Math.max(0, r.height);
    const playingBonus = !el.paused && !el.ended ? 1e9 : 0;
    const audibleBonus = !el.muted && el.volume > 0 ? 1e8 : 0;
    return area + playingBonus + audibleBonus;
  }

  function findBestMedia() {
    const list = [...document.querySelectorAll("video, audio")];
    if (!list.length) return null;
    list.sort((a, b) => visibleScore(b) - visibleScore(a));
    return list[0] || null;
  }

  function currentState(type = "state") {
    if (!media) return;
    send({
      type,
      videoId: mediaKey(media),
      currentTime: Number(media.currentTime || 0),
      duration: Number(media.duration || 0),
      playbackRate: Number(media.playbackRate || 1),
      paused: Boolean(media.paused),
      site: HOST,
      title: document.title || ""
    });
  }

  function normalize(text) {
    return String(text || "").replace(/\s+/g, " ").trim();
  }

  function emitCaption(text, source = "dom") {
    if (!connected || !media) return;
    text = normalize(text);
    if (!text || text.length > 500) return;

    lastCaptionSeenAt = performance.now();
    if (text === lastCaption) return;

    const now = performance.now();
    const elapsed = lastCaptionAt ? Math.max(0.2, (now - lastCaptionAt) / 1000) : 0.8;
    lastCaptionAt = now;
    lastCaption = text;

    send({
      type: "caption",
      text,
      videoId: mediaKey(media),
      currentTime: Number(media.currentTime || 0),
      duration: Math.min(5, elapsed),
      playbackRate: Number(media.playbackRate || 1),
      paused: Boolean(media.paused),
      source,
      site: HOST,
      title: document.title || ""
    });
  }

  function ensureCaptionsEnabled() {
    if (!connected || !media) return;

    const now = performance.now();
    if (now - lastCaptionEnableAttempt < 1200) return;
    lastCaptionEnableAttempt = now;

    // Native HTML5 tracks: hidden loads cues without forcing visible captions.
    try {
      if (media.textTracks) {
        for (const track of media.textTracks) {
          if (track.kind !== "captions" && track.kind !== "subtitles") continue;
          if (track.mode === "disabled") track.mode = "hidden";
        }
      }
    } catch {}

    // YouTube uses a custom caption renderer and often exposes no useful TextTrack.
    // If CC exists but is off, turn it on so the DOM caption stream is actually created.
    if (HOST.includes("youtube.com")) {
      const cc = document.querySelector(".ytp-subtitles-button");
      if (cc && !cc.disabled && cc.getAttribute("aria-pressed") === "false") {
        try { cc.click(); } catch {}
      }
    }
  }

  function activeTrackText() {
    if (!media?.textTracks) return "";
    const parts = [];
    for (const track of media.textTracks) {
      if (track.kind !== "captions" && track.kind !== "subtitles") continue;
      try {
        if (track.mode === "disabled") track.mode = "hidden";
      } catch {}
      const cues = track.activeCues;
      if (!cues) continue;
      for (let i = 0; i < cues.length; i++) {
        const cue = cues[i];
        const text = cue?.text || "";
        if (text) parts.push(text);
      }
    }
    return normalize(parts.join(" "));
  }

  function attachTextTracks() {
    for (const [track, fn] of textTrackHandlers) {
      try { track.removeEventListener("cuechange", fn); } catch {}
    }
    textTrackHandlers = [];

    if (!media?.textTracks) return;
    ensureCaptionsEnabled();

    for (const track of media.textTracks) {
      if (track.kind !== "captions" && track.kind !== "subtitles") continue;
      const fn = () => {
        const text = activeTrackText();
        if (text) emitCaption(text, "textTrack");
      };
      try {
        track.addEventListener("cuechange", fn);
        textTrackHandlers.push([track, fn]);
      } catch {}
    }

    const text = activeTrackText();
    if (text) emitCaption(text, "textTrack");
  }

  function isLikelyCaptionElement(el) {
    if (!(el instanceof HTMLElement) || !media) return false;
    const text = normalize(el.innerText || el.textContent || "");
    if (!text || text.length > 500) return false;

    const er = el.getBoundingClientRect();
    const mr = media.getBoundingClientRect();
    if (er.width <= 0 || er.height <= 0 || mr.width <= 0 || mr.height <= 0) return false;

    const overlapsX = er.right >= mr.left && er.left <= mr.right;
    const overlapsY = er.bottom >= mr.top && er.top <= mr.bottom;
    const nearLowerArea = er.top >= mr.top + mr.height * 0.30;
    return overlapsX && overlapsY && nearLowerArea;
  }

  function siteSpecificCaption() {
    let nodes = [];

    if (HOST.includes("youtube.com")) {
      nodes = [...document.querySelectorAll(".ytp-caption-segment")];
      if (!nodes.length) {
        nodes = [...document.querySelectorAll(".ytp-caption-window-container .caption-visual-line")];
      }
    } else if (HOST.includes("netflix.com")) {
      nodes = [...document.querySelectorAll(".player-timedtext-text-container, [class*='timedtext']")];
    } else {
      nodes = [...document.querySelectorAll(
        "[class*='subtitle' i], [class*='caption' i], [data-testid*='subtitle' i], [data-testid*='caption' i]"
      )].filter(isLikelyCaptionElement);
    }

    return normalize(nodes.map(x => x.innerText || x.textContent || "").filter(Boolean).join(" "));
  }

  function scanCaption() {
    if (!connected || !media) return;

    ensureCaptionsEnabled();

    const trackText = activeTrackText();
    if (trackText) {
      emitCaption(trackText, "textTrack");
      return;
    }

    const domText = siteSpecificCaption();
    if (domText) emitCaption(domText, "dom");
  }

  function reportCaptionStatus() {
    if (!connected || !media) return;
    const active = performance.now() - lastCaptionSeenAt < 3000;
    const state = active ? "active" : "missing";
    if (state === lastCaptionStatus) return;
    lastCaptionStatus = state;

    send({
      type: "captionStatus",
      text: state,
      videoId: mediaKey(media),
      currentTime: Number(media.currentTime || 0),
      duration: Number(media.duration || 0),
      playbackRate: Number(media.playbackRate || 1),
      paused: Boolean(media.paused),
      site: HOST,
      title: document.title || ""
    });
  }

  function attachCaptionObserver() {
    if (captionObserver) captionObserver.disconnect();

    captionObserver = new MutationObserver(() => {
      if (scanTimer) return;
      scanTimer = setTimeout(() => {
        scanTimer = null;
        scanCaption();
      }, 60);
    });

    captionObserver.observe(document.documentElement || document.body, {
      subtree: true,
      childList: true,
      characterData: true
    });
  }

  function detachMedia() {
    if (!media) return;
    media.removeEventListener("play", onPlay);
    media.removeEventListener("pause", onPause);
    media.removeEventListener("seeked", onSeeked);
    media.removeEventListener("ratechange", onRate);
    media.removeEventListener("loadedmetadata", onLoaded);
    media.removeEventListener("emptied", onLoaded);
    media = null;

    for (const [track, fn] of textTrackHandlers) {
      try { track.removeEventListener("cuechange", fn); } catch {}
    }
    textTrackHandlers = [];
  }

  function onPlay() {
    ensureCaptionsEnabled();
    currentState("state");
  }
  function onPause() { currentState("state"); }
  function onSeeked() {
    lastCaption = "";
    lastCaptionSeenAt = 0;
    lastCaptionStatus = "";
    currentState("seek");
    ensureCaptionsEnabled();
    scanCaption();
  }
  function onRate() { currentState("state"); }
  function onLoaded() {
    lastCaption = "";
    lastCaptionSeenAt = 0;
    lastCaptionStatus = "";
    currentState("video");
    ensureCaptionsEnabled();
    attachTextTracks();
    scanCaption();
  }

  function attachMedia() {
    const found = findBestMedia();
    if (!found || found === media) return;

    detachMedia();
    media = found;
    lastMediaKey = mediaKey(media);
    media.addEventListener("play", onPlay);
    media.addEventListener("pause", onPause);
    media.addEventListener("seeked", onSeeked);
    media.addEventListener("ratechange", onRate);
    media.addEventListener("loadedmetadata", onLoaded);
    media.addEventListener("emptied", onLoaded);
    ensureCaptionsEnabled();
    attachTextTracks();
    currentState("video");
    scanCaption();
  }

  function setVolume(value) {
    attachMedia();
    if (!media) return;
    if (originalVolumeBeforeAlad == null) originalVolumeBeforeAlad = media.volume;
    const v = Math.max(0, Math.min(1, Number(value)));
    if (Number.isFinite(v)) media.volume = v;
  }

  function restoreVolume() {
    if (media && originalVolumeBeforeAlad != null) {
      try { media.volume = originalVolumeBeforeAlad; } catch {}
    }
    originalVolumeBeforeAlad = null;
  }

  function activate() {
    attachMedia();
    ensureCaptionsEnabled();
    attachCaptionObserver();
    currentState("state");
    scanCaption();
  }

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type === "aladBridgeStatus") {
      connected = Boolean(message.connected);
      if (connected) activate();
      else restoreVolume();
    }

    if (message?.type === "aladSetVolume") {
      setVolume(message.value);
    }

    if (message?.type === "aladConfig") {
      connected = true;
      attachMedia();
      setVolume(message.originalVolume ?? 1);
      activate();
    }
  });

  chrome.runtime.sendMessage({ type: "aladBridgeProbe" }).then(r => {
    connected = Boolean(r?.connected);
    if (connected) activate();
  }).catch(() => {});

  setInterval(() => {
    attachMedia();

    if (media) {
      const key = mediaKey(media);
      if (key !== lastMediaKey) {
        lastMediaKey = key;
        lastCaption = "";
        lastCaptionSeenAt = 0;
        lastCaptionStatus = "";
        currentState("video");
        ensureCaptionsEnabled();
        attachTextTracks();
      }
    }

    if (connected && media) {
      ensureCaptionsEnabled();
      if (performance.now() - lastStateSent > 500) {
        lastStateSent = performance.now();
        currentState("state");
        scanCaption();
      }
      reportCaptionStatus();
    }
  }, 250);
})();