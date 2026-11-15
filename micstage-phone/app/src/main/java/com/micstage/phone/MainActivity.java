package com.micstage.phone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;

public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1423, PANEL=0xFF192A42, TXT=0xFFF0F6FF, SECOND=0xFFABBDCF, CYAN=0xFF22D3EE, GREEN=0xFF34D399;
    private volatile boolean running=false;
    private volatile int gain=55, echo=0, reverb=0, noise=0;
    private volatile int inputMode=0; // 0=automatic, 1=phone mic, 2=headset/USB mic
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private TextView status;
    private TextView sourceLabel;
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
        TextView hint=label("Giọng hát phát qua thiết bị nghe đang kết nối. Bản 1.1 ưu tiên âm sạch và hạn chế vỡ tiếng.",14,SECOND,false);
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
        LinearLayout inputBox=new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.VERTICAL);
        inputBox.setPadding(dp(16),dp(14),dp(16),dp(14));
        inputBox.setBackground(background(PANEL,16));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);
        ip.topMargin=dp(17);
        c.addView(inputBox,ip);
        inputBox.addView(label("Nguồn thu micro",15,TXT,true));
        Spinner selector=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
            new String[]{"Tự động", "Micro điện thoại", "Micro tai nghe / USB"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selector.setAdapter(adapter);
        selector.setBackground(background(0xFFE7EEF7,9));
        LinearLayout.LayoutParams sip=new LinearLayout.LayoutParams(-1,dp(48));
        sip.topMargin=dp(9);inputBox.addView(selector,sip);
        selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int pos,long id){
                inputMode=pos;
                if(running)sourceLabel.setText("Đổi nguồn micro: hãy tắt rồi bật lại.");
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        sourceLabel=label("Nguồn thu: tự động",12,SECOND,false);
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2);slp.topMargin=dp(8);
        inputBox.addView(sourceLabel,slp);
        addSlider(c,"Âm lượng micro (nên giữ 40–70%)",250,gain,0);
        addSlider(c,"Echo • tiếng vọng",100,echo,1);
        addSlider(c,"Reverb • độ vang",100,reverb,2);
        addSlider(c,"Lọc tiếng ồn",100,noise,3);
        TextView warn=label("Nếu vẫn rè: chọn đúng nguồn micro, để Echo và Reverb 0%, hạ âm lượng micro. Lưu ý: micro tai nghe có thể bị quá tải khi hát sát miệng. Bluetooth có thể trễ hoặc giảm chất lượng khi dùng mic Bluetooth.",13,SECOND,false);
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
            AudioRecord r=createAudioRecord(capture,Math.max(minIn,rate/10));
            selectPreferredInput(r);
            if(r.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Không mở được micro");
            recorder=r;
            AudioTrack p=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(playback).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(Math.max(minOut,rate/10)).build();
            if(p.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("Không mở được loa");
            p.setVolume(0.95f);
            player=p;
            if(!running)return;
            p.play();r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Micro bị hệ thống chặn");
            AudioDeviceInfo routedInput=r.getRoutedDevice();
            final String routeText=routedInput==null?"Nguồn thu: không xác định":
                "Nguồn thu đang dùng: "+inputDeviceName(routedInput);
            runOnUiThread(()-> {if(running){status.setText("Micro đang hoạt động");sourceLabel.setText(routeText);}});
            short[] input=new short[480],output=new short[480];
            float[] delays=new float[rate],rooms=new float[rate];
            int index=0;long lastMeter=0;
            float hpPrevX=0, hpPrevY=0, gateEnvelope=1f, smoothGain=gain/100f, smoothEcho=0f, smoothReverb=0f, limiterGain=1f;
            while(running){
                int n=r.read(input,0,input.length,AudioRecord.READ_BLOCKING);
                if(n<0){if(!running)break;throw new IllegalStateException("Lỗi đọc micro: "+n);}
                if(n==0)continue;
                float g=gain/100f, e=echo/100f,rv=reverb/100f;
                float threshold=noise/100f*0.025f;
                double energy=0;
                int clippedSamples=0;
                for(int i=0;i<n;i++){
                    float raw=input[i]/32768f;
                    energy+=raw*raw;
                    if(Math.abs(raw)>0.985f)clippedSamples++;
                    // 80 Hz first-order high-pass to reduce rumble and DC offset
                    float hp=raw-hpPrevX+0.9895f*hpPrevY;
                    hpPrevX=raw;hpPrevY=hp;
                    float wantedGate=(threshold<=0 || Math.abs(hp)>threshold)?1f:0f;
                    float gateSpeed=wantedGate>gateEnvelope?0.025f:0.0018f;
                    gateEnvelope+=(wantedGate-gateEnvelope)*gateSpeed;
                    // Smooth real-time parameter changes to avoid clicks
                    smoothGain+=(g-smoothGain)*0.0018f;
                    smoothEcho+=(e-smoothEcho)*0.0018f;
                    smoothReverb+=(rv-smoothReverb)*0.0018f;
                    float x=hp*gateEnvelope*smoothGain;
                    int d1=(index+rate-8640)%rate;
                    int d2=(index+rate-2352)%rate;
                    int d3=(index+rate-6192)%rate;
                    float echoTap=delays[d1];
                    float roomTap=0.57f*rooms[d2]+0.43f*rooms[d3];
                    delays[index]=limitFeedback(x+echoTap*0.20f);
                    rooms[index]=limitFeedback(x+roomTap*0.25f);
                    float mixed=x+smoothEcho*0.52f*echoTap+smoothReverb*0.39f*roomTap;
                    // Fast attack / slow release output limiter, then rounded soft saturation.
                    float peak=Math.abs(mixed);
                    float desiredGain=peak>0.82f?0.82f/peak:1f;
                    limiterGain+=(desiredGain-limiterGain)*(desiredGain<limiterGain?0.25f:0.001f);
                    output[i]=(short)(softLimit(mixed*limiterGain)*32767f);
                    index++;if(index==rate)index=0;
                }
                int sent=0;
                while(running && sent<n){
                    int wrote=p.write(output,sent,n-sent,AudioTrack.WRITE_BLOCKING);
                    if(wrote<=0){if(!running)break;throw new IllegalStateException("Không thể phát tiếng");}
                    sent+=wrote;
                }
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-lastMeter>180){
                    final int meter=Math.min(100,(int)(Math.sqrt(energy/n)*750));
                    final boolean inputClipped=clippedSamples>n/25;
                    runOnUiThread(()->{
                        if(running){
                            level.setProgress(meter);
                            if(inputClipped)status.setText("Micro đầu vào quá lớn – nói/hát xa mic hơn");
                            else status.setText("Micro đang hoạt động");
                        }
                    });
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
    private static float limitFeedback(float x){return Math.max(-1.4f,Math.min(1.4f,x));}
    private static float softLimit(float x){
        float a=Math.abs(x);
        if(a<=0.72f)return x;
        float y=0.72f+0.25f*(1f-(float)Math.exp(-(a-0.72f)/0.25f));
        return x<0?-y:y;
    }
    private AudioRecord createAudioRecord(AudioFormat format,int size){
        int desired=android.os.Build.VERSION.SDK_INT>=29?
            MediaRecorder.AudioSource.VOICE_PERFORMANCE:MediaRecorder.AudioSource.VOICE_RECOGNITION;
        try{
            AudioRecord ar=new AudioRecord.Builder().setAudioSource(desired)
                .setAudioFormat(format).setBufferSizeInBytes(size).build();
            if(ar.getState()==AudioRecord.STATE_INITIALIZED)return ar;
            ar.release();
        }catch(Exception ignored){}
        return new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format).setBufferSizeInBytes(size).build();
    }
    private void selectPreferredInput(AudioRecord ar){
        if(inputMode==0)return;
        AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(am==null)return;
        for(AudioDeviceInfo device:am.getDevices(AudioManager.GET_DEVICES_INPUTS)){
            int type=device.getType();
            boolean builtin=type==AudioDeviceInfo.TYPE_BUILTIN_MIC;
            boolean external=(type==AudioDeviceInfo.TYPE_WIRED_HEADSET
                ||type==AudioDeviceInfo.TYPE_USB_DEVICE
                ||type==AudioDeviceInfo.TYPE_USB_HEADSET);
            if((inputMode==1&&builtin)||(inputMode==2&&external)){
                ar.setPreferredDevice(device);return;
            }
        }
        runOnUiThread(()->sourceLabel.setText("Không thấy nguồn micro đã chọn, dùng mặc định"));
    }
    private String inputDeviceName(AudioDeviceInfo d){
        switch(d.getType()){
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:return "Micro điện thoại";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "Micro tai nghe có dây";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:return "Micro USB";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return "Micro Bluetooth (chế độ thoại)";
            default:return d.getProductName().toString();
        }
    }
    @Override protected void onStop(){stopMic();super.onStop();}
    @Override protected void onDestroy(){stopMic();super.onDestroy();}
}
