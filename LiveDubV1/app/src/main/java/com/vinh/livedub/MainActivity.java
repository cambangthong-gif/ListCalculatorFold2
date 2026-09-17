package com.vinh.livedub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        TextView title = new TextView(this);
        title.setText("LiveDub Việt");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(25, 25, 28));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("Lồng tiếng Việt trực tiếp từ âm thanh nội bộ của YouTube, X và ứng dụng media.\n\nBản V1: Anh → Việt • Live Sync • không dùng micro.");
        desc.setTextSize(17);
        desc.setTextColor(Color.DKGRAY);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.setMargins(0, 36, 0, 36);
        root.addView(desc, dp);

        Button start = new Button(this);
        start.setText("BẮT ĐẦU LỒNG TIẾNG");
        start.setTextSize(17);
        start.setAllCaps(false);
        root.addView(start, new LinearLayout.LayoutParams(-1, 150));

        Button stop = new Button(this);
        stop.setText("Dừng");
        stop.setAllCaps(false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 130);
        sp.setMargins(0, 18, 0, 0);
        root.addView(stop, sp);

        status = new TextView(this);
        status.setText("Trạng thái: chưa chạy");
        status.setTextSize(15);
        status.setTextColor(Color.GRAY);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.setMargins(0, 32, 0, 0);
        root.addView(status, stp);

        TextView note = new TextView(this);
        note.setText("Lần đầu Android sẽ hỏi quyền chia sẻ màn hình/âm thanh. Ứng dụng chỉ lấy playback audio, không lưu video. Mô hình dịch Anh→Việt sẽ tải lần đầu qua Internet.");
        note.setTextSize(13);
        note.setTextColor(Color.GRAY);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(0, 40, 0, 0);
        root.addView(note, np);

        setContentView(root);

        start.setOnClickListener(v -> requestCapture());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, LiveDubService.class));
            status.setText("Trạng thái: đã dừng");
        });
    }

    private void requestCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, LiveDubService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("resultData", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Trạng thái: đang khởi động… có thể chuyển sang YouTube/X");
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Trạng thái: chưa cấp quyền capture");
        }
    }
}
