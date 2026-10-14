package vn.tts.moonbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SettingsStore {
 static final String[] VOICES={"Puck","Fenrir","Achird","Zubenelgenubi"};
 static final String[] MODELS={"gemini-3.8-flash-lite-tts","gemini-3.8-flash-tts"};
 static final String[] STYLES={"Tự nhiên","Vui vẻ","Kể chuyện","Điềm tĩnh"};
 private static final String PREF="moon_gemini_tts",ALIAS="vn.tts.moonbridge.key";
 private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
 static String voice(Context c){return prefs(c).getString("voice",VOICES[0]);}
 static String model(Context c){return prefs(c).getString("model",MODELS[0]);}
 static String style(Context c){return prefs(c).getString("style",STYLES[1]);}
 static void saveOptions(Context c,String v,String m,String s){
  prefs(c).edit().putString("voice",v).putString("model",m).putString("style",s).apply();
 }
 static void setError(Context c,String error){prefs(c).edit().putString("error",error).apply();}
 static String getError(Context c){return prefs(c).getString("error","");}
 static void saveKey(Context c,String plain)throws Exception{
  plain=plain==null?"":plain.trim();
  if(plain.isEmpty()){prefs(c).edit().remove("iv").remove("encrypted").apply();return;}
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
  byte[] encrypted=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
  prefs(c).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
   .putString("encrypted",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
 }
 static String getKey(Context c){
  String iv=prefs(c).getString("iv",""),enc=prefs(c).getString("encrypted","");
  if(iv.isEmpty()||enc.isEmpty())return "";
  try{
   KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
   SecretKey key=(SecretKey)store.getKey(ALIAS,null);
   if(key==null)return "";
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(iv,Base64.DEFAULT)));
   return new String(cipher.doFinal(Base64.decode(enc,Base64.DEFAULT)),StandardCharsets.UTF_8);
  }catch(Exception e){return "";}
 }
 private static SecretKey getOrCreateKey()throws Exception{
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  SecretKey key=(SecretKey)store.getKey(ALIAS,null);if(key!=null)return key;
  KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
   .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
  return gen.generateKey();
 }
}
