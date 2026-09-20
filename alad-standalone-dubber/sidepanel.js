const $ = id => document.getElementById(id);

let activeTab = null;

async function getActiveTab() {
  const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTab = tabs[0] || null;
  return activeTab;
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

async function loadVoices() {
  if (!activeTab?.id) return;
  try {
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
    const s = cfg.settings;
    $("targetLanguage").value = s.targetLanguage || "vi";
    $("voiceName").dataset.saved = s.voiceName || "";
    $("dubVolume").value = s.dubVolume ?? 1;
    $("duckLevel").value = s.duckLevel ?? 0.16;
    $("voiceRate").value = s.voiceRate ?? 1;
    $("showTranslatedCaptions").checked = s.showTranslatedCaptions !== false;
  }

  await getActiveTab();

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
}

async function start() {
  try {
    await saveConfig();
    await getActiveTab();
    if (!activeTab?.id) throw new Error("Không tìm thấy tab hiện tại.");

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
