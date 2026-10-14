package vn.tts.moonbridge;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
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
 private static final int RATE=24000,CHUNK=380;
 private static final List<String> LANGS=Arrays.asList("vi","en","ja","ko","zh","fr","de","es","pt","it","ru","th","id","hi","ar","nl","tr","pl");
 private volatile boolean stopped=false;
 private volatile HttpURLConnection active;

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
 @Override protected void onSynthesizeText(SynthesisRequest request,SynthesisCallback cb){
  stopped=false;
  String key=SettingsStore.getKey(this);
  if(key.isEmpty()){
   SettingsStore.setError(this,"Chưa có Gemini API key. Hãy mở Moon Gemini TTS để nhập.");
   cb.error();return;
  }
  CharSequence chars=request.getCharSequenceText();
  String text=chars==null?"":chars.toString().trim();
  if(text.isEmpty()){cb.start(RATE,AudioFormat.ENCODING_PCM_16BIT,1);cb.done();return;}
  String model=SettingsStore.model(this),voice=SettingsStore.voice(this),style=SettingsStore.style(this);
  if(!Arrays.asList(SettingsStore.MODELS).contains(model))model=SettingsStore.MODELS[0];
  if(!Arrays.asList(SettingsStore.VOICES).contains(voice))voice=SettingsStore.VOICES[0];
  boolean started=false;
  try{
   for(String piece:splitText(text)){
    if(stopped)return;
    byte[] pcm=requestAudio(key,model,voice,style,request.getSpeechRate(),piece);
    if(stopped)return;
    if(pcm.length==0)throw new Exception("Gemini trả âm thanh rỗng.");
    if(!started){
     if(cb.start(RATE,AudioFormat.ENCODING_PCM_16BIT,1)!=TextToSpeech.SUCCESS)return;
     started=true;SettingsStore.setError(this,"");
    }
    int size=Math.max(2,Math.min(8192,cb.getMaxBufferSize()))&~1;
    for(int offset=0;offset<pcm.length&&!stopped;offset+=size){
     int len=Math.min(size,pcm.length-offset)&~1;
     if(len<=0)break;
     if(cb.audioAvailable(pcm,offset,len)!=TextToSpeech.SUCCESS)return;
    }
   }
   if(!stopped)cb.done();
  }catch(Exception e){
   if(!stopped){
    String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
    SettingsStore.setError(this,msg.substring(0,Math.min(280,msg.length())));
    cb.error();
   }
  }
 }
 private byte[] requestAudio(String key,String model,String voice,String style,int speed,String text)throws Exception{
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
   active=c;c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(100000);
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
