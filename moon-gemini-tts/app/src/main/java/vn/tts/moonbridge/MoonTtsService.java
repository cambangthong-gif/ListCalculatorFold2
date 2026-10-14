package vn.tts.moonbridge;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MoonTtsService extends TextToSpeechService {
 private static final int RATE=24000,CHUNK=1400;
 private static final List<String> LANGS=Arrays.asList("vi","en","ja","ko","zh","fr","de","es","pt","it","ru","th","id","hi","ar","nl","tr","pl");
 private volatile boolean stopped=false;
 private volatile HttpURLConnection active;
 private volatile long streamRetryAtMs=0;
 private volatile String lastStreamProblem="";

 @Override protected String[] onGetLanguage(){return new String[]{"vie","VNM",""};}
 private static String lang(String l){
  if(l==null)return "";
  switch(l.toLowerCase(Locale.ROOT)){
   case "vie":return "vi";case "eng":return "en";case "jpn":return "ja";
   case "kor":return "ko";case "chi":case "zho":return "zh";
   case "fra":case "fre":return "fr";case "deu":case "ger":return "de";
   case "spa":return "es";case "por":return "pt";case "ita":return "it";
   case "rus":return "ru";case "tha":return "th";case "ind":return "id";
   default:return l.toLowerCase(Locale.ROOT);
  }
 }
 @Override protected int onIsLanguageAvailable(String l,String c,String v){
  return LANGS.contains(lang(l))?TextToSpeech.LANG_AVAILABLE:TextToSpeech.LANG_NOT_SUPPORTED;
 }
 @Override protected int onLoadLanguage(String l,String c,String v){return onIsLanguageAvailable(l,c,v);}
 private static String name(String language){return "moon-gemini-"+lang(language);}
 @Override public String onGetDefaultVoiceNameFor(String l,String c,String v){
  return onIsLanguageAvailable(l,c,v)>=TextToSpeech.LANG_AVAILABLE?name(l):null;
 }
 @Override public List<Voice> onGetVoices(){
  List<Voice> out=new ArrayList<>();
  for(String language:LANGS)
   out.add(new Voice(name(language),new Locale(language),Voice.QUALITY_HIGH,Voice.LATENCY_NORMAL,true,
     new HashSet<>(Arrays.asList(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS))));
  return out;
 }
 @Override public int onIsValidVoiceName(String n){
  for(String l:LANGS)if(name(l).equals(n))return TextToSpeech.SUCCESS;
  return TextToSpeech.ERROR;
 }
 @Override public int onLoadVoice(String name){return onIsValidVoiceName(name);}
 @Override protected void onStop(){
  stopped=true;
  HttpURLConnection c=active;if(c!=null)c.disconnect();
 }
 @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback callback){
  stopped=false;
  String key=SettingsStore.getKey(this);
  if(key.isEmpty()){
   SettingsStore.setError(this,"Chưa có Gemini API key. Mở app để nhập.");
   callback.error();return;
  }
  CharSequence chars=request.getCharSequenceText();
  String input=chars==null?"":chars.toString().trim();
  if(input.isEmpty()){
   callback.start(RATE,AudioFormat.ENCODING_PCM_16BIT,1);
   callback.done();return;
  }
  String model=SettingsStore.model(this),voice=SettingsStore.voice(this),style=SettingsStore.style(this);
  if(!Arrays.asList(SettingsStore.MODELS).contains(model))model=SettingsStore.MODELS[0];
  if(!Arrays.asList(SettingsStore.VOICES).contains(voice))voice=SettingsStore.VOICES[0];

  long begin=SystemClock.elapsedRealtime();
  AudioWriter writer=new AudioWriter(callback,begin);
  boolean preferenceFast=SettingsStore.mode(this)==0;
  boolean useStream=preferenceFast&&SystemClock.elapsedRealtime()>=streamRetryAtMs;
  String mode=useStream?"streaming":(preferenceFast?"ổn định (streaming tạm nghỉ)":"ổn định (đã chọn)");
  try{
   for(String part:splitText(input)){
    if(stopped)return;
    if(useStream){
     long before=writer.received();
     try{
      streamAudio(key,model,voice,style,request.getSpeechRate(),part,writer);
      streamRetryAtMs=0;
      lastStreamProblem="";
      SettingsStore.setStreamInfo(this,"Streaming đã hoạt động thành công");
     }catch(Exception ex){
      if(stopped)return;
      if(writer.received()!=before)throw ex;
      String reason=ex.getMessage()==null?"":ex.getMessage();
      if(reason.contains("429")||reason.contains("401")||reason.contains("403"))
       throw ex;
      // V1.3 disabled streaming permanently for any transient error.
      // Retry automatically after a bounded cooldown; preserve the reason for diagnostics.
      lastStreamProblem=reason.length()>170?reason.substring(0,170):reason;
      boolean unsupported=reason.contains("(400)")||reason.contains("HTTP 404")||reason.contains("HTTP 422");
      streamRetryAtMs=SystemClock.elapsedRealtime()+(unsupported?600000L:45000L);
      SettingsStore.setStreamInfo(this,"Không dùng streaming vì: "+lastStreamProblem+
          (unsupported?" (thử lại sau 10 phút)":" (thử lại sau 45 giây)"));
      useStream=false;
      mode="dự phòng ổn định";
      byte[] pcm=unaryAudio(key,model,voice,style,request.getSpeechRate(),part);
      writer.append(pcm);
     }
    }else{
     byte[] pcm=unaryAudio(key,model,voice,style,request.getSpeechRate(),part);
     writer.append(pcm);
    }
   }
   if(stopped)return;
   if(!writer.hasStarted())throw new Exception("Gemini không trả âm thanh.");
   SettingsStore.setError(this,"");
   SettingsStore.setMetric(this,mode+": âm đầu "+(writer.firstAudioMs())+
       " ms; tổng tổng hợp "+(SystemClock.elapsedRealtime()-begin)+" ms"+
       (preferenceFast&&!lastStreamProblem.isEmpty()?" | "+lastStreamProblem:""));
   callback.done();
  }catch(Exception ex){
   if(stopped)return;
   String msg=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
   SettingsStore.setError(this,msg.substring(0,Math.min(300,msg.length())));
   SettingsStore.setMetric(this,"Lỗi sau "+(SystemClock.elapsedRealtime()-begin)+" ms: "+
       msg.substring(0,Math.min(80,msg.length())));
   callback.error();
  }finally{
   HttpURLConnection conn=active;
   if(conn!=null)conn.disconnect();
   active=null;
  }
 }
 // Streaming sends PCM to Android immediately as soon as the first audio delta arrives.
 // Do not buffer the full utterance in memory, which caused the high initial latency in v1.1.
 private final class AudioWriter {
  private final SynthesisCallback cb;
  private final long began;
  private long first=-1;
  private long byteCount=0;
  private boolean started=false;
  private byte[] pending=new byte[0];
  AudioWriter(SynthesisCallback callback,long start){cb=callback;began=start;}
  boolean hasStarted(){return started;}
  long received(){return byteCount;}
  long firstAudioMs(){return first<0?-1:first-began;}
  void append(byte[] bytes)throws Exception{
   if(stopped)return;
   if(bytes.length==0)return;
   if(!started){
    if(cb.start(RATE,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS)
     throw new Exception("Android từ chối luồng TTS.");
    started=true;
    first=SystemClock.elapsedRealtime();
   }
   byteCount+=bytes.length;
   if(pending.length>0){
    byte[] merged=new byte[pending.length+bytes.length];
    System.arraycopy(pending,0,merged,0,pending.length);
    System.arraycopy(bytes,0,merged,pending.length,bytes.length);
    bytes=merged;pending=new byte[0];
   }
   int even=bytes.length&~1;
   if(even!=bytes.length)pending=new byte[]{bytes[bytes.length-1]};
   int block=Math.max(2,Math.min(8192,cb.getMaxBufferSize()))&~1;
   for(int off=0;off<even&&!stopped;off+=block){
    int size=Math.min(block,even-off);
    if(cb.audioAvailable(bytes,off,size)!=TextToSpeech.SUCCESS)
     throw new Exception("Android không nhận được đoạn âm thanh.");
   }
  }
 }
 private void streamAudio(String key,String model,String voice,String style,int speed,
                          String text,AudioWriter writer)throws Exception{
  JSONObject annotation=new JSONObject().put("type","speech_metadata")
   .put("style",deliveryStyle(style,speed));
  JSONObject textPart=new JSONObject().put("type","text").put("text",text)
   .put("annotations",new JSONArray().put(annotation));
  JSONObject payload=new JSONObject()
   .put("model",model)
   .put("input",new JSONArray().put(new JSONObject().put("type","user_input")
     .put("content",new JSONArray().put(textPart))))
   .put("response_format",new JSONObject().put("type","audio")
     .put("mime_type","audio/l16").put("sample_rate",RATE))
   .put("generation_config",new JSONObject()
     .put("speech_config",new JSONArray().put(new JSONObject().put("voice",voice))))
   .put("stream",true);
  HttpURLConnection c=null;
  try{
   c=(HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
   active=c;
   c.setRequestMethod("POST");
   c.setConnectTimeout(12000);c.setReadTimeout(80000);
   c.setDoOutput(true);
   c.setRequestProperty("Accept","text/event-stream");
   c.setRequestProperty("Content-Type","application/json; charset=utf-8");
   c.setRequestProperty("x-goog-api-key",key);
   try(OutputStream out=c.getOutputStream()){
    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
   }
   int status=c.getResponseCode();
   if(status!=200){
    String raw=read(c.getErrorStream(),3000),desc;
    if(status==400)desc="Gemini không chấp nhận yêu cầu streaming (400). ";
    else if(status==401||status==403)desc="API key không hợp lệ hoặc không có quyền ("+status+"). ";
    else if(status==429)desc="Vượt hạn mức API miễn phí (429). ";
    else desc="Gemini API lỗi HTTP "+status+". ";
    throw new Exception(desc+raw.substring(0,Math.min(140,raw.length())));
   }
   boolean gotAudio=false,completed=false;
   StringBuilder eventData=new StringBuilder();
   try(BufferedReader reader=new BufferedReader(
       new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8),8192)){
    String line;
    while(!stopped&&(line=reader.readLine())!=null){
     if(line.startsWith("data:")){
      if(eventData.length()>0)eventData.append('\n');
      eventData.append(line.substring(5).trim());
     }else if(line.isEmpty()){
      if(eventData.length()>0){
       String raw=eventData.toString();
       eventData.setLength(0);
       if("[DONE]".equals(raw)){completed=true;break;}
       JSONObject event;
       try{event=new JSONObject(raw);}catch(Exception ignored){continue;}
       String type=event.optString("event_type","");
       if("step.delta".equals(type)){
        JSONObject delta=event.optJSONObject("delta");
        if(delta!=null&&"audio".equals(delta.optString("type"))){
         String audio=delta.optString("data","");
         if(!audio.isEmpty()){
          byte[] bytes=Base64.decode(audio,Base64.DEFAULT);
          if(bytes.length>0){writer.append(bytes);gotAudio=true;}
         }
        }
       }else if("interaction.completed".equals(type)){
        JSONObject meta=event.optJSONObject("interaction");
        String result=meta==null?"completed":meta.optString("status","completed");
        if("failed".equals(result))throw new Exception("Gemini không tạo được âm thanh.");
        completed=true;
       }else if("interaction.failed".equals(type)||
         "interaction.error".equals(type)||
         "error".equals(type)){
        throw new Exception("Gemini báo lỗi khi đang phát streaming.");
       }
      }
     }
    }
   }
   if(stopped)return;
   if(!gotAudio)throw new Exception("Luồng streaming không có dữ liệu âm thanh.");
   if(!completed)throw new Exception("Gemini ngắt kết nối trước khi hoàn tất âm thanh.");
  }finally{
   active=null;
   if(c!=null)c.disconnect();
  }
 }
 private byte[] unaryAudio(String key,String model,String voice,String style,int speed,String text)throws Exception{
  JSONObject annotation=new JSONObject().put("type","speech_metadata")
   .put("style",deliveryStyle(style,speed));
  JSONObject textPart=new JSONObject().put("type","text").put("text",text)
   .put("annotations",new JSONArray().put(annotation));
  JSONObject payload=new JSONObject()
   .put("model",model)
   .put("input",new JSONArray().put(new JSONObject().put("type","user_input")
     .put("content",new JSONArray().put(textPart))))
   .put("response_format",new JSONObject().put("type","audio")
     .put("mime_type","audio/l16").put("sample_rate",RATE))
   .put("generation_config",new JSONObject()
     .put("speech_config",new JSONArray().put(new JSONObject().put("voice",voice))));
  HttpURLConnection c=null;
  try{
   c=(HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
   active=c;c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(60000);
   c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
   c.setRequestProperty("x-goog-api-key",key);
   try(OutputStream out=c.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
   int status=c.getResponseCode();
   if(status!=200){
    String raw=read(c.getErrorStream(),3000),desc;
    if(status==400)desc="Yêu cầu hoặc giọng không được API hỗ trợ (400). ";
    else if(status==401||status==403)desc="API key không hợp lệ hoặc không có quyền ("+status+"). ";
    else if(status==429)desc="Vượt hạn mức miễn phí (429). ";
    else desc="Gemini API HTTP "+status+". ";
    throw new Exception(desc+raw.substring(0,Math.min(140,raw.length())));
   }
   JSONObject root=new JSONObject(read(c.getInputStream(),16000000));
   String b64=null;
   JSONObject direct=root.optJSONObject("output_audio");
   if(direct!=null)b64=direct.optString("data",null);
   if(b64==null||b64.isEmpty()){
    JSONArray steps=root.optJSONArray("steps");
    if(steps!=null)for(int s=0;s<steps.length();s++){
     JSONObject step=steps.optJSONObject(s);
     if(step==null||!"model_output".equals(step.optString("type")))continue;
     JSONArray content=step.optJSONArray("content");
     if(content==null)continue;
     for(int j=0;j<content.length();j++){
      JSONObject item=content.optJSONObject(j);
      if(item!=null&&"audio".equals(item.optString("type")))b64=item.optString("data",null);
     }
    }
   }
   if(b64==null||b64.isEmpty())throw new Exception("Không tìm thấy âm thanh trong phản hồi API.");
   return decodeAudio(Base64.decode(b64,Base64.DEFAULT));
  }finally{
   active=null;if(c!=null)c.disconnect();
  }
 }
 private static byte[] decodeAudio(byte[] bytes){
  if(bytes.length<44||bytes[0]!='R'||bytes[1]!='I'||bytes[2]!='F'||bytes[3]!='F')return bytes;
  for(int i=12;i+8<bytes.length;){
   int n=(bytes[i+4]&255)|((bytes[i+5]&255)<<8)|((bytes[i+6]&255)<<16)|((bytes[i+7]&255)<<24);
   if(bytes[i]=='d'&&bytes[i+1]=='a'&&bytes[i+2]=='t'&&bytes[i+3]=='a'){
    int at=i+8;if(n<0||at+(long)n>bytes.length)return bytes;
    return Arrays.copyOfRange(bytes,at,at+n);
   }
   if(n<0)break;long next=i+8L+n+(n&1);if(next>bytes.length)break;i=(int)next;
  }
  return bytes;
 }
 private static String deliveryStyle(String style,int speed){
  String s;
  if("Tự nhiên".equals(style))s="Clear, natural, expressive conversational narration.";
  else if("Kể chuyện".equals(style))s="Warm storytelling with engaging characterful intonation.";
  else if("Điềm tĩnh".equals(style))s="Calm and relaxed, pleasant audiobook narration.";
  else s="Cheerful, curious, playful and natural narration.";
  if(speed>115)s+=" Brisk pace.";else if(speed<85)s+=" Gentle slower pace.";
  return s;
 }
 private static List<String> splitText(String text){
  List<String> chunks=new ArrayList<>();text=text.trim();
  while(!text.isEmpty()){
   if(text.length()<=CHUNK){chunks.add(text);break;}
   int last=Math.min(CHUNK,text.length()-1),point=-1;
   for(int i=last;i>CHUNK/2;i--){
    char ch=text.charAt(i);
    if(ch=='.'||ch=='!'||ch=='?'||ch=='\n'||ch==';'||ch==','||ch=='。'){point=i+1;break;}
   }
   if(point<0)for(int i=last;i>CHUNK/2;i--)if(Character.isWhitespace(text.charAt(i))){point=i;break;}
   if(point<0)point=last;
   chunks.add(text.substring(0,point).trim());text=text.substring(point).trim();
  }
  return chunks;
 }
 private static String read(InputStream input,int max)throws Exception{
  if(input==null)return "";
  try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buf=new byte[8192];int n;
   while((n=in.read(buf))!=-1){
    if(out.size()+n>max)throw new Exception("Phản hồi API quá lớn.");
    out.write(buf,0,n);
   }
   return out.toString("UTF-8");
  }
 }
}
