package vn.puck.epubreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class PuckPlayer {
 interface Listener {
  void progress(int segment,int ahead,int firstDelayMs);
  void state(String message);
  void error(String message);
 }
 private static final int PREFETCH=2;
 private static final int CACHE_LIMIT=16*1024*1024;
 private final Context context;
 private final Listener listener;
 private final ExecutorService playback=Executors.newSingleThreadExecutor();
 private final ExecutorService synthesis=Executors.newSingleThreadExecutor();
 private final Object guard=new Object();
 private final HashMap<Integer,Future<byte[]>> pending=new HashMap<>();
 private final LinkedHashMap<Integer,byte[]> cache=new LinkedHashMap<>(12,0.75f,true);
 private int cacheBytes=0,session=0,position=0;
 private volatile boolean paused=false,playing=false;
 private volatile AudioTrack activeTrack;
 private EpubBook book;
 PuckPlayer(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

 void setBook(EpubBook b){
  stop();
  synchronized(guard){
   // Segment indices start again at zero for each book. Cached audio must not
   // leak from the previous EPUB into the new book.
   cache.clear();
   cacheBytes=0;
   book=b;
   position=0;
  }
 }
 int position(){synchronized(guard){return position;}}
 void startAt(int wanted){
  final EpubBook current;
  final int token;
  synchronized(guard){
   if(book==null||book.segments.isEmpty())return;
   current=book;
   position=Math.max(0,Math.min(wanted,book.segments.size()-1));
   token=++session;
   clearPending();
   paused=false;playing=true;
  }
  AudioTrack t=activeTrack;
  if(t!=null)try{t.pause();t.flush();}catch(Exception ignored){}
  playback.execute(()->playLoop(current,token));
 }
 void pauseOrResume(){
  AudioTrack t;
  synchronized(guard){
   if(!playing){
    if(book!=null)startAt(position);
    return;
   }
   paused=!paused;t=activeTrack;
  }
  if(t!=null)try{if(paused)t.pause();else t.play();}catch(Exception ignored){}
  listener.state(paused?"Đã tạm dừng":"Đang tiếp tục đọc");
 }
 void stop(){
  synchronized(guard){
   ++session;paused=false;playing=false;clearPending();
  }
  AudioTrack t=activeTrack;
  if(t!=null)try{t.pause();t.flush();}catch(Exception ignored){}
  listener.state("Đã dừng đọc");
 }
 void shutdown(){
  stop();
  playback.shutdownNow();synthesis.shutdownNow();
 }
 boolean isPlaying(){return playing&&!paused;}
 boolean isPaused(){return playing&&paused;}
 private void clearPending(){
  for(Future<byte[]> future:pending.values())future.cancel(true);
  pending.clear();
 }
 private boolean valid(int token){
  synchronized(guard){return session==token&&playing;}
 }
 private void prefetch(EpubBook current,int token,int from){
  synchronized(guard){
   if(session!=token)return;
   for(int i=from;i<Math.min(from+PREFETCH,current.segments.size());i++){
    final int index=i;
    if(cache.containsKey(i)||pending.containsKey(i))continue;
    final String text=current.segments.get(i).text;
    pending.put(i,synthesis.submit(()->GeminiSpeech.synthesize(context,text,
        status->listener.state(status))));
   }
  }
 }
 private byte[] obtain(EpubBook current,int token,int index)throws Exception{
  Future<byte[]> future;
  synchronized(guard){
   if(session!=token)throw new CancellationException();
   byte[] cached=cache.get(index);
   if(cached!=null)return cached;
   future=pending.get(index);
   if(future==null){
    final String text=current.segments.get(index).text;
    future=synthesis.submit(()->GeminiSpeech.synthesize(context,text,
        status->listener.state(status)));
    pending.put(index,future);
   }
  }
  byte[] pcm;
  try{pcm=future.get(200,TimeUnit.SECONDS);}
  catch(ExecutionException ex){
   Throwable cause=ex.getCause();
   throw new Exception(cause==null?"Gemini thất bại":cause.getMessage(),cause);
  }
  synchronized(guard){
   pending.remove(index);
   if(session!=token)throw new CancellationException();
   cache.put(index,pcm);
   cacheBytes+=pcm.length;
   // Retain recent segments for replay without spending Gemini requests again.
   while(cacheBytes>CACHE_LIMIT&&cache.size()>1){
    Map.Entry<Integer,byte[]> first=cache.entrySet().iterator().next();
    cacheBytes-=first.getValue().length;
    cache.remove(first.getKey());
   }
  }
  return pcm;
 }
 private int readyAhead(int index){
  synchronized(guard){
   int count=0;
   for(int j=index+1;j<index+PREFETCH;j++){
    Future<byte[]> f=pending.get(j);
    if(cache.containsKey(j)||(f!=null&&f.isDone()))count++;
   }
   return count;
  }
 }
 private void playLoop(EpubBook current,int token){
  AudioTrack track=null;
  long requestedAt=SystemClock.elapsedRealtime();
  int firstDelay=-1;
  try{
   int buffer=Math.max(16384,AudioTrack.getMinBufferSize(GeminiSpeech.SAMPLE_RATE,
       AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT));
   track=new AudioTrack.Builder()
       .setAudioAttributes(new AudioAttributes.Builder()
           .setUsage(AudioAttributes.USAGE_MEDIA)
           .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
       .setAudioFormat(new AudioFormat.Builder()
           .setSampleRate(GeminiSpeech.SAMPLE_RATE)
           .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
           .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
       .setTransferMode(AudioTrack.MODE_STREAM)
       .setBufferSizeInBytes(buffer).build();
   if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new Exception("Android không khởi tạo được loa PCM.");
   if(!valid(token))return;
   activeTrack=track;
   int index;
   synchronized(guard){index=position;}
   prefetch(current,token,index);
   boolean began=false;
   while(index<current.segments.size()&&valid(token)){
    prefetch(current,token,index);
    listener.state("Đang chuẩn bị giọng Puck • bộ đệm "+readyAhead(index)+"/"+(PREFETCH-1));
    byte[] audio=obtain(current,token,index);
    if(!valid(token))break;
    if(!began){firstDelay=(int)(SystemClock.elapsedRealtime()-requestedAt);began=true;track.play();}
    while(valid(token)&&paused)Thread.sleep(90);
    if(!valid(token))break;
    synchronized(guard){position=index;}
    listener.progress(index,readyAhead(index),firstDelay);
    int offset=0;
    while(offset<audio.length&&valid(token)){
     if(paused){Thread.sleep(70);continue;}
     int amount=Math.min(8192,audio.length-offset)&~1;
     if(amount==0)break;
     int n=track.write(audio,offset,amount,AudioTrack.WRITE_BLOCKING);
     if(n<0)throw new Exception("AudioTrack không thể phát âm thanh ("+n+").");
     if(n==0){Thread.sleep(20);continue;}
     offset+=n;
    }
    if(!valid(token))break;
    index++;
   }
   if(valid(token)&&index>=current.segments.size()){
    // Let the small AudioTrack hardware buffer drain before releasing it.
    long playedFrames=track.getPlaybackHeadPosition()&0xffffffffL;
    int waitMs=(int)Math.min(700,Math.max(0,buffer*1000L/(GeminiSpeech.SAMPLE_RATE*2)));
    Thread.sleep(waitMs);
    synchronized(guard){if(session==token){playing=false;paused=false;}}
    listener.state("Đã đọc hết sách.");
   }
  }catch(CancellationException ignored){
  }catch(InterruptedException ignored){Thread.currentThread().interrupt();
  }catch(Exception ex){
   if(valid(token)){
    synchronized(guard){playing=false;paused=false;clearPending();}
    String message=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
    listener.error(message);
   }
  }finally{
   if(track!=null){
    if(activeTrack==track)activeTrack=null;
    try{track.pause();track.flush();track.release();}catch(Exception ignored){}
   }
  }
 }
}
