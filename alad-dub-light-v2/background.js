const DEFAULTS = {
  targetLanguage: "vi",
  voiceName: "Kore",
  sourceMode: "auto",
  originalVolume: 0.18,
  dubVolume: 1.0,
  prefetchCount: 4,
  showCaptions: true
};

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});
chrome.runtime.onStartup.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});

async function getApiKey() {
  const s = await chrome.storage.session.get("aladV2ApiKey");
  if (s.aladV2ApiKey) return s.aladV2ApiKey;
  const l = await chrome.storage.local.get("aladV2ApiKey");
  return l.aladV2ApiKey || "";
}
async function saveApiKey(key, remember) {
  await chrome.storage.session.remove("aladV2ApiKey");
  if (remember) await chrome.storage.local.set({ aladV2ApiKey: key });
  else {
    await chrome.storage.local.remove("aladV2ApiKey");
    await chrome.storage.session.set({ aladV2ApiKey: key });
  }
}
async function getSettings() {
  const x = await chrome.storage.local.get("aladV2Settings");
  return { ...DEFAULTS, ...(x.aladV2Settings || {}) };
}
async function saveSettings(s) {
  await chrome.storage.local.set({ aladV2Settings: { ...DEFAULTS, ...s } });
}

function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i=0;i<str.length;i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16);
}
function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i=0;i<bin.length;i++) out[i]=bin.charCodeAt(i);
  return out;
}
function bytesToB64(bytes) {
  let s="";
  const step=0x8000;
  for(let i=0;i<bytes.length;i+=step) {
    s += String.fromCharCode(...bytes.subarray(i, i+step));
  }
  return btoa(s);
}
function extractText(json) {
  return (json?.candidates?.[0]?.content?.parts || [])
    .map(p => p?.text || "").join("").trim();
}
function stripFence(s) {
  return String(s||"").replace(/^\s*```(?:json)?\s*/i,"")
    .replace(/\s*```\s*$/i,"").trim();
}
async function ensureContent(tabId) {
  try {
    await chrome.tabs.sendMessage(tabId,{type:"ALAD_V2_PING"});
    return;
  } catch {}
  const tab=await chrome.tabs.get(tabId);
  if (!/^https?:\/\//i.test(tab.url||"")) throw new Error("Trang này không cho extension chạy.");
  try { await chrome.scripting.insertCSS({target:{tabId},files:["content.css"]}); } catch {}
  await chrome.scripting.executeScript({target:{tabId},files:["content.js"]});
  await new Promise(r=>setTimeout(r,100));
}

async function getYoutubeMeta(tabId) {
  const result = await chrome.scripting.executeScript({
    target:{tabId},
    world:"MAIN",
    func:() => {
      const player=document.getElementById("movie_player");
      const r=player?.getPlayerResponse?.() || window.ytInitialPlayerResponse || null;
      const vd=r?.videoDetails || {};
      const tracks=r?.captions?.playerCaptionsTracklistRenderer?.captionTracks || [];
      const text = n => n?.simpleText || (n?.runs||[]).map(x=>x.text||"").join("") || "";
      return {
        videoId: vd.videoId || new URL(location.href).searchParams.get("v") || "",
        title: vd.title || document.title || "",
        duration: Number(vd.lengthSeconds || 0),
        tracks: tracks.map((t,i)=>({
          index:i,
          baseUrl:t.baseUrl || "",
          languageCode:t.languageCode || "",
          kind:t.kind || "",
          name:text(t.name),
          isTranslatable:t.isTranslatable !== false
        })).filter(t=>t.baseUrl)
      };
    }
  });
  return result?.[0]?.result || {tracks:[]};
}

async function fetchYoutubeCaptions(baseUrl) {
  const sep=baseUrl.includes("?") ? "&" : "?";
  const r=await fetch(baseUrl + sep + "fmt=json3");
  if(!r.ok) throw new Error("Không tải được track phụ đề YouTube.");
  const j=await r.json();
  const cues=[];
  let id=0;
  for(const e of (j.events||[])) {
    if(!e?.segs?.length) continue;
    const text=e.segs.map(s=>s.utf8||"").join("").replace(/\n/g," ").replace(/\s+/g," ").trim();
    if(!text) continue;
    const start=Number(e.tStartMs||0)/1000;
    const dur=Math.max(0.35,Number(e.dDurationMs||0)/1000);
    cues.push({id:id++,start,end:start+dur,source:text});
  }
  return cues;
}

async function geminiJson(body, model="gemini-3.8-flash") {
  const key=await getApiKey();
  if(!key) throw new Error("Chưa nhập Gemini API key.");
  const r=await fetch("https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent",{
    method:"POST",
    headers:{"content-type":"application/json","x-goog-api-key":key},
    body:JSON.stringify(body)
  });
  const raw=await r.text();
  if(!r.ok) throw new Error("Gemini "+r.status+": "+raw.slice(0,220));
  return JSON.parse(raw);
}

async function translateBatch(cues,targetLanguage) {
  if(!cues.length) return [];
  const compact=cues.map(c=>({id:c.id,text:c.source}));
  const body={
    contents:[{role:"user",parts:[{text:
      "Translate these subtitle cues into "+targetLanguage+
      ". Keep meaning, names, numbers and tone. Make each result natural for dubbing, concise enough to fit the original cue. "+
      "Return one translation for every id. Input JSON:\n"+JSON.stringify(compact)
    }]}],
    generationConfig:{
      temperature:0.12,
      thinkingConfig:{thinkingLevel:"low"},
      responseMimeType:"application/json",
      responseSchema:{
        type:"OBJECT",
        properties:{items:{type:"ARRAY",items:{type:"OBJECT",properties:{
          id:{type:"INTEGER"},text:{type:"STRING"}
        },required:["id","text"]}}},
        required:["items"]
      }
    }
  };
  const j=await geminiJson(body);
  let parsed;
  try { parsed=JSON.parse(stripFence(extractText(j))); }
  catch { throw new Error("Không đọc được JSON bản dịch."); }
  const map=new Map((parsed.items||[]).map(x=>[Number(x.id),String(x.text||"").trim()]));
  return cues.map(c=>({...c,translated:map.get(c.id)||c.source}));
}

async function aiCaptionChunk(url,start,end,targetLanguage) {
  const cacheKey="aladV2Ai:"+fnv1a(url+"|"+targetLanguage+"|"+Math.floor(start));
  const got=await chrome.storage.local.get(cacheKey);
  if(got[cacheKey]?.length) return got[cacheKey];

  const body={
    contents:[{role:"user",parts:[
      {file_data:{file_uri:url},videoMetadata:{
        startOffset:Math.max(0,start).toFixed(1)+"s",
        endOffset:Math.max(start+2,end).toFixed(1)+"s"
      }},
      {text:
        "Transcribe the actually spoken dialogue in this clip and translate it into "+targetLanguage+
        ". Return absolute timestamps from the beginning of the original video. Do not summarize. "+
        "Create natural dubbing cues, normally 1-6 seconds. Ignore music-only/silence."
      }
    ]}],
    generationConfig:{
      temperature:0.12,
      thinkingConfig:{thinkingLevel:"low"},
      responseMimeType:"application/json",
      responseSchema:{
        type:"OBJECT",
        properties:{segments:{type:"ARRAY",items:{type:"OBJECT",properties:{
          start:{type:"NUMBER"},end:{type:"NUMBER"},source:{type:"STRING"},translated:{type:"STRING"}
        },required:["start","end","source","translated"]}}},
        required:["segments"]
      }
    }
  };
  const j=await geminiJson(body);
  let parsed;
  try { parsed=JSON.parse(stripFence(extractText(j))); }
  catch { throw new Error("Không đọc được phụ đề AI."); }
  const seg=(parsed.segments||[]).map((x,i)=>({
    id:Math.floor(start*1000)+i,
    start:Number(x.start),end:Number(x.end),
    source:String(x.source||"").trim(),
    translated:String(x.translated||"").trim()
  })).filter(x=>Number.isFinite(x.start)&&Number.isFinite(x.end)&&x.end>x.start&&x.translated)
     .sort((a,b)=>a.start-b.start);
  await chrome.storage.local.set({[cacheKey]:seg});
  return seg;
}

async function tts(text,voice) {
  const hash=fnv1a(voice+"|"+text);
  const cache=await caches.open("alad-dub-v2-tts");
  const req=new Request("https://alad.local/tts/"+hash);
  const hit=await cache.match(req);
  if(hit) {
    const bytes=new Uint8Array(await hit.arrayBuffer());
    return {pcm:bytesToB64(bytes),sampleRate:24000,fromCache:true};
  }

  const body={
    contents:[{parts:[{text:"Read naturally for video dubbing. Speak only this text, do not add anything:\n"+text}]}],
    generationConfig:{
      responseModalities:["AUDIO"],
      speechConfig:{voiceConfig:{prebuiltVoiceConfig:{voiceName:voice||"Kore"}}}
    }
  };
  const j=await geminiJson(body,"gemini-3.1-flash-tts-preview");
  const part=(j?.candidates?.[0]?.content?.parts||[]).find(p=>p?.inlineData?.data || p?.inline_data?.data);
  const b64=part?.inlineData?.data || part?.inline_data?.data || "";
  if(!b64) throw new Error("Gemini TTS không trả audio.");
  const bytes=b64ToBytes(b64);
  await cache.put(req,new Response(bytes,{headers:{"content-type":"application/octet-stream"}}));
  return {pcm:b64,sampleRate:24000,fromCache:false};
}

async function clearCaches() {
  const all=await chrome.storage.local.get(null);
  const keys=Object.keys(all).filter(k=>k.startsWith("aladV2"));
  if(keys.length) await chrome.storage.local.remove(keys);
  await caches.delete("alad-dub-v2-tts");
  return keys.length;
}

chrome.runtime.onMessage.addListener((msg,sender,sendResponse)=>{
  (async()=>{
    switch(msg?.type) {
      case "ALAD_V2_ENSURE":
        await ensureContent(msg.tabId);
        sendResponse({ok:true});
        break;
      case "ALAD_V2_GET_CONFIG": {
        const settings=await getSettings();
        sendResponse({ok:true,settings,hasKey:!!(await getApiKey())});
        break;
      }
      case "ALAD_V2_SAVE_CONFIG":
        if(String(msg.apiKey||"").trim()) await saveApiKey(String(msg.apiKey).trim(),!!msg.rememberKey);
        await saveSettings(msg.settings||{});
        sendResponse({ok:true});
        break;
      case "ALAD_V2_YT_META": {
        const tabId=sender.tab?.id || msg.tabId;
        sendResponse({ok:true,data:await getYoutubeMeta(tabId)});
        break;
      }
      case "ALAD_V2_YT_CAPTIONS":
        sendResponse({ok:true,cues:await fetchYoutubeCaptions(msg.baseUrl)});
        break;
      case "ALAD_V2_TRANSLATE":
        sendResponse({ok:true,cues:await translateBatch(msg.cues||[],msg.targetLanguage||"vi")});
        break;
      case "ALAD_V2_AI_CAPTIONS":
        sendResponse({ok:true,cues:await aiCaptionChunk(msg.url,msg.start,msg.end,msg.targetLanguage||"vi")});
        break;
      case "ALAD_V2_TTS":
        sendResponse({ok:true,audio:await tts(msg.text,msg.voice||"Kore")});
        break;
      case "ALAD_V2_CLEAR_CACHE":
        sendResponse({ok:true,removed:await clearCaches()});
        break;
      default:
        sendResponse({ok:false,error:"Unknown message"});
    }
  })().catch(e=>sendResponse({ok:false,error:e?.message||String(e)}));
  return true;
});
