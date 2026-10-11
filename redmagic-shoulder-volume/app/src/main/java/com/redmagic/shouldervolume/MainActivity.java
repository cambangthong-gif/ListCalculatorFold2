package com.redmagic.shouldervolume;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private final int bg = Color.rgb(11, 17, 31);
    private final int panelColor = Color.rgb(24, 34, 54);
    private final int muted = Color.rgb(179, 195, 211);
    private LinearLayout content;
    private TextView state, bridge, events, stepLabel;
    private Button toggle;
    private android.content.SharedPreferences prefs;

    private int px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
    private GradientDrawable panel() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(panelColor);
        d.setCornerRadius(px(17));
        return d;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, px(4), 0, px(6));
        return t;
    }
    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(px(17),px(14),px(17),px(14));
        l.setBackground(panel());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin = px(12);
        content.addView(l,p);
        return l;
    }
    private Button button(String label, LinearLayout parent, Runnable callback) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(14);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(50));
        lp.topMargin = px(7);
        parent.addView(b,lp);
        b.setOnClickListener(v -> callback.run());
        return b;
    }
    private void copy(String value) {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("ADB", value));
        Toast.makeText(this,"Đã sao chép lệnh",Toast.LENGTH_SHORT).show();
    }
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(TriggerService.PREF, MODE_PRIVATE);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(18),px(20),px(18),px(28));
        scroll.addView(content);
        setContentView(scroll);

        content.addView(text("REDMAGIC 8 PRO",13,0xFF80D6FF,true));
        content.addView(text("Nút vai điều chỉnh âm lượng",24,Color.WHITE,true));
        content.addView(text("Không Game Space · Không Shizuku · Không root",13,muted,false));

        LinearLayout status = section();
        status.addView(text("Trạng thái",18,Color.WHITE,true));
        state = text("",14,muted,false);
        bridge = text("",14,muted,false);
        events = text("",13,muted,false);
        status.addView(state);status.addView(bridge);status.addView(events);
        toggle = button("Bật nút vai",status,() -> {
            boolean enabled = TriggerService.isEnabled(this);
            if (!enabled && !TriggerService.canWrite(this)) {
                Toast.makeText(this,"Cần cấp WRITE_SECURE_SETTINGS bằng ADB",Toast.LENGTH_LONG).show();
                return;
            }
            try { TriggerService.setEnabled(this,!enabled); }
            catch (RuntimeException ex) {
                Toast.makeText(this,"Không khởi động được dịch vụ: " + ex.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
            }
            refresh();
        });

        LinearLayout mapping = section();
        mapping.addView(text("Ánh xạ nút",18,Color.WHITE,true));
        mapping.addView(text("◀ Nút vai trái: Giảm âm lượng",15,Color.WHITE,false));
        mapping.addView(text("▶ Nút vai phải: Tăng âm lượng",15,Color.WHITE,false));
        stepLabel = text("",14,muted,false);
        mapping.addView(stepLabel);
        SeekBar step = new SeekBar(this);
        step.setMax(2);
        step.setProgress(prefs.getInt("step",1)-1);
        mapping.addView(step);
        step.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser) {
                if (fromUser) prefs.edit().putInt("step",progress+1).apply();
                stepLabel.setText("Mỗi lần nhấn: " + (progress+1) + " nấc âm lượng");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        button("Thử giảm âm",mapping,()->sendTest("LEFT"));
        button("Thử tăng âm",mapping,()->sendTest("RIGHT"));

        LinearLayout adb = section();
        adb.addView(text("Thiết lập lần đầu",18,Color.WHITE,true));
        adb.addView(text("1. Cài APK. Mở ứng dụng ít nhất một lần.\n"
                +"2. Bật Gỡ lỗi USB trên điện thoại.\n"
                +"3. Cấp quyền hệ thống bằng ADB.\n"
                +"4. Chạy cầu nối redmagic-bridge.sh từ ADB.\n"
                +"5. Quay lại ứng dụng và bấm Bật nút vai.",14,muted,false));
        button("Sao chép lệnh cấp quyền",adb,
                ()->copy("adb shell pm grant com.redmagic.shouldervolume android.permission.WRITE_SECURE_SETTINGS"));
        button("Sao chép lệnh kiểm tra phím F7/F8",adb,
                ()->copy("adb shell getevent -l"));
        adb.addView(text("Lưu ý: cầu nối shell có thể phải chạy lại sau mỗi lần khởi động. "
                +"Nếu máy không phát F7/F8 ngoài Game Space, bản thử nghiệm này chưa thể xử lý.",12,0xFFFFD17A,false));
        refresh();
    }
    private void sendTest(String side) {
        Intent i = new Intent(TriggerReceiver.ACTION);
        i.setClass(this,TriggerReceiver.class);
        i.putExtra("key",side);
        sendBroadcast(i);
        refresh();
    }
    private void refresh() {
        boolean hasPermission = TriggerService.canWrite(this);
        boolean enabled = TriggerService.isEnabled(this);
        state.setText("Quyền ADB: "+(hasPermission?"Đã cấp":"Chưa cấp")
                +"  ·  Cảm biến: "+(enabled?"Đang bật":"Đang tắt"));
        toggle.setText(enabled ? "Tắt nút vai" : "Bật nút vai");
        long t = prefs.getLong("last_bridge",0);
        bridge.setText("Cầu nối ADB: "+(t == 0?"Chưa nhận kết nối":"Đã nhận lúc "+fmt(t)));
        long e = prefs.getLong("last_key",0);
        String side = prefs.getString("last_side","");
        events.setText("Phím cuối: "+(e==0?"Chưa có":((side.equals("LEFT")?"Trái":"Phải")+" · "+fmt(e))));
        if(stepLabel!=null) stepLabel.setText("Mỗi lần nhấn: "+prefs.getInt("step",1)+" nấc âm lượng");
    }
    private String fmt(long ms) { return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(ms)); }
    @Override protected void onResume() { super.onResume(); if (state!=null) refresh(); }
}
