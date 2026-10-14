package vn.tts.moonbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
 private EditText keyField,trial;
 private Spinner voice,model,style,latency;
 private TextView status,metric,streamInfo;
 private TextToSpeech tester;

 private int dp(int px){return Math.round(px*getResources().getDisplayMetrics().density);}
 private GradientDrawable bg(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;}
 private TextView text(String s,int size,boolean strong){
  TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xff1e293b);
  if(strong)t.setTypeface(null,Typeface.BOLD);return t;
 }
 private void gap(LinearLayout parent,int h){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
 private void heading(LinearLayout p,String s){p.addView(text(s,15,true));gap(p,7);}
 private EditText edit(String hint,boolean multiline){
  EditText e=new EditText(this);e.setHint(hint);e.setTextSize(16);
  e.setPadding(dp(15),dp(10),dp(15),dp(10));e.setBackground(bg(0xfff0f4fa));
  if(multiline){e.setMinLines(3);e.setGravity(Gravity.TOP);}else e.setSingleLine(true);return e;
 }
 private Spinner dropdown(String[] choices,String value){
  Spinner s=new Spinner(this);
  ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);
  a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  s.setAdapter(a);s.setBackground(bg(0xffedf3fb));s.setPadding(dp(12),0,dp(12),0);
  s.setSelection(Math.max(0,Arrays.asList(choices).indexOf(value)));
  return s;
 }
 private Button button(String caption,boolean primary){
  Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextSize(16);
  b.setTextColor(primary?Color.WHITE:0xff174582);
  b.setBackground(bg(primary?0xff1766c9:0xffe4efff));return b;
 }
 private void status(String s){runOnUiThread(()->status.setText(s));}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.WHITE);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  root.setPadding(dp(22),dp(24),dp(22),dp(30));scroll.addView(root);setContentView(scroll);
  root.addView(text("Moon Gemini TTS",27,true));gap(root,8);
  TextView summary=text("TTS cho Moon+ Reader • Tiếng Việt và nhiều ngôn ngữ • Cần Internet và Gemini API key",14,false);
  summary.setTextColor(0xff58697b);root.addView(summary);gap(root,25);

  heading(root,"1. Gemini API key");
  keyField=edit("Dán API key (để trống nếu đã lưu)",false);
  keyField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
  root.addView(keyField,new LinearLayout.LayoutParams(-1,dp(54)));gap(root,7);
  root.addView(text("Tạo miễn phí: aistudio.google.com/apikey. Khóa được mã hóa bằng Android Keystore.",13,false));
  gap(root,20);

  heading(root,"2. Chọn giọng");
  voice=dropdown(SettingsStore.VOICES,SettingsStore.voice(this));
  root.addView(voice,new LinearLayout.LayoutParams(-1,dp(52)));gap(root,17);

  heading(root,"3. Mô hình TTS");
  String[] models={"Flash-Lite (ưu tiên miễn phí)","Flash (biểu cảm hơn)"};
  model=dropdown(models,models[Arrays.asList(SettingsStore.MODELS).indexOf(SettingsStore.model(this))<0?0:Arrays.asList(SettingsStore.MODELS).indexOf(SettingsStore.model(this))]);
  root.addView(model,new LinearLayout.LayoutParams(-1,dp(52)));gap(root,17);

  heading(root,"4. Phong cách");
  style=dropdown(SettingsStore.STYLES,SettingsStore.style(this));
  root.addView(style,new LinearLayout.LayoutParams(-1,dp(52)));gap(root,17);
  heading(root,"5. Tối ưu tốc độ đọc");
  latency=dropdown(SettingsStore.LATENCIES,SettingsStore.LATENCIES[SettingsStore.mode(this)]);
  root.addView(latency,new LinearLayout.LayoutParams(-1,dp(52)));gap(root,5);
  root.addView(text("Chế độ nhanh phát ngay khi nhận âm thanh, tự quay về chế độ ổn định nếu streaming bị lỗi trước khi phát.",13,false));
  gap(root,20);

  Button save=button("Lưu cấu hình",true);root.addView(save,new LinearLayout.LayoutParams(-1,dp(54)));
  save.setOnClickListener(v->save());gap(root,24);

  heading(root,"Nghe thử");
  trial=edit("Nhập câu muốn nghe thử",true);
  trial.setText("Hôm nay là một ngày thật thú vị! Chào mừng bạn đến với Moon Gemini TTS.");
  root.addView(trial,new LinearLayout.LayoutParams(-1,dp(110)));gap(root,10);
  Button test=button("▶ Nghe thử giọng",true);
  root.addView(test,new LinearLayout.LayoutParams(-1,dp(54)));
  test.setOnClickListener(v->test());gap(root,10);
  Button open=button("Mở cài đặt TTS Android",false);
  root.addView(open,new LinearLayout.LayoutParams(-1,dp(54)));
  open.setOnClickListener(v->openSettings());gap(root,20);
  status=text(SettingsStore.getKey(this).isEmpty()?"Chưa nhập API key.":"API key đã lưu. Sẵn sàng thử giọng.",14,false);
  status.setPadding(dp(12),dp(14),dp(12),dp(14));status.setBackground(bg(0xffedf3fc));
  root.addView(status);gap(root,6);
  metric=text("Lần đọc gần nhất: "+SettingsStore.metric(this),13,false);
  metric.setTextColor(0xff58697b);root.addView(metric);gap(root,7);
  streamInfo=text("Streaming: "+SettingsStore.streamInfo(this),13,false);
  streamInfo.setTextColor(0xff58697b);root.addView(streamInfo);gap(root,20);
  TextView guide=text("HƯỚNG DẪN MOON+ READER\n\n1. Lưu API key và nghe thử.\n2. Cài đặt Android > Chuyển văn bản thành giọng nói > Công cụ ưu tiên > Moon Gemini TTS.\n3. Trong Moon+ Reader, mở sách > Đọc to (TTS).\n\nỨng dụng gửi đoạn văn tới Google Gemini. Cần mạng và có giới hạn API miễn phí. Giọng Puck không phải Drew.",14,false);
  guide.setLineSpacing(dp(3),1f);root.addView(guide);
 }
 private boolean save(){
  try{
   String input=keyField.getText().toString().trim();
   if(!input.isEmpty()){SettingsStore.saveKey(this,input);keyField.setText("");}
   SettingsStore.saveOptions(this,voice.getSelectedItem().toString(),
       SettingsStore.MODELS[model.getSelectedItemPosition()],style.getSelectedItem().toString());
   SettingsStore.setMode(this,latency.getSelectedItemPosition());
   status("Đã lưu giọng "+SettingsStore.voice(this)+". Chế độ: "+
       SettingsStore.LATENCIES[SettingsStore.mode(this)]+". API key: "+
       (SettingsStore.getKey(this).isEmpty()?"chưa có":"đã mã hóa."));
   return true;
  }catch(Exception e){status("Lỗi lưu key: "+e.getMessage());return false;}
 }
 private void test(){
  if(!save())return;
  if(SettingsStore.getKey(this).isEmpty()){status("Bạn cần nhập Gemini API key.");return;}
  String message=trial.getText().toString().trim();if(message.isEmpty()){status("Hãy nhập câu để thử.");return;}
  if(tester!=null){tester.stop();tester.shutdown();tester=null;}
  status("Đang khởi tạo dịch vụ TTS...");
  tester=new TextToSpeech(this,result->{
   if(result!=TextToSpeech.SUCCESS){status("Android không kết nối được với Moon Gemini TTS. Kiểm tra Cài đặt > Chuyển văn bản thành giọng nói.");return;}
   // Trì hoãn tới vòng lặp giao diện để tránh callback khởi tạo trước khi gán tester.
   runOnUiThread(()->beginSpeaking(message));
  },getPackageName());
 }
 private void beginSpeaking(String message){
   if(tester==null){status("TTS chưa sẵn sàng; hãy thử lại.");return;}
   int langResult=tester.setLanguage(new Locale("vi","VN"));
   if(langResult==TextToSpeech.LANG_NOT_SUPPORTED||langResult==TextToSpeech.LANG_MISSING_DATA){status("Moon Gemini TTS chưa nhận tiếng Việt.");return;}
   tester.setOnUtteranceProgressListener(new UtteranceProgressListener(){
    @Override public void onStart(String id){status("Đang phát giọng "+SettingsStore.voice(MainActivity.this)+"...");}
    @Override public void onDone(String id){status("Đọc xong. "+SettingsStore.metric(MainActivity.this));runOnUiThread(()->refreshStats());}
    @Override public void onError(String id){status("Lỗi: "+SettingsStore.getError(MainActivity.this));runOnUiThread(()->refreshStats());}
    @Override public void onError(String id,int code){status("Lỗi: "+SettingsStore.getError(MainActivity.this));runOnUiThread(()->refreshStats());}
   });
   if(tester.speak(message,TextToSpeech.QUEUE_FLUSH,null,"moon_test")!=TextToSpeech.SUCCESS)
       status("Không gửi được yêu cầu TTS tới Android.");
   else status("Đang gọi Gemini. Nếu khoảng chờ kéo dài, xem thông số sau khi đọc xong.");
 }
 private void openSettings(){
  for(String action:new String[]{"com.android.settings.TTS_SETTINGS","android.settings.TTS_SETTINGS",Settings.ACTION_ACCESSIBILITY_SETTINGS}){
   try{startActivity(new Intent(action));return;}catch(Exception ignored){}
  }
  status("Tự mở Cài đặt > Chuyển văn bản thành giọng nói.");
 }
 private void refreshStats(){
  if(metric!=null)metric.setText("Lần đọc gần nhất: "+SettingsStore.metric(this));
  if(streamInfo!=null)streamInfo.setText("Streaming: "+SettingsStore.streamInfo(this));
 }
 @Override protected void onResume(){super.onResume();refreshStats();}
 @Override protected void onDestroy(){if(tester!=null){tester.stop();tester.shutdown();}super.onDestroy();}
}
