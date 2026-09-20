const DEFAULTS = {
  targetLanguage: "vi",
  voiceName: "",
  dubVolume: 1,
  duckLevel: 0.16,
  voiceRate: 1,
  chunkSeconds: 90,
  showTranslatedCaptions: true
};

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});

chrome.runtime.onStartup.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});


async function ensureContentScript(tabId) {
  if (!tabId) throw new Error("Không tìm thấy tab hiện tại.");

  try {
    await chrome.tabs.sendMessage(tabId, { type: "ALAD_GET_STATE" });
    return { injected: false };
  } catch {}

  let tab;
  try { tab = await chrome.tabs.get(tabId); } catch {}
  const url = tab?.url || "";
  if (!/^https?:\/\//i.test(url)) {
    throw new Error("ALAD Dub chỉ chạy trên trang web http/https. Hãy mở trang video rồi thử lại.");
  }

  try {
    await chrome.scripting.insertCSS({
      target: { tabId },
      files: ["content.css"]
    });
  } catch {}

  try {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["content.js"]
    });
  } catch (e) {
    throw new Error("Không thể chèn ALAD vào tab này: " + (e?.message || String(e)));
  }

  await new Promise(r => setTimeout(r, 120));

  try {
    await chrome.tabs.sendMessage(tabId, { type: "ALAD_GET_STATE" });
  } catch {
    throw new Error("ALAD đã thử chèn vào trang nhưng tab vẫn không nhận lệnh. Hãy reload trang một lần.");
  }

  return { injected: true };
}

async function getApiKeyInfo() {
  const [s, l] = await Promise.all([
    chrome.storage.session.get("geminiApiKey"),
    chrome.storage.local.get("geminiApiKey")
  ]);

  if (l.geminiApiKey) {
    return { key: l.geminiApiKey, hasKey: true, storage: "local" };
  }
  if (s.geminiApiKey) {
    return { key: s.geminiApiKey, hasKey: true, storage: "session" };
  }
  return { key: "", hasKey: false, storage: "none" };
}

async function getApiKey() {
  return (await getApiKeyInfo()).key;
}

async function saveApiKey(key, remember) {
  key = String(key || "").trim();
  if (!key) return;

  if (remember) {
    await chrome.storage.local.set({ geminiApiKey: key });
    await chrome.storage.session.remove("geminiApiKey");
  } else {
    await chrome.storage.session.set({ geminiApiKey: key });
    await chrome.storage.local.remove("geminiApiKey");
  }
}

async function applyKeyPreference(remember) {
  const info = await getApiKeyInfo();
  if (!info.hasKey) return info;

  if (remember && info.storage === "session") {
    await chrome.storage.local.set({ geminiApiKey: info.key });
    await chrome.storage.session.remove("geminiApiKey");
  } else if (!remember && info.storage === "local") {
    await chrome.storage.session.set({ geminiApiKey: info.key });
    await chrome.storage.local.remove("geminiApiKey");
  }

  return getApiKeyInfo();
}

async function clearApiKey() {
  await Promise.all([
    chrome.storage.session.remove("geminiApiKey"),
    chrome.storage.local.remove("geminiApiKey")
  ]);
}

async function loadSettings() {
  const obj = await chrome.storage.local.get("aladSettings");
  return { ...DEFAULTS, ...(obj.aladSettings || {}) };
}

async function saveSettings(settings) {
  await chrome.storage.local.set({ aladSettings: { ...DEFAULTS, ...settings } });
}

function videoIdFromUrl(url) {
  try {
    const u = new URL(url);
    if (u.hostname === "youtu.be") return u.pathname.slice(1);
    if (u.hostname.includes("youtube.com")) return u.searchParams.get("v") || "";
  } catch {}
  return "";
}

function cacheKey(url, lang, start, end) {
  const id = videoIdFromUrl(url) || btoa(unescape(encodeURIComponent(url))).slice(0, 40);
  return `aladDub:v1:${id}:${lang}:${Math.round(start)}:${Math.round(end)}`;
}

function extractResponseText(json) {
  const parts = json?.candidates?.[0]?.content?.parts || [];
  return parts.map(p => p?.text || "").join("").trim();
}

function stripCodeFence(text) {
  return String(text || "")
    .replace(/^\s*```(?:json)?\s*/i, "")
    .replace(/\s*```\s*$/i, "")
    .trim();
}

function clamp(n, min, max) {
  return Math.max(min, Math.min(max, n));
}

async function geminiGenerateYoutubeChunk({ url, start, end, targetLanguage }) {
  const key = await getApiKey();
  if (!key) throw new Error("Chưa nhập Gemini API key trong ALAD Dub.");

  const ck = cacheKey(url, targetLanguage, start, end);
  const cached = await chrome.storage.local.get(ck);
  if (cached[ck]?.segments?.length) {
    return { ...cached[ck], fromCache: true };
  }

  const languageName = {
    vi: "Vietnamese",
    en: "English",
    ja: "Japanese",
    ko: "Korean",
    zh: "Simplified Chinese",
    th: "Thai",
    id: "Indonesian",
    fr: "French",
    de: "German",
    es: "Spanish",
    pt: "Portuguese"
  }[targetLanguage] || targetLanguage;

  const prompt = [
    `Process ONLY the clip from ${start.toFixed(1)}s to ${end.toFixed(1)}s of this video.`,
    `Create dubbing cues translated into ${languageName}.`,
    "Transcribe the actually spoken dialogue faithfully. Do not summarize, explain, or omit meaningful speech.",
    "Return ABSOLUTE timestamps in seconds from the beginning of the original video, not timestamps relative to the clip.",
    "Each cue should normally be 1 to 6 seconds and contain a complete natural phrase.",
    "translated_text must sound natural when spoken aloud. source_text is the original speech.",
    "Do not translate music-only or silent portions. Keep names, numbers and technical terms accurate."
  ].join("\n");

  const body = {
    contents: [{
      role: "user",
      parts: [
        {
          file_data: { file_uri: url },
          videoMetadata: {
            startOffset: `${Math.max(0, start).toFixed(2)}s`,
            endOffset: `${Math.max(start + 1, end).toFixed(2)}s`
          }
        },
        { text: prompt }
      ]
    }],
    generationConfig: {
      temperature: 0.15,
      responseMimeType: "application/json",
      responseSchema: {
        type: "OBJECT",
        properties: {
          segments: {
            type: "ARRAY",
            items: {
              type: "OBJECT",
              properties: {
                start: { type: "NUMBER" },
                end: { type: "NUMBER" },
                source_text: { type: "STRING" },
                translated_text: { type: "STRING" },
                speaker: { type: "STRING" }
              },
              required: ["start", "end", "source_text", "translated_text"]
            }
          }
        },
        required: ["segments"]
      }
    }
  };

  const response = await fetch(
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": key
      },
      body: JSON.stringify(body)
    }
  );

  const raw = await response.text();
  if (!response.ok) {
    throw new Error(`Gemini ${response.status}: ${raw.slice(0, 260)}`);
  }

  let api;
  try { api = JSON.parse(raw); }
  catch { throw new Error("Gemini trả dữ liệu không hợp lệ."); }

  let parsed;
  try { parsed = JSON.parse(stripCodeFence(extractResponseText(api))); }
  catch {
    throw new Error("Không đọc được JSON phụ đề từ Gemini.");
  }

  const segments = (parsed.segments || [])
    .map((s, i) => ({
      id: `${Math.round(start)}-${i}`,
      start: Number(s.start),
      end: Number(s.end),
      source_text: String(s.source_text || "").trim(),
      translated_text: String(s.translated_text || "").trim(),
      speaker: String(s.speaker || "").trim()
    }))
    .filter(s =>
      Number.isFinite(s.start) &&
      Number.isFinite(s.end) &&
      s.end > s.start &&
      s.translated_text &&
      s.start >= start - 5 &&
      s.start <= end + 5
    )
    .map(s => ({
      ...s,
      start: clamp(s.start, Math.max(0, start - 2), end + 2),
      end: clamp(s.end, Math.max(s.start + 0.15, start), end + 4)
    }))
    .sort((a, b) => a.start - b.start);

  if (!segments.length) {
    throw new Error("Gemini không tạo được đoạn thoại trong khoảng này.");
  }

  const value = {
    url,
    targetLanguage,
    start,
    end,
    segments,
    createdAt: Date.now()
  };
  await chrome.storage.local.set({ [ck]: value });
  return { ...value, fromCache: false };
}

async function geminiTranslateText(text, targetLanguage) {
  const key = await getApiKey();
  if (!key) throw new Error("Chưa nhập Gemini API key.");

  const prompt = `Translate the following subtitle into ${targetLanguage}. Return only the translated sentence, natural for voice dubbing.\n\n${text}`;
  const response = await fetch(
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": key
      },
      body: JSON.stringify({
        contents: [{ parts: [{ text: prompt }] }],
        generationConfig: { temperature: 0.1 }
      })
    }
  );

  const raw = await response.text();
  if (!response.ok) throw new Error(`Gemini ${response.status}: ${raw.slice(0, 220)}`);
  const json = JSON.parse(raw);
  const out = extractResponseText(json).trim();
  if (!out) throw new Error("Gemini không trả bản dịch.");
  return out;
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    switch (msg?.type) {
      case "ALAD_ENSURE_CONTENT": {
        const result = await ensureContentScript(msg.tabId);
        sendResponse({ ok: true, ...result });
        break;
      }
      case "ALAD_GET_CONFIG": {
        const settings = await loadSettings();
        const keyInfo = await getApiKeyInfo();
        sendResponse({
          ok: true,
          settings,
          hasKey: keyInfo.hasKey,
          keyStorage: keyInfo.storage
        });
        break;
      }
      case "ALAD_SAVE_CONFIG": {
        const remember = !!msg.rememberKey;
        if (typeof msg.apiKey === "string" && msg.apiKey.trim()) {
          await saveApiKey(msg.apiKey.trim(), remember);
        } else {
          await applyKeyPreference(remember);
        }
        await saveSettings(msg.settings || {});
        const keyInfo = await getApiKeyInfo();
        sendResponse({
          ok: true,
          hasKey: keyInfo.hasKey,
          keyStorage: keyInfo.storage
        });
        break;
      }
      case "ALAD_SAVE_KEY": {
        const key = String(msg.apiKey || "").trim();
        if (!key) throw new Error("Chưa nhập API key.");
        await saveApiKey(key, !!msg.rememberKey);
        const keyInfo = await getApiKeyInfo();
        sendResponse({
          ok: true,
          hasKey: keyInfo.hasKey,
          keyStorage: keyInfo.storage
        });
        break;
      }
      case "ALAD_CLEAR_KEY": {
        await clearApiKey();
        sendResponse({ ok: true, hasKey: false, keyStorage: "none" });
        break;
      }
      case "ALAD_GENERATE_YOUTUBE_CHUNK": {
        const data = await geminiGenerateYoutubeChunk(msg.payload);
        sendResponse({ ok: true, data });
        break;
      }
      case "ALAD_TRANSLATE_TEXT": {
        const translated = await geminiTranslateText(msg.text, msg.targetLanguage);
        sendResponse({ ok: true, translated });
        break;
      }
      case "ALAD_OPEN_PANEL": {
        if (sender.tab?.id) {
          await chrome.sidePanel.open({ tabId: sender.tab.id });
        }
        sendResponse({ ok: true });
        break;
      }
      case "ALAD_CLEAR_CACHE": {
        const all = await chrome.storage.local.get(null);
        const keys = Object.keys(all).filter(k => k.startsWith("aladDub:v1:"));
        if (keys.length) await chrome.storage.local.remove(keys);
        sendResponse({ ok: true, removed: keys.length });
        break;
      }
      default:
        sendResponse({ ok: false, error: "Unknown message" });
    }
  })().catch(err => {
    sendResponse({ ok: false, error: err?.message || String(err) });
  });
  return true;
});
