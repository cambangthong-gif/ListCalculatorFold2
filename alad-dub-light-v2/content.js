(() => {
  if (window.__ALAD_DUB_LITE_V2__) return;
  window.__ALAD_DUB_LITE_V2__=true;

  const S={
    running:false,settings:null,video:null,mode:"",
    cues:[],index:0,translatedUntil:-1,
    translateBusy:false,aiBusy:false,
    audioCtx:null,currentSource:null,
    decoded:new Map(),loadingTts:new Map(),
    originalVolume:null,overlay:null,button:null,
    lastVideoUrl:"",timer:null,status:"Sẵn sàng"
  };

  const send=(m)=>chrome.runtime.sendMessage(m);
  function status(text,detail="") {
    S.status=text;
    send({type:"ALAD_V2_STATUS",text,detail}).catch(()=>{});
  }
  function isYouTube() {
    try {
      const u=new URL(location.href);
      return u.hostname.includes("youtube.com")&&u.pathname==="/watch"&&u.searchParams.has("v");
    } catch{return false;}
  }
  function findVideo() {
    const vs=[...document.querySelectorAll("video")];
    if(!vs.length)return null;
    return vs.sort((a,b)=>{
      const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
      return (br.width*br.height+(!b.paused?1e9:0))-(ar.width*ar.height+(!a.paused?1e9:0));
    })[0];
  }
  function ensureCtx() {
    if(!S.audioCtx) S.audioCtx=new AudioContext({latencyHint:"interactive"});
    if(S.audioCtx.state==="suspended") S.audioCtx.resume().catch(()=>{});
    return S.audioCtx;
  }
  function pcmToBuffer(b64,rate) {
    const bin=atob(b64);
    const n=Math.floor(bin.length/2);
    const ctx=ensureCtx();
    const buf=ctx.createBuffer(1,n,rate||24000);
    const ch=buf.getChannelData(0);
    for(let i=0,j=0;i<n;i++,j+=2) {
      let v=bin.charCodeAt(j)|(bin.charCodeAt(j+1)<<8);
      if(v&0x8000)v-=0x10000;
      ch[i]=v/32768;
    }
    return buf;
  }
  function ensureOverlay() {
    if(S.overlay&&document.contains(S.overlay))return S.overlay;
    const v=S.video||findVideo();
    const host=v?.parentElement;
    if(!host)return null;
    const x=document.createElement("div");
    x.id="alad-v2-overlay";
    x.innerHTML='<div class="alad-v2-tr"></div><div class="alad-v2-src"></div>';
    host.appendChild(x);
    S.overlay=x;
    return x;
  }
  function showCue(cue) {
    if(!S.settings?.showCaptions)return;
    const x=ensureOverlay(); if(!x)return;
    x.querySelector(".alad-v2-tr").textContent=cue.translated||"";
    x.querySelector(".alad-v2-src").textContent=cue.source||"";
    x.classList.add("show");
  }
  function hideCue(){S.overlay?.classList.remove("show");}
  function stopAudio() {
    try{S.currentSource?.stop();}catch{}
    S.currentSource=null;
    if(S.video&&S.originalVolume!=null) {
      try{S.video.volume=S.originalVolume;}catch{}
    }
    hideCue();
  }
  function cueIndexAt(t) {
    let lo=0,hi=S.cues.length;
    while(lo<hi) {
      const m=(lo+hi)>>1;
      if(S.cues[m].end<t-0.12)lo=m+1;else hi=m;
    }
    return lo;
  }
  async function loadTts(cue) {
    if(!cue?.translated)return null;
    if(S.decoded.has(cue.id))return S.decoded.get(cue.id);
    if(S.loadingTts.has(cue.id))return S.loadingTts.get(cue.id);
    const p=(async()=>{
      const r=await send({type:"ALAD_V2_TTS",text:cue.translated,voice:S.settings.voiceName});
      if(!r?.ok)throw new Error(r?.error||"TTS lỗi");
      const b=pcmToBuffer(r.audio.pcm,r.audio.sampleRate);
      S.decoded.set(cue.id,b);
      if(S.decoded.size>14) {
        const first=S.decoded.keys().next().value;
        S.decoded.delete(first);
      }
      return b;
    })().finally(()=>S.loadingTts.delete(cue.id));
    S.loadingTts.set(cue.id,p);
    return p;
  }
  function prefetch() {
    if(!S.running)return;
    const n=Math.max(2,Number(S.settings.prefetchCount||4));
    for(let i=S.index;i<Math.min(S.cues.length,S.index+n);i++) {
      const c=S.cues[i];
      if(c?.translated&&!S.decoded.has(c.id)&&!S.loadingTts.has(c.id)) {
        loadTts(c).catch(()=>{});
      }
    }
  }
  async function playCue(cue) {
    if(!S.running||!S.video||S.video.paused||!cue?.translated)return;
    stopAudio();
    let buf;
    try{buf=await loadTts(cue);}catch(e){status("TTS lỗi",e.message);return;}
    if(!S.running||S.video.paused)return;

    const ctx=ensureCtx(),src=ctx.createBufferSource(),gain=ctx.createGain();
    src.buffer=buf;
    const mediaDur=Math.max(0.6,cue.end-cue.start);
    const rate=Math.max(0.72,Math.min(1.85,(buf.duration*(S.video.playbackRate||1))/mediaDur));
    src.playbackRate.value=rate;
    gain.gain.value=Math.max(0,Math.min(1,Number(S.settings.dubVolume||1)));
    src.connect(gain).connect(ctx.destination);
    S.currentSource=src;

    S.originalVolume ??= S.video.volume;
    try{S.video.volume=Math.max(0,Math.min(1,Number(S.settings.originalVolume??0.18)));}catch{}
    showCue(cue);
    status("Đang lồng tiếng",cue.translated.slice(0,80));
    src.onended=()=>{
      if(S.currentSource===src)S.currentSource=null;
      if(S.video&&S.originalVolume!=null) {
        try{S.video.volume=S.originalVolume;}catch{}
      }
      hideCue();
    };
    src.start();
  }
  function schedule() {
    if(!S.running||!S.video)return;
    if(S.timer){clearTimeout(S.timer);S.timer=null;}
    if(S.video.paused)return;

    S.index=cueIndexAt(S.video.currentTime);
    prefetch();
    maybeTranslateAhead();
    maybeAiAhead();

    const cue=S.cues[S.index];
    if(!cue)return;
    const now=S.video.currentTime;
    if(cue.end<now-0.15){S.index++;schedule();return;}
    if(cue.start<=now+0.16) {
      S.index++;
      playCue(cue).finally(()=>{S.timer=setTimeout(schedule,90);});
      return;
    }
    const delay=Math.max(40,Math.min(1500,((cue.start-now)/(S.video.playbackRate||1))*1000-110));
    S.timer=setTimeout(schedule,delay);
  }
  async function translateWindow(fromIndex) {
    if(S.translateBusy||S.mode!=="captions")return;
    const batch=S.cues.slice(fromIndex,Math.min(S.cues.length,fromIndex+34))
      .filter(c=>!c.translated);
    if(!batch.length)return;
    S.translateBusy=true;
    try{
      status("Đang dịch phụ đề",batch[0].start.toFixed(0)+"s → "+batch[batch.length-1].end.toFixed(0)+"s");
      const r=await send({type:"ALAD_V2_TRANSLATE",cues:batch,targetLanguage:S.settings.targetLanguage});
      if(!r?.ok)throw new Error(r?.error||"Dịch lỗi");
      const map=new Map((r.cues||[]).map(c=>[c.id,c.translated]));
      for(const c of S.cues)if(map.has(c.id))c.translated=map.get(c.id);
      status("Đã dịch xong",batch.length+" câu");
      prefetch();schedule();
    }catch(e){status("Lỗi dịch",e.message);}
    finally{S.translateBusy=false;}
  }
  function maybeTranslateAhead() {
    if(S.mode!=="captions")return;
    const idx=cueIndexAt(S.video?.currentTime||0);
    let missing=-1;
    for(let i=idx;i<Math.min(S.cues.length,idx+45);i++) {
      if(!S.cues[i].translated){missing=i;break;}
    }
    if(missing>=0)translateWindow(missing);
  }
  async function loadAiChunk(start) {
    if(S.aiBusy)return;
    S.aiBusy=true;
    const end=start+180;
    try{
      status("AI tạo phụ đề",Math.round(start)+"–"+Math.round(end)+"s");
      const r=await send({type:"ALAD_V2_AI_CAPTIONS",url:location.href,start,end,targetLanguage:S.settings.targetLanguage});
      if(!r?.ok)throw new Error(r?.error||"AI phụ đề lỗi");
      const old=new Set(S.cues.map(c=>c.id));
      for(const c of (r.cues||[]))if(!old.has(c.id))S.cues.push(c);
      S.cues.sort((a,b)=>a.start-b.start);
      status("AI phụ đề xong",(r.cues||[]).length+" đoạn");
      S.index=cueIndexAt(S.video?.currentTime||0);
      prefetch();schedule();
    }catch(e){status("AI phụ đề lỗi",e.message);}
    finally{S.aiBusy=false;}
  }
  function maybeAiAhead() {
    if(S.mode!=="ai")return;
    const t=S.video?.currentTime||0;
    const last=S.cues.length?S.cues[S.cues.length-1].end:0;
    if(last<t+70)loadAiChunk(Math.floor(t/180)*180);
  }
  async function start(settings) {
    stop(false);
    S.settings=settings;
    S.video=findVideo();
    if(!S.video){status("Không tìm thấy video");return;}
    S.running=true;
    S.originalVolume=S.video.volume;
    S.lastVideoUrl=location.href;
    attachEvents();

    if(isYouTube()) {
      status("Đang đọc phụ đề YouTube...");
      const meta=await send({type:"ALAD_V2_YT_META"});
      if(!meta?.ok)throw new Error(meta?.error||"Không đọc được player.");
      const tracks=meta.data?.tracks||[];
      const forceAi=settings.sourceMode==="ai";
      if(tracks.length&&!forceAi) {
        let track=tracks.find(t=>t.languageCode===settings.targetLanguage)||
                  tracks.find(t=>t.kind!=="asr")||tracks[0];
        const r=await send({type:"ALAD_V2_YT_CAPTIONS",baseUrl:track.baseUrl});
        if(!r?.ok)throw new Error(r?.error||"Không tải được phụ đề.");
        S.cues=(r.cues||[]).map(c=>({...c,translated:""}));
        S.mode="captions";
        S.index=cueIndexAt(S.video.currentTime);
        const wasPlaying=!S.video.paused;
        if(wasPlaying)S.video.pause();
        await translateWindow(S.index);
        prefetch();
        // Wait only for first TTS cue to avoid long startup.
        const first=S.cues[S.index];
        if(first?.translated)try{await loadTts(first);}catch{}
        if(wasPlaying)await S.video.play().catch(()=>{});
        status("Sẵn sàng · phụ đề trước",track.name||track.languageCode);
        schedule();
      } else if(settings.sourceMode==="captions") {
        status("Video không có phụ đề","Đổi nguồn sang Tự động hoặc Ép AI.");
      } else {
        S.mode="ai";S.cues=[];S.index=0;
        const wasPlaying=!S.video.paused;
        if(wasPlaying)S.video.pause();
        await loadAiChunk(Math.floor((S.video.currentTime||0)/180)*180);
        const first=S.cues[cueIndexAt(S.video.currentTime)];
        if(first)try{await loadTts(first);}catch{}
        if(wasPlaying)await S.video.play().catch(()=>{});
        schedule();
      }
    } else {
      startGenericTextTrack();
    }
    updateButton();
  }
  function startGenericTextTrack() {
    const tracks=[...(S.video?.textTracks||[])].filter(t=>t.kind==="captions"||t.kind==="subtitles");
    if(!tracks.length) {status("Trang này không có TextTrack","v2 Lite không quét DOM để tránh lag.");return;}
    S.mode="generic";
    const tr=tracks[0];try{tr.mode="hidden";}catch{}
    tr.addEventListener("cuechange",async()=>{
      if(!S.running||!tr.activeCues?.length)return;
      const text=[...tr.activeCues].map(c=>c.text||"").join(" ").trim();
      if(!text)return;
      const id=Date.now();
      const cue={id,start:S.video.currentTime,end:S.video.currentTime+3,source:text,translated:""};
      try{
        const r=await send({type:"ALAD_V2_TRANSLATE",cues:[cue],targetLanguage:S.settings.targetLanguage});
        cue.translated=r?.cues?.[0]?.translated||"";
        if(cue.translated)playCue(cue);
      }catch{}
    });
    status("Universal TextTrack","Đang chờ cue phụ đề");
  }
  function attachEvents() {
    const v=S.video;if(!v||v.__aladV2)return;v.__aladV2=true;
    v.addEventListener("play",()=>{if(S.running){ensureCtx();schedule();}});
    v.addEventListener("pause",()=>{if(S.running){stopAudio();if(S.timer)clearTimeout(S.timer);}});
    v.addEventListener("seeked",()=>{if(S.running){stopAudio();S.index=cueIndexAt(v.currentTime);schedule();}});
    v.addEventListener("ratechange",()=>{if(S.running){stopAudio();schedule();}});
    v.addEventListener("timeupdate",()=>{if(S.running&&!S.timer)schedule();});
  }
  function stop(notify=true) {
    S.running=false;
    if(S.timer)clearTimeout(S.timer);S.timer=null;
    stopAudio();
    S.cues=[];S.index=0;S.mode="";S.translateBusy=false;S.aiBusy=false;
    if(notify)status("Đã dừng");
    updateButton();
  }
  function ensureButton() {
    if(!isYouTube())return;
    if(document.getElementById("alad-v2-btn")){S.button=document.getElementById("alad-v2-btn");updateButton();return;}
    const host=document.querySelector(".ytp-right-controls");if(!host)return;
    const b=document.createElement("button");
    b.id="alad-v2-btn";b.className="ytp-button alad-v2-btn";b.textContent="ALAD";b.title="ALAD Dub Lite";
    b.onclick=async e=>{
      e.stopPropagation();
      if(S.running){stop();return;}
      const cfg=await send({type:"ALAD_V2_GET_CONFIG"});
      if(!cfg?.hasKey){status("Mở panel ALAD để nhập API key");return;}
      start(cfg.settings).catch(er=>status("Lỗi",er.message));
    };
    host.prepend(b);S.button=b;updateButton();
  }
  function updateButton(){if(S.button){S.button.textContent=S.running?"DUB":"ALAD";S.button.classList.toggle("active",S.running);}}
  function resetForNavigation() {
    if(location.href===S.lastVideoUrl)return;
    if(S.running)stop(false);
    S.video=findVideo();S.lastVideoUrl=location.href;
    setTimeout(ensureButton,300);
  }

  chrome.runtime.onMessage.addListener((m,s,reply)=>{
    (async()=>{
      if(m?.type==="ALAD_V2_PING"){reply({ok:true});return;}
      if(m?.type==="ALAD_V2_START"){await start(m.settings);reply({ok:true,mode:S.mode});return;}
      if(m?.type==="ALAD_V2_STOP"){stop();reply({ok:true});return;}
      if(m?.type==="ALAD_V2_STATE"){reply({ok:true,running:S.running,mode:S.mode,status:S.status,time:S.video?.currentTime||0});return;}
      reply({ok:false});
    })().catch(e=>reply({ok:false,error:e?.message||String(e)}));
    return true;
  });

  window.addEventListener("yt-navigate-finish",resetForNavigation);
  setInterval(ensureButton,1800);
  S.video=findVideo();S.lastVideoUrl=location.href;ensureButton();
})();