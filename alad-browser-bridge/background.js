let socket = null;
let retryMs = 1000;
let reconnectTimer = null;

function broadcastToTabs(message) {
  chrome.tabs.query({}, tabs => {
    for (const tab of tabs) {
      if (!tab.id) continue;
      chrome.tabs.sendMessage(tab.id, message).catch(() => {});
    }
  });
}

function notifyTabs(connected) {
  broadcastToTabs({ type: "aladBridgeStatus", connected });
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
    try {
      socket.send(JSON.stringify({
        type: "hello",
        client: "alad-universal-browser-bridge",
        version: "1.2.0"
      }));
    } catch {}
  };

  socket.onmessage = event => {
    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }

    if (msg.type === "setVolume") {
      broadcastToTabs({ type: "aladSetVolume", value: Number(msg.value) });
    }

    if (msg.type === "config") {
      broadcastToTabs({
        type: "aladConfig",
        targetLanguage: msg.targetLanguage || "vi",
        originalVolume: Number(msg.originalVolume ?? 1)
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
      const payload = {
        ...message.payload,
        pageUrl: sender.tab?.url || "",
        tabId: sender.tab?.id ?? -1,
        frameId: sender.frameId ?? 0
      };
      try { socket.send(JSON.stringify(payload)); } catch {}
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
