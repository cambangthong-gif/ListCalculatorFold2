package vn.puck.epubreader;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Private persistent on-device PCM cache, keyed by the model/voice/text. */
final class SpeechCache {
 private static final long LIMIT_BYTES=192L*1024L*1024L;
 private static final int MAX_CLIP_BYTES=11*1024*1024;
 private static final String VOICE_PREFIX="puck-3.8-flash-lite-cheerful-v1:";
 private SpeechCache(){}
 private static File folder(Context ctx){
  File f=new File(ctx.getFilesDir(),"puck-audio");
  if(!f.exists())f.mkdirs();
  return f;
 }
 private static String hash(String text)throws Exception{
  byte[] data=MessageDigest.getInstance("SHA-256").digest(
       (VOICE_PREFIX+text).getBytes(StandardCharsets.UTF_8));
  StringBuilder result=new StringBuilder();
  for(byte b:data)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  return result.toString();
 }
 private static File entry(Context ctx,String text)throws Exception{
  return new File(folder(ctx),hash(text)+".pcm");
 }
 static synchronized byte[] load(Context ctx,String text){
  try{
   File f=entry(ctx,text);
   long len=f.length();
   if(!f.isFile()||len<1000||len>MAX_CLIP_BYTES)return null;
   byte[] data=new byte[(int)len];
   try(FileInputStream in=new FileInputStream(f)){
    int off=0,n;
    while(off<data.length&&(n=in.read(data,off,data.length-off))>0)off+=n;
    if(off!=data.length)return null;
   }
   // Update LRU by file modified timestamp, not filesystem access-time.
   f.setLastModified(System.currentTimeMillis());
   return data;
  }catch(Exception ignored){return null;}
 }
 static synchronized void save(Context ctx,String text,byte[] audio){
  if(audio==null||audio.length<1000||audio.length>MAX_CLIP_BYTES)return;
  try{
   File target=entry(ctx,text);
   if(target.isFile()){target.setLastModified(System.currentTimeMillis());return;}
   File temporary=new File(target.getParentFile(),target.getName()+".part");
   try(FileOutputStream out=new FileOutputStream(temporary)){
    out.write(audio);
    out.getFD().sync();
   }
   if(!temporary.renameTo(target)){
    temporary.delete();
    return;
   }
   prune(target.getParentFile());
  }catch(Exception ignored){}
 }
 private static void prune(File dir){
  File[] files=dir.listFiles((d,name)->name.endsWith(".pcm"));
  if(files==null)return;
  Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
  long total=0;
  for(File f:files)total+=f.length();
  for(File f:files){
   if(total<=LIMIT_BYTES)break;
   long bytes=f.length();
   if(f.delete())total-=bytes;
  }
 }
 static synchronized long bytes(Context ctx){
  long bytes=0;File[] list=folder(ctx).listFiles((dir,name)->name.endsWith(".pcm"));
  if(list!=null)for(File f:list)bytes+=f.length();
  return bytes;
 }
}
