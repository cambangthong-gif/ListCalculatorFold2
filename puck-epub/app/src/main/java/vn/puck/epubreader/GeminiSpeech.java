package vn.puck.epubreader;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class GeminiSpeech {
 static final int SAMPLE_RATE=24000;
 private GeminiSpeech(){}

 interface Status {
  void update(String message);
 }
 static final class RateLimitException extends Exception {
  final boolean daily;
  final long retryMs;
  RateLimitException(String message,boolean daily,long retryMs){
   super(message);this.daily=daily;this.retryMs=retryMs;
  }
 }
 private static final Object RATE_LOCK=new Object();
 private static long nextAllowedAt=0;
 private static final long MIN_REQUEST_INTERVAL=15000L;

 static byte[] synthesize(Context context,String input)throws Exception{
  return synthesize(context,input,null);
 }
 static byte[] synthesize(Context context,String input,Status feedback)throws Exception{
  byte[] cached=SpeechCache.load(context,input);
  if(cached!=null){
   if(feedback!=null)feedback.update("Đã có giọng Puck trong bộ nhớ; không dùng API.");
   return cached;
  }
  String key=SettingsStore.getKey(context);
  if(key.isEmpty())throw new Exception("Chưa nhập Gemini API key.");
  for(int attempt=0;attempt<2;attempt++){
   waitForSlot(feedback);
   try{
    byte[] pcm=callGemini(context,key,input);
    SpeechCache.save(context,input,pcm);
    return pcm;
   }catch(RateLimitException ex){
    if(ex.daily||attempt>=1)throw ex;
    long backoff=Math.max(20000L,Math.min(90000L,ex.retryMs));
    synchronized(RATE_LOCK){
     nextAllowedAt=Math.max(nextAllowedAt,android.os.SystemClock.elapsedRealtime()+backoff);
    }
    if(feedback!=null)feedback.update("Google 429: tạm giới hạn yêu cầu. Tự thử lại sau "+
       (backoff/1000)+" giây (tối đa một lần).");
   }
  }
  throw new Exception("Không tạo được âm thanh.");
 }
 private static void waitForSlot(Status feedback)throws InterruptedException{
  long scheduled;
  synchronized(RATE_LOCK){
   long now=android.os.SystemClock.elapsedRealtime();
   scheduled=Math.max(nextAllowedAt,now);
   nextAllowedAt=scheduled+MIN_REQUEST_INTERVAL;
  }
  long previous=-1;
  while(true){
   long remaining=scheduled-android.os.SystemClock.elapsedRealtime();
   if(remaining<=0)return;
   long seconds=(remaining+999)/1000;
   if(feedback!=null&&(previous<0||seconds<=3||seconds%5==0)&&seconds!=previous)
    feedback.update("Điều tiết API miễn phí • chờ "+seconds+" giây");
   previous=seconds;
   Thread.sleep(Math.min(1000L,remaining));
  }
 }
 private static byte[] callGemini(Context context,String key,String input)throws Exception{
  JSONObject annotation=new JSONObject().put("type","speech_metadata")
   .put("style","Cheerful, curious, engaging, lively, natural audiobook storytelling. Read faithfully without adding or omitting words.");
  JSONObject text=new JSONObject().put("type","text")
   .put("text",input).put("annotations",new JSONArray().put(annotation));
  JSONObject payload=new JSONObject()
   .put("model","gemini-3.8-flash-lite-tts")
   .put("input",new JSONArray().put(
    new JSONObject().put("type","user_input").put("content",new JSONArray().put(text))))
   .put("response_format",new JSONObject().put("type","audio")
    .put("mime_type","audio/l16").put("sample_rate",24000))
   .put("generation_config",new JSONObject().put("speech_config",
    new JSONArray().put(new JSONObject().put("voice","Puck"))));
  HttpURLConnection conn=null;
  try{
   conn=(HttpURLConnection)new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
   conn.setConnectTimeout(12000);conn.setReadTimeout(90000);
   conn.setRequestMethod("POST");conn.setDoOutput(true);
   conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
   conn.setRequestProperty("x-goog-api-key",key);
   conn.setRequestProperty("Connection","keep-alive");
   try(OutputStream output=conn.getOutputStream()){
    output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
   }
   int code=conn.getResponseCode();
   if(code!=200){
    String detail=read(conn.getErrorStream(),5000);
    if(code==429){
     String lower=detail.toLowerCase(java.util.Locale.ROOT);
     boolean daily=lower.contains("per_day")||lower.contains("perday")||
       lower.contains("requestsperday")||lower.contains("quota_exceeded")||
       lower.contains("per day")||lower.contains("rpd");
     long retryMs=30000L;
     String retryHeader=conn.getHeaderField("Retry-After");
     if(retryHeader!=null)try{
      retryMs=Math.max(5000L,Math.min(90000L,
          (long)(Double.parseDouble(retryHeader.trim())*1000)));
     }catch(NumberFormatException ignored){}
     if(daily)throw new RateLimitException(
       "Google 429: đã hết hạn mức theo ngày của dự án Gemini. Không thể tự khắc phục bằng thử lại liên tục. Kiểm tra AI Studio > Rate limits.",true,retryMs);
     throw new RateLimitException(
       "Google 429: quá nhiều yêu cầu hoặc hết hạn mức. Đã giảm tốc độ; nếu vẫn lỗi, kiểm tra hạn mức dự án trong AI Studio.",false,retryMs);
    }
    if(code==401||code==403)throw new Exception("Gemini từ chối API key hoặc quyền TTS ("+code+").");
    throw new Exception("Gemini HTTP "+code+": "+detail.substring(0,Math.min(220,detail.length())));
   }
   JSONObject root=new JSONObject(read(conn.getInputStream(),18000000));
   String encoded="";
   JSONObject direct=root.optJSONObject("output_audio");
   if(direct!=null)encoded=direct.optString("data","");
   if(encoded.isEmpty()){
    JSONArray steps=root.optJSONArray("steps");
    if(steps!=null)for(int i=0;i<steps.length();i++){
     JSONObject step=steps.optJSONObject(i);
     if(step==null||!"model_output".equals(step.optString("type")))continue;
     JSONArray parts=step.optJSONArray("content");
     if(parts==null)continue;
     for(int j=0;j<parts.length();j++){
      JSONObject part=parts.optJSONObject(j);
      if(part!=null&&"audio".equals(part.optString("type")))encoded=part.optString("data","");
     }
    }
   }
   if(encoded.isEmpty())throw new Exception("Gemini không trả dữ liệu âm thanh.");
   byte[] result=Base64.decode(encoded,Base64.DEFAULT);
   if(result.length<1000)throw new Exception("Âm thanh từ Gemini rỗng hoặc quá ngắn.");
   return stripWav(result);
  }finally{
   if(conn!=null)conn.disconnect();
  }
 }
 private static byte[] stripWav(byte[] bytes){
  if(bytes.length<44||bytes[0]!='R'||bytes[1]!='I'||bytes[2]!='F'||bytes[3]!='F')return bytes;
  for(int i=12;i+8<bytes.length;){
   int len=(bytes[i+4]&255)|((bytes[i+5]&255)<<8)|((bytes[i+6]&255)<<16)|((bytes[i+7]&255)<<24);
   if(bytes[i]=='d'&&bytes[i+1]=='a'&&bytes[i+2]=='t'&&bytes[i+3]=='a'){
    int start=i+8;
    if(len<0||start+(long)len>bytes.length)return bytes;
    return Arrays.copyOfRange(bytes,start,start+len);
   }
   if(len<0)break;
   long next=i+8L+len+(len&1);
   if(next>bytes.length)break;
   i=(int)next;
  }
  return bytes;
 }
 private static String read(InputStream stream,int limit)throws Exception{
  if(stream==null)return "";
  try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int n;
   while((n=in.read(buffer))!=-1){
    if(out.size()+n>limit)throw new Exception("Phản hồi Gemini vượt giới hạn bộ nhớ.");
    out.write(buffer,0,n);
   }
   return out.toString("UTF-8");
  }
 }
}
