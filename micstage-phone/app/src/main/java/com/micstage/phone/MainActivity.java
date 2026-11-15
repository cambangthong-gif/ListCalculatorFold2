package com.micstage.phone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ProgressBar;

public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1423, PANEL=0xFF192A42, TXT=0xFFF0F6FF, SECOND=0xFFABBDCF, CYAN=0xFF22D3EE, GREEN=0xFF34D399;
    private volatile boolean running=false;
    private volatile int gain=75, echo=18, reverb=12, noise=0;
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private TextView status;
    private Button toggle;
    private ProgressBar level;
    private Thread work;
    private int lastMeter=0;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();
    }

    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private GradientDrawable background(int color, int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String message,int size,int color, boolean bold){
        TextView t=new TextView(this);
        t.setText(message); t.setTextSize(size); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void buildUi(){
        ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(BG);
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(20),dp(28),dp(20),dp(26));
        s.addView(c);setContentView(s);
        TextView title=label("MicStage Karaoke",29,TXT,true); c.addView(title);
        TextView sub=label("Dùng chính điện thoại làm micro hát",14,SECOND,false);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2); sp.topMargin=dp(7); c.addView(sub,sp);
        LinearLayout card=new LinearLayout(this);card.setPadding(dp(18),dp(22),dp(18),dp(20));
        card.setOrientation(LinearLayout.VERTICAL);card.setBackground(background(PANEL,20));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.topMargin=dp(25);c.addView(card,cp);
        status=label("Micro đang tắt",18,TXT,true);card.addView(status);
        TextView hint=label("Giọng hát sẽ phát lại ngay trên loa hoặc tai nghe điện thoại.",14,SECOND,false);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(9);card.addView(hint,hp);
        toggle=new Button(this);toggle.setAllCaps(false);toggle.setText("BẬT MICRO");toggle.setTextSize(18);
        toggle.setTextColor(0xFF061520);toggle.setBackground(background(CYAN,15));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.topMargin=dp(22);card.addView(toggle,bp);
        toggle.setOnClickListener(v -> {if(running)stopMic();else askMicrophone();});
        level=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        level.setMax(100); level.setProgress(0);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(12));mp.topMargin=dp(20);card.addView(level,mp);
        TextView meter=label("Mức tín hiệu micro",12,SECOND,false);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);card.addView(meter,lp);
        addSlider(c,"Âm lượng micro",250,gain,0);
        addSlider(c,"Echo • tiếng vọng",100,echo,1);
        addSlider(c,"Reverb • độ vang",100,reverb,2);
        addSlider(c,"Lọc tiếng ồn",100,noise,3);
        TextView warn=label("Lưu ý: loa ngoài và micro ở gần nhau dễ gây hú. Nên bắt đầu ở âm lượng thấp; dùng tai nghe hoặc loa có dây để giảm trễ. Bluetooth có thể bị trễ tiếng.",13,SECOND,false);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.topMargin=dp(26); c.addView(warn,wp);
    }
    private void addSlider(LinearLayout parent,String text,int max,int initial,int which){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(17);
        box.setPadding(dp(16),dp(14),dp(16),dp(10));box.setBackground(background(PANEL,16));parent.addView(box,p);
        TextView title=label(text,15,TXT,true);box.addView(title);
        TextView value=label(initial+"%",13,CYAN,true);
        LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-1,-2);vp.topMargin=dp(5);box.addView(value,vp);
        SeekBar bar=new SeekBar(this);bar.setMax(max);bar.setProgress(initial);
        box.addView(bar,new LinearLayout.LayoutParams(-1,dp(42)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){
                value.setText(n+"%");
                if(which==0)gain=n;
                else if(which==1)echo=n;
                else if(which==2)reverb=n;
                else noise=n;
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void askMicrophone(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},11);
            return;
        }
        startMic();
    }
    @Override public void onRequestPermissionsResult(int req,String[] names,int[] results){
        super.onRequestPermissionsResult(req,names,results);
        if(req==11 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)startMic();
        else if(req==11)status.setText("Cần cấp quyền micro trong Cài đặt.");
    }
    private synchronized void startMic(){
        if(running)return;
        running=true;status.setText("Đang khởi động micro…");
        toggle.setText("TẮT MICRO");
        toggle.setBackground(background(GREEN,15));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        work=new Thread(this::stream,"micstage-sound");
        work.start();
    }
    private synchronized void stopMic(){
        running=false;
        AudioRecord r=recorder;
        if(r!=null){try{r.stop();}catch(Exception ignored){}}
        AudioTrack p=player;
        if(p!=null){try{p.pause();}catch(Exception ignored){}}
        status.setText("Micro đang tắt");
        toggle.setText("BẬT MICRO");toggle.setBackground(background(CYAN,15));
        level.setProgress(0);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void stream(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try{
            final int rate=48000;
            int minIn=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            int minOut=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minIn<=0||minOut<=0)throw new IllegalStateException("Thiết bị không hỗ trợ âm thanh 48 kHz");
            AudioFormat capture=new AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            AudioFormat playback=new AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            AudioRecord r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(capture).setBufferSizeInBytes(Math.max(minIn,rate/10)).build();
            if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Không mở được micro");
            recorder=r;
            AudioTrack p=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(playback).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(Math.max(minOut,rate/10)).build();
            if(p.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Không mở được loa");
            player=p;
            if(!running)return;
            p.play();r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Micro bị hệ thống chặn");
            runOnUiThread(()-> {if(running)status.setText("Micro đang hoạt động");});
            short[] input=new short[480],output=new short[480];
            float[] delays=new float[rate],rooms=new float[rate];
            int index=0;long lastMeter=0;
            while(running){
                int n=r.read(input,0,input.length,AudioRecord.READ_BLOCKING);
                if(n<0){if(!running)break;throw new IllegalStateException("Lỗi đọc micro: "+n);}
                if(n==0)continue;
                float g=gain/100f, e=echo/100f,rv=reverb/100f;
                float threshold=noise/100f*0.035f;
                double energy=0;
                for(int i=0;i<n;i++){
                    float raw=input[i]/32768f;
                    energy+=raw*raw;
                    float x=Math.abs(raw)>=threshold?raw*g:0f;
                    int d1=(index+rate-8640)%rate;
                    int d2=(index+rate-2352)%rate;
                    int d3=(index+rate-6192)%rate;
                    float echoTap=delays[d1];
                    float roomTap=0.57f*rooms[d2]+0.43f*rooms[d3];
                    delays[index]=clamp(x+echoTap*0.23f);
                    rooms[index]=clamp(x+roomTap*0.28f);
                    float mixed=clamp(x+e*0.56f*echoTap+rv*0.42f*roomTap);
                    output[i]=(short)(mixed*32767f);
                    index++;if(index==rate)index=0;
                }
                int sent=0;
                while(running && sent<n){
                    int wrote=p.write(output,sent,n-sent,AudioTrack.WRITE_BLOCKING);
                    if(wrote<=0){if(!running)break;throw new IllegalStateException("Không thể phát tiếng");}
                    sent+=wrote;
                }
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastMeter>90){
                    final int meter=Math.min(100,(int)(Math.sqrt(energy/n)*750));
                    runOnUiThread(()->{if(running)level.setProgress(meter);});
                    lastMeter=now;
                }
            }
        }catch(Exception ex){
            final String msg=ex.getMessage()==null?"Lỗi âm thanh":ex.getMessage();
            runOnUiThread(()->status.setText(msg));
        }finally{
            running=false;
            AudioRecord r=recorder;recorder=null;
            AudioTrack p=player;player=null;
            if(r!=null){try{r.stop();}catch(Exception ignored){}try{r.release();}catch(Exception ignored){}}
            if(p!=null){try{p.pause();}catch(Exception ignored){}try{p.release();}catch(Exception ignored){}}
            runOnUiThread(()->{
                toggle.setText("BẬT MICRO");toggle.setBackground(background(CYAN,15));
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }
    private static float clamp(float x){return Math.max(-1f,Math.min(1f,x));}
    @Override protected void onStop(){stopMic();super.onStop();}
    @Override protected void onDestroy(){stopMic();super.onDestroy();}
}
