package vn.puck.epubreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
 private static final int PICK_EPUB=4001;
 private static final String STORE="puck_reading";
 private static final int INK=0xff1e293b,BLUE=0xff1c62ba,MUTED=0xff677589;
 private EpubBook book;
 private PuckPlayer player;
 private ScrollView scroll;
 private TextView reader,title,chapterLabel,status,stats;
 private Spinner chapterSpinner;
 private Button play;
 private int currentSegment=0,chapterShown=-1,font=20;
 private boolean ignoreSelection=false;
 private Spannable chapterText;
 private final ArrayList<Integer> textOffsets=new ArrayList<>();
 private BackgroundColorSpan highlight;
 private String lastStatus="Mở sách EPUB để bắt đầu.";
 private int lastDelay=-1,lastPrefetch=0;
 private SharedPreferences prefs(){return getSharedPreferences(STORE,MODE_PRIVATE);}
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private GradientDrawable shape(int fill,int radius){
  GradientDrawable b=new GradientDrawable();b.setColor(fill);b.setCornerRadius(dp(radius));return b;
 }
 private TextView text(String value,int size,int color,boolean bold){
  TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);
  if(bold)v.setTypeface(null,Typeface.BOLD);
  return v;
 }
 private Button button(String label,boolean primary){
  Button b=new Button(this);b.setText(label);b.setAllCaps(false);
  b.setTextSize(14);b.setTextColor(primary?Color.WHITE:BLUE);
  b.setBackground(shape(primary?BLUE:0xffe8f0fc,12));
  b.setMinHeight(dp(46));return b;
 }
 private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
 private void weighted(LinearLayout parent,View v,float weight){
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(45),weight);
  lp.setMargins(dp(2),0,dp(2),0);parent.addView(v,lp);
 }
 private void gap(LinearLayout parent,int value){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(value)));}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  font=prefs().getInt("font",20);
  player=new PuckPlayer(this,new PuckPlayer.Listener(){
   @Override public void progress(int position,int ahead,int delay){
    runOnUiThread(()->{
     if(book==null)return;
     currentSegment=position;lastPrefetch=ahead;lastDelay=delay;
     EpubBook.Segment seg=book.segments.get(position);
     if(chapterShown!=seg.chapter)renderChapter(seg.chapter);
     markParagraph(seg.paragraph);
     prefs().edit().putInt("segment",position).apply();
     updateStatus();
    });
   }
   @Override public void state(String message){
    runOnUiThread(()->{lastStatus=message;updateStatus();});
   }
   @Override public void error(String message){
    runOnUiThread(()->{
     lastStatus="Không đọc được: "+message;
     updateStatus();
     new AlertDialog.Builder(MainActivity.this).setTitle("TTS tạm dừng")
       .setMessage(message+"\n\nNếu 429 do giới hạn theo ngày, cần chờ hạn mức phục hồi. Kiểm tra aistudio.google.com > Rate limits. Những đoạn giọng đã tạo sẽ được giữ trong máy.")
       .setPositiveButton("Đóng",null).show();
    });
   }
  });
  buildUi();
  Uri intentUri=getIntent()!=null?getIntent().getData():null;
  if(intentUri!=null)openBook(intentUri);
  else{
   String saved=prefs().getString("uri",null);
   if(saved!=null)openBook(Uri.parse(saved));
  }
 }
 private void buildUi(){
  LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);
  outer.setBackgroundColor(0xfffcfcfd);
  outer.setPadding(dp(12),dp(10),dp(12),dp(12));
  setContentView(outer);
  int availableDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
  LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
  LinearLayout.LayoutParams centerLp=new LinearLayout.LayoutParams(-1,-1);
  if(availableDp>900)centerLp.width=dp(900);
  centerLp.gravity=Gravity.CENTER_HORIZONTAL;
  outer.addView(column,centerLp);

  LinearLayout top=row();
  Button open=button("📖 Mở EPUB",true),api=button("🔑 API key",false);
  weighted(top,open,1.4f);weighted(top,api,1f);
  column.addView(top,new LinearLayout.LayoutParams(-1,dp(46)));
  open.setOnClickListener(v->pickBook());api.setOnClickListener(v->showKeyDialog());
  gap(column,9);

  title=text("Puck EPUB Reader",21,INK,true);
  title.setMaxLines(2);column.addView(title);
  gap(column,5);
  LinearLayout chapters=row();
  Button prevChapter=button("‹ Chương",false),nextChapter=button("Chương ›",false);
  weighted(chapters,prevChapter,1f);weighted(chapters,nextChapter,1f);
  column.addView(chapters,new LinearLayout.LayoutParams(-1,dp(46)));
  prevChapter.setOnClickListener(v->moveChapter(-1));
  nextChapter.setOnClickListener(v->moveChapter(1));
  gap(column,7);

  chapterSpinner=new Spinner(this);
  chapterSpinner.setBackground(shape(0xffeaf1fa,12));
  chapterSpinner.setPadding(dp(12),dp(3),dp(10),dp(3));
  column.addView(chapterSpinner,new LinearLayout.LayoutParams(-1,dp(44)));
  chapterSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
   @Override public void onNothingSelected(AdapterView<?> adapter){}
   @Override public void onItemSelected(AdapterView<?> adapter,View view,int index,long id){
    if(ignoreSelection||book==null||index==chapterShown)return;
    chooseChapter(index,true);
   }
  });
  gap(column,5);
  chapterLabel=text("Tiếng Việt • Anh • Nhật • Giọng Puck",13,MUTED,false);
  column.addView(chapterLabel);
  gap(column,7);

  scroll=new ScrollView(this);scroll.setFillViewport(true);
  scroll.setBackground(shape(0xfff4f6fa,16));
  reader=text("Nhấn “Mở EPUB” để chọn sách.\n\nChế độ tiết kiệm hạn mức: chỉ gọi một yêu cầu Gemini mỗi lần, có đọc trước và lưu âm thanh trên máy.",font,INK,false);
  reader.setLineSpacing(dp(7),1.12f);
  reader.setPadding(dp(18),dp(20),dp(18),dp(25));
  scroll.addView(reader,new ScrollView.LayoutParams(-1,-2));
  column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  gap(column,10);

  LinearLayout bar=row();
  Button smaller=button("A−",false),bigger=button("A+",false);
  weighted(bar,smaller,1f);weighted(bar,bigger,1f);
  column.addView(bar,new LinearLayout.LayoutParams(-1,dp(44)));
  smaller.setOnClickListener(v->changeFont(-1));
  bigger.setOnClickListener(v->changeFont(1));
  gap(column,7);

  LinearLayout controls=row();
  Button previous=button("« Đoạn",false);
  play=button("▶ Đọc / ⏸",true);
  Button stop=button("■ Dừng",false),next=button("Đoạn »",false);
  weighted(controls,previous,1f);weighted(controls,play,1.3f);
  weighted(controls,stop,0.92f);weighted(controls,next,1f);
  column.addView(controls,new LinearLayout.LayoutParams(-1,dp(48)));
  previous.setOnClickListener(v->skip(-1));
  play.setOnClickListener(v->toggle());
  stop.setOnClickListener(v->player.stop());
  next.setOnClickListener(v->skip(1));
  gap(column,8);

  status=text(lastStatus,13,INK,true);
  status.setBackground(shape(0xffeef4ff,10));
  status.setPadding(dp(10),dp(9),dp(10),dp(9));
  column.addView(status);
  gap(column,5);
  stats=text("Đọc trước 1 đoạn • Puck Flash-Lite • Lưu giọng trên máy",12,MUTED,false);
  column.addView(stats);
  if(book!=null)showBook();else updateStatus();
 }
 private void changeFont(int delta){
  font=Math.max(15,Math.min(33,font+delta));
  prefs().edit().putInt("font",font).apply();
  reader.setTextSize(font);
 }
 private void pickBook(){
  Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
  i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
  i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/epub+zip","application/octet-stream","application/zip"});
  startActivityForResult(i,PICK_EPUB);
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==PICK_EPUB&&result==RESULT_OK&&data!=null&&data.getData()!=null){
   Uri uri=data.getData();
   try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
   catch(Exception ignored){}
   openBook(uri);
  }
 }
 private void openBook(Uri uri){
  player.stop();
  lastStatus="Đang mở EPUB và tách chương...";
  updateStatus();
  new Thread(()->{
   try{
    EpubBook b=EpubBook.open(this,uri);
    runOnUiThread(()->{
     book=b;player.setBook(b);
     String previous=prefs().getString("uri","");
     int saved=uri.toString().equals(previous)?prefs().getInt("segment",0):0;
     prefs().edit().putString("uri",uri.toString()).putInt("segment",saved).apply();
     currentSegment=Math.max(0,Math.min(saved,b.segments.size()-1));
     showBook();
     lastStatus="Sẵn sàng. Nhấn ▶ Đọc để bắt đầu.";
     updateStatus();
    });
   }catch(Exception err){
    runOnUiThread(()->{
     lastStatus="EPUB không đọc được: "+err.getMessage();updateStatus();
     new AlertDialog.Builder(this).setTitle("Không mở được EPUB")
      .setMessage(err.getMessage()).setPositiveButton("Đóng",null).show();
    });
   }
  },"OpenEpub").start();
 }
 private void showBook(){
  if(book==null)return;
  title.setText(book.title);
  ArrayList<String> names=new ArrayList<>();
  for(int i=0;i<book.chapters.size();i++){
   String name=book.chapters.get(i).title.replace('\n',' ').trim();
   if(name.length()>45)name=name.substring(0,45)+"…";
   names.add(String.format(Locale.ROOT,"%d. %s",i+1,name));
  }
  chapterSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
  renderChapter(book.segments.get(currentSegment).chapter);
  markParagraph(book.segments.get(currentSegment).paragraph);
  updateStatus();
 }
 private void renderChapter(int idx){
  if(book==null||idx<0||idx>=book.chapters.size())return;
  chapterShown=idx;
  EpubBook.Chapter ch=book.chapters.get(idx);
  chapterLabel.setText("Chương "+(idx+1)+"/"+book.chapters.size()+" • "+ch.paragraphs.size()+" đoạn văn");
  SpannableStringBuilder sb=new SpannableStringBuilder();
  textOffsets.clear();
  for(String p:ch.paragraphs){
   textOffsets.add(sb.length());
   sb.append(p).append("\n\n");
  }
  reader.setText(sb,TextView.BufferType.SPANNABLE);
  chapterText=(Spannable)reader.getText();
  ignoreSelection=true;
  chapterSpinner.setSelection(idx,false);
  ignoreSelection=false;
  scroll.scrollTo(0,0);
 }
 private void markParagraph(int index){
  if(chapterText==null||index<0||index>=textOffsets.size())return;
  if(highlight!=null){chapterText.removeSpan(highlight);highlight=null;}
  int begin=textOffsets.get(index);
  int end=index+1<textOffsets.size()?Math.max(begin,textOffsets.get(index+1)-2):chapterText.length();
  highlight=new BackgroundColorSpan(0xffd8e9ff);
  chapterText.setSpan(highlight,begin,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  reader.invalidate();
  final int offset=begin;
  scroll.post(()->{
   Layout layout=reader.getLayout();
   if(layout!=null){
    int line=layout.getLineForOffset(Math.min(offset,reader.length()));
    int y=layout.getLineTop(line);
    scroll.smoothScrollTo(0,Math.max(0,y-dp(70)));
   }
  });
 }
 private void chooseChapter(int idx,boolean autoplay){
  if(book==null)return;
  EpubBook.Chapter chapter=book.chapters.get(idx);
  if(chapter.segmentPositions.isEmpty())return;
  currentSegment=chapter.segmentPositions.get(0);
  prefs().edit().putInt("segment",currentSegment).apply();
  renderChapter(idx);markParagraph(book.segments.get(currentSegment).paragraph);
  if(autoplay&&ensureKey())player.startAt(currentSegment);
  updateStatus();
 }
 private void moveChapter(int direction){
  if(book==null)return;
  chooseChapter(Math.max(0,Math.min(book.chapters.size()-1,chapterShown+direction)),true);
 }
 private void skip(int direction){
  if(book==null)return;
  currentSegment=Math.max(0,Math.min(book.segments.size()-1,currentSegment+direction));
  if(book.segments.get(currentSegment).chapter!=chapterShown)
   renderChapter(book.segments.get(currentSegment).chapter);
  markParagraph(book.segments.get(currentSegment).paragraph);
  prefs().edit().putInt("segment",currentSegment).apply();
  if(ensureKey())player.startAt(currentSegment);
  updateStatus();
 }
 private void toggle(){
  if(book==null){pickBook();return;}
  if(!ensureKey())return;
  if(player.isPlaying()||player.isPaused())player.pauseOrResume();
  else player.startAt(currentSegment);
  updateStatus();
 }
 private boolean ensureKey(){
  if(SettingsStore.getKey(this).isEmpty()){
   showKeyDialog();return false;
  }
  return true;
 }
 private void showKeyDialog(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  root.setPadding(dp(20),dp(8),dp(20),dp(4));
  TextView help=text("Tạo Gemini API key tại aistudio.google.com/apikey. Key được mã hóa trong máy, không gửi cho nhà phát triển. Mỗi đoạn sách sẽ được gửi đến Google để tổng hợp giọng Puck.",14,MUTED,false);
  root.addView(help);
  EditText key=new EditText(this);
  key.setSingleLine(true);key.setHint(SettingsStore.getKey(this).isEmpty()?"Dán API key":"Đã lưu key; dán key mới nếu muốn đổi");
  key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
  root.addView(key);
  new AlertDialog.Builder(this).setTitle("Thiết lập giọng Puck")
   .setView(root).setNegativeButton("Hủy",null)
   .setPositiveButton("Lưu", (d,w)->{
    String value=key.getText().toString().trim();
    if(!value.isEmpty())try{
     SettingsStore.saveKey(this,value);
     lastStatus="Đã lưu API key. Bấm ▶ để đọc.";updateStatus();
    }catch(Exception e){lastStatus="Không lưu được key: "+e.getMessage();updateStatus();}
   }).show();
 }
 private void updateStatus(){
  if(status==null)return;
  status.setText(lastStatus);
  if(book==null){stats.setText("EPUB • Puck • Gọi API lần lượt • Có bộ nhớ âm thanh");return;}
  EpubBook.Segment seg=book.segments.get(currentSegment);
  stats.setText("Chương "+(seg.chapter+1)+"/"+book.chapters.size()+
   " • "+(currentSegment+1)+"/"+book.segments.size()+" đoạn"+
   (lastDelay>=0?" • Chờ đầu "+String.format(Locale.ROOT,"%.1f",lastDelay/1000f)+" s":"")+
   " • Đệm sẵn "+lastPrefetch+" đoạn"+
   " • Đã lưu "+String.format(Locale.ROOT,"%.1f",SpeechCache.bytes(this)/(1024f*1024f))+" MB");
 }
 @Override public void onConfigurationChanged(Configuration config){
  super.onConfigurationChanged(config);
  buildUi();
 }
 @Override protected void onDestroy(){
  if(player!=null)player.shutdown();
  super.onDestroy();
 }
}
