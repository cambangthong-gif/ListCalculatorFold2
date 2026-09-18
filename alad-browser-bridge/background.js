let socket = null;
let retryMs = 1000;
let reconnectTimer = null;

function notifyTabs(connected) {
  chrome.tabs.query({ url: ["https://www.youtube.com/*", "https://m.youtube.com/*"] }, tabs => {
    for (const tab of tabs) {
      if (!tab.id) continue;
      chrome.tabs.sendMessage(tab.id, { type: "aladBridgeStatus", connected }).catch(() => {});
    }
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, retryMs);
  retryMs = Math.min(8000, retryMs * 2);
}

function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;

  try {
    socket = new WebSocket("ws://127.0.0.1:37921/alad/");
  } catch {
    scheduleReconnect();
    return;
  }

  socket.onopen = () => {
    retryMs = 1000;
    notifyTabs(true);
    try { socket.send(JSON.stringify({ type: "hello", client: "alad-browser-bridge", version: "1.0.0" })); } catch {}
  };

  socket.onmessage = event => {
    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }

    if (msg.type === "setVolume") {
      chrome.tabs.query({ url: ["https://www.youtube.com/*", "https://m.youtube.com/*"] }, tabs => {
        for (const tab of tabs) {
          if (!tab.id) continue;
          chrome.tabs.sendMessage(tab.id, { type: "aladSetVolume", value: Number(msg.value) }).catch(() => {});
        }
      });
    }

    if (msg.type === "config") {
      chrome.tabs.query({ url: ["https://www.youtube.com/*", "https://m.youtube.com/*"] }, tabs => {
        for (const tab of tabs) {
          if (!tab.id) continue;
          chrome.tabs.sendMessage(tab.id, {
            type: "aladConfig",
            targetLanguage: msg.targetLanguage || "vi",
            originalVolume: Number(msg.originalVolume ?? 1)
          }).catch(() => {});
        }
      });
    }
  };

  socket.onclose = () => {
    notifyTabs(false);
    socket = null;
    scheduleReconnect();
  };

  socket.onerror = () => {
    try { socket.close(); } catch {}
  };
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "aladBridgeMessage") {
    if (socket?.readyState === WebSocket.OPEN) {
      try { socket.send(JSON.stringify(message.payload)); } catch {}
      sendResponse({ ok: true });
    } else {
      connect();
      sendResponse({ ok: false });
    }
    return true;
  }

  if (message?.type === "aladBridgeProbe") {
    sendResponse({ connected: socket?.readyState === WebSocket.OPEN });
    return true;
  }
});

chrome.runtime.onInstalled.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
connect();
