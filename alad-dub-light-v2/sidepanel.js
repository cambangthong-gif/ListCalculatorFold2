const $=id=>document.getElementById(id);let tab=null;
async function active(){tab=(await chrome.tabs.query({active:true,currentWindow:true}))[0]||null;return tab}
function settings(){return{
 targetLanguage:$("lang").value,voiceName:$("voice").value,sourceMode:$("sourceMode").value,
 originalVolume:Number($("orig").value),dubVolume:Number($("dub").value),
 prefetchCount:4,showCaptions:$("caps").checked
}}
function nums(){$("origText").textContent=Math.round($("orig").value*100)+"%";$("dubText").textContent=Math.round($("dub").value*100)+"%"}
async function ensure(){await active();if(!tab?.id)throw new Error("Không có tab hiện tại");
 const r=await chrome.runtime.sendMessage({type:"ALAD_V2_ENSURE",tabId:tab.id});
 if(!r?.ok)throw new Error(r?.error||"Không kết nối được tab")}
async function init(){
 const cfg=await chrome.runtime.sendMessage({type:"ALAD_V2_GET_CONFIG"});
 const s=cfg?.settings||{};
 $("lang").value=s.targetLanguage||"vi";$("voice").value=s.voiceName||"Kore";$("sourceMode").value=s.sourceMode||"auto";
 $("orig").value=s.originalVolume??.18;$("dub").value=s.dubVolume??1;$("caps").checked=s.showCaptions!==false;nums();
 try{await ensure()}catch(e){$("status").textContent="Chưa kết nối";$("detail").textContent=e.message}
 refresh()
}
async function save(){
 const r=await chrome.runtime.sendMessage({type:"ALAD_V2_SAVE_CONFIG",apiKey:$("key").value.trim(),rememberKey:$("remember").checked,settings:settings()});
 if(!r?.ok)throw new Error(r?.error||"Không lưu được")
}
async function start(){try{await save();await ensure();const r=await chrome.tabs.sendMessage(tab.id,{type:"ALAD_V2_START",settings:settings()});
 if(!r?.ok)throw new Error(r?.error||"Không khởi động được");$("dot").classList.add("on");$("status").textContent="Đang chạy";$("detail").textContent=r.mode||""
}catch(e){$("status").textContent="Lỗi";$("detail").textContent=e.message}}
async function stop(){try{await ensure();await chrome.tabs.sendMessage(tab.id,{type:"ALAD_V2_STOP"})}catch{}$("dot").classList.remove("on");$("status").textContent="Đã dừng";$("detail").textContent=""}
async function refresh(){try{await active();if(!tab?.id)return;const r=await chrome.tabs.sendMessage(tab.id,{type:"ALAD_V2_STATE"});$("dot").classList.toggle("on",!!r?.running);if(r?.status)$("status").textContent=r.status}catch{}}
chrome.runtime.onMessage.addListener(m=>{if(m?.type==="ALAD_V2_STATUS"){$("status").textContent=m.text||"";$("detail").textContent=m.detail||""}})
$("start").onclick=start;$("stop").onclick=stop;$("show").onclick=()=>{const x=$("key");x.type=x.type==="password"?"text":"password";$("show").textContent=x.type==="password"?"Hiện":"Ẩn"};
$("clear").onclick=async()=>{const r=await chrome.runtime.sendMessage({type:"ALAD_V2_CLEAR_CACHE"});$("status").textContent="Đã xóa cache";$("detail").textContent=(r?.removed||0)+" mục script"};
$("orig").oninput=nums;$("dub").oninput=nums;init();setInterval(refresh,2500);