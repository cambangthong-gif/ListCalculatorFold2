const $ = id => document.getElementById(id);

let activeTab = null;

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTab = tabs[0] || null;
  return activeTab;
}


async function ensureContent() {
  await getActiveTab();
  if (!activeTab?.id) throw new Error("Không tìm thấy tab hiện tại.");

  const r = await chrome.runtime.sendMessage({
    type: "ALAD_ENSURE_CONTENT",
    tabId: activeTab.id
  });
  if (!r?.ok) throw new Error(r?.error || "Không thể kết nối ALAD với tab hiện tại.");
  return r;
}

function isYoutubeWatch(url) {
  try {
    const u = new URL(url);
    return u.hostname.includes("youtube.com") && u.pathname === "/watch" && u.searchParams.has("v");
  } catch { return false; }
}

function values() {
  return {
    targetLanguage: $("targetLanguage").value,
    voiceName: $("voiceName").value,
    dubVolume: Number($("dubVolume").value),
    duckLevel: Number($("duckLevel").value),
    voiceRate: Number($("voiceRate").value),
    chunkSeconds: 90,
    showTranslatedCaptions: $("showTranslatedCaptions").checked
  };
}

function updateNumbers() {
  $("dubVolumeText").textContent = Math.round(Number($("dubVolume").value) * 100) + "%";
  $("duckText").textContent = Math.round(Number($("duckLevel").value) * 100) + "%";
  $("rateText").textContent = Number($("voiceRate").value).toFixed(2) + "×";
}

function showKeyStatus(hasKey, storage) {
  const el = $("keyStatus");
  el.classList.remove("saved", "session");

  if (!hasKey) {
    el.textContent = "Chưa lưu API key";
    $("apiKey").placeholder = "Dán API key của anh";
    return;
  }

  if (storage === "local") {
    el.textContent = "✓ API key đã lưu trên máy này";
    el.classList.add("saved");
    $("rememberKey").checked = true;
  } else {
    el.textContent = "✓ API key đã lưu cho phiên trình duyệt này";
    el.classList.add("session");
    $("rememberKey").checked = false;
  }

  $("apiKey").value = "";
  $("apiKey").placeholder = "••••••••••••  (đang dùng key đã lưu)";
}

async function saveKeyOnly() {
  const key = $("apiKey").value.trim();
  if (!key) {
    const cfg = await chrome.runtime.sendMessage({ type: "ALAD_GET_CONFIG" });
    if (cfg?.hasKey) {
      showKeyStatus(true, cfg.keyStorage);
      $("statusTitle").textContent = "API key đã có";
      $("statusDetail").textContent = cfg.keyStorage === "local"
        ? "Đã lưu lâu dài trên trình duyệt."
        : "Đang lưu trong phiên hiện tại.";
      return;
    }
    throw new Error("Chưa nhập API key.");
  }

  const res = await chrome.runtime.sendMessage({
    type: "ALAD_SAVE_KEY",
    apiKey: key,
    rememberKey: $("rememberKey").checked
  });
  if (!res?.ok) throw new Error(res?.error || "Không lưu được API key.");

  showKeyStatus(res.hasKey, res.keyStorage);
  $("statusTitle").textContent = "Đã lưu API key";
  $("statusDetail").textContent = res.keyStorage === "local"
    ? "Key vẫn còn sau khi đóng/mở lại trình duyệt."
    : "Key chỉ tồn tại trong phiên trình duyệt này.";
}

async function loadVoices() {
  if (!activeTab?.id) return;
  try {
    await ensureContent();
    const res = await chrome.tabs.sendMessage(activeTab.id, { type: "ALAD_GET_VOICES" });
    if (!res?.ok) return;
    const selected = $("voiceName").value;
    $("voiceName").innerHTML = '<option value="">Tự động chọn giọng phù hợp</option>';
    for (const v of res.voices || []) {
      const o = document.createElement("option");
      o.value = v.name;
      o.textContent = `${v.name} · ${v.lang}${v.default ? " · mặc định" : ""}`;
      $("voiceName").appendChild(o);
    }
    $("voiceName").value = selected;
  } catch {}
}

async function init() {
  const cfg = await chrome.runtime.sendMessage({ type: "ALAD_GET_CONFIG" });
  if (cfg?.settings) {
    showKeyStatus(!!cfg.hasKey, cfg.keyStorage || "none");
    const s = cfg.settings;
    $("targetLanguage").value = s.targetLanguage || "vi";
    $("voiceName").dataset.saved = s.voiceName || "";
    $("dubVolume").value = s.dubVolume ?? 1;
    $("duckLevel").value = s.duckLevel ?? 0.16;
    $("voiceRate").value = s.voiceRate ?? 1;
    $("showTranslatedCaptions").checked = s.showTranslatedCaptions !== false;
  }

  await getActiveTab();

  try {
    await ensureContent();
  } catch (e) {
    $("statusTitle").textContent = "Chưa kết nối với trang";
    $("statusDetail").textContent = e?.message || String(e);
  }

  if (isYoutubeWatch(activeTab?.url || "")) {
    $("modeTitle").textContent = "YouTube AI Dubbing";
    $("modeText").textContent = "Không cần caption: Gemini đọc trực tiếp URL video và tạo timeline.";
  } else {
    $("modeTitle").textContent = "Universal Caption Dubbing";
    $("modeText").textContent = "Dùng phụ đề/TextTrack mà trang web đang cung cấp.";
  }

  await loadVoices();
  if ($("voiceName").dataset.saved) $("voiceName").value = $("voiceName").dataset.saved;

  updateNumbers();
  refreshState();
}

async function saveConfig() {
  const res = await chrome.runtime.sendMessage({
    type: "ALAD_SAVE_CONFIG",
    apiKey: $("apiKey").value.trim(),
    rememberKey: $("rememberKey").checked,
    settings: values()
  });
  if (!res?.ok) throw new Error(res?.error || "Không lưu được cài đặt");
  showKeyStatus(!!res.hasKey, res.keyStorage || "none");
  if (!res.hasKey) throw new Error("Chưa có Gemini API key. Hãy nhập key rồi bấm Lưu API key.");
}

async function start() {
  try {
    await saveConfig();
    await ensureContent();

    const res = await chrome.tabs.sendMessage(activeTab.id, {
      type: "ALAD_START",
      settings: values()
    });
    if (!res?.ok) throw new Error(res?.error || "Không khởi động được ALAD Dub.");
    $("dot").classList.add("on");
    $("statusTitle").textContent = "Đang chạy";
    $("statusDetail").textContent = res.mode === "youtube-ai"
      ? "Đang chuẩn bị đoạn dịch đầu tiên..."
      : "Đang chờ phụ đề của trang...";
  } catch (e) {
    $("statusTitle").textContent = "Lỗi";
    $("statusDetail").textContent = e?.message || String(e);
  }
}

async function stop() {
  await getActiveTab();
  if (activeTab?.id) {
    try { await ensureContent(); } catch {}
    try { await chrome.tabs.sendMessage(activeTab.id, { type: "ALAD_STOP" }); } catch {}
  }
  $("dot").classList.remove("on");
  $("statusTitle").textContent = "Đã dừng";
  $("statusDetail").textContent = "";
}

async function refreshState() {
  await getActiveTab();
  if (!activeTab?.id) return;
  try {
    await ensureContent();
    const s = await chrome.tabs.sendMessage(activeTab.id, { type: "ALAD_GET_STATE" });
    $("dot").classList.toggle("on", !!s?.running);
    if (s?.status) $("statusTitle").textContent = s.status;
  } catch {}
}

chrome.runtime.onMessage.addListener(msg => {
  if (msg?.type !== "ALAD_STATUS") return;
  $("statusTitle").textContent = msg.text || "";
  $("statusDetail").textContent = msg.detail || "";
});

$("start").addEventListener("click", start);
$("stop").addEventListener("click", stop);
$("saveKey").addEventListener("click", async () => {
  try {
    await saveKeyOnly();
  } catch (e) {
    $("statusTitle").textContent = "Lỗi lưu API key";
    $("statusDetail").textContent = e?.message || String(e);
  }
});
$("clearKey").addEventListener("click", async () => {
  const r = await chrome.runtime.sendMessage({ type: "ALAD_CLEAR_KEY" });
  if (r?.ok) {
    $("apiKey").value = "";
    showKeyStatus(false, "none");
    $("statusTitle").textContent = "Đã xóa API key";
    $("statusDetail").textContent = "";
  }
});
$("clearCache").addEventListener("click", async () => {
  const r = await chrome.runtime.sendMessage({ type: "ALAD_CLEAR_CACHE" });
  $("statusTitle").textContent = "Đã xóa cache";
  $("statusDetail").textContent = `${r?.removed || 0} đoạn cache`;
});
$("toggleKey").addEventListener("click", () => {
  const input = $("apiKey");
  input.type = input.type === "password" ? "text" : "password";
  $("toggleKey").textContent = input.type === "password" ? "Hiện" : "Ẩn";
});
for (const id of ["dubVolume", "duckLevel", "voiceRate"]) {
  $(id).addEventListener("input", updateNumbers);
}

init();
setInterval(refreshState, 1800);
