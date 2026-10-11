# RedMagic 8 Pro — Nút vai chỉnh âm lượng

**Bản thử nghiệm kỹ thuật 0.1 dành cho RedMagic 8 Pro / RedMagic OS 10.**

Cài như một ứng dụng riêng (`com.redmagic.shouldervolume`). Không dùng Shizuku, không Game Space và không yêu cầu root. Hai nút vai dự kiến gán:
- **Nút vai trái** → giảm âm lượng media.
- **Nút vai phải** → tăng âm lượng media.

⚠️ **CHƯA ĐƯỢC KIỂM NGHIỆM TRÊN MÁY REDMAGIC 8 PRO OS 10.** Không có bảo đảm firmware này gửi phím F7/F8 ngoài Game Space. Việc *kích hoạt* cảm biến và việc *nhận sự kiện nút* là hai công đoạn độc lập.

## Vì sao phải dùng ADB?

Ứng dụng Android thông thường không có quyền đọc `/dev/input/event*` khi RedMagic game service đã nhận phím vai. Chỉ cấp `WRITE_SECURE_SETTINGS` qua ADB có thể giúp ứng dụng duy trì `nubia_game_scene=1`, **không tự cho phép đọc phím**.

Giải pháp này dùng một shell bridge khởi động qua ADB với quyền người dùng `shell` để gọi `getevent -l`, phát hiện `KEY_F7` / `KEY_F8` và gửi lệnh tới APK. Máy bình thường không cho tự khởi chạy shell bridge sau reboot, nên thường phải chạy lệnh lại sau khi khởi động.

## Cài đặt

1. Từ Android Studio hoặc Gradle tạo bản `app-debug.apk` (xem mục build bên dưới).
2. Cài APK lên RedMagic 8 Pro. **Mở app ít nhất một lần.**
3. Bật Developer options → USB debugging. Kết nối với máy tính và cho phép máy tính điều khiển ADB.
4. Nếu dùng Windows: chạy `START_BRIDGE_WINDOWS.bat` (cần `adb` có trong PATH). Nếu Linux/macOS: `sh start_bridge_linux_mac.sh`.
5. Trở lại ứng dụng. Kiểm tra dòng quyền ADB đã cấp, dòng `Cầu nối ADB` đã nhận PING.
6. Bấm **Bật nút vai**. Chạm nút vai trái/phải và quan sát thanh âm lượng.

### Các lệnh ADB thủ công

```sh
adb shell pm grant com.redmagic.shouldervolume android.permission.WRITE_SECURE_SETTINGS
adb push scripts/redmagic-bridge.sh /data/local/tmp/redmagic-bridge.sh
adb shell chmod 700 /data/local/tmp/redmagic-bridge.sh
adb shell 'nohup sh /data/local/tmp/redmagic-bridge.sh </dev/null >/data/local/tmp/rm8_shouldervolume.log 2>&1 &'
```

Nếu Android 16/OS 10 chặn foreground service hay quyền ADB, xem lỗi bằng `adb logcat`.

## Kiểm tra nếu bấm nút vai nhưng âm lượng không đổi

- Trong ứng dụng nhấn **Thử giảm âm** / **Thử tăng âm**. Nếu hoạt động, AudioManager và BroadcastReceiver của APK dùng được.
- Kiểm tra bridge: `adb shell cat /data/local/tmp/rm8_shouldervolume.log`.
- Kiểm tra cảm biến: `adb shell getevent -l`. Chạm từng phím vai; nếu có `KEY_F7 DOWN`, `KEY_F8 DOWN`, bridge có đầu vào để chạy. Lệnh có thể chờ đến khi có phím; nhấn Ctrl+C để dừng.
- Nếu không có sự kiện khi ngoài Game Space, không thể suy ra một sửa đổi Android thuần Java sẽ giải quyết được. Cần khảo sát các giao diện Nubia TGK riêng cho RM8/OS10, có thể đòi root hoặc Shizuku.
- Nếu có phím nhưng không nhận âm lượng, cần kiểm tra `am broadcast` hoặc `logcat`, và có thể mức âm lượng đã tới giới hạn.

## Build

Dùng **JDK 17**, Android SDK Platform 35, Android Gradle Plugin 8.7.3, Gradle 8.9+.

```sh
gradle :app:assembleDebug --no-daemon
```

APK sinh ra ở `app/build/outputs/apk/debug/app-debug.apk`.

Gói nguồn không chứa file APK biên dịch sẵn. **Không đổi tên ZIP thành APK** vì sẽ không cài đặt được.

## Thiết kế bảo mật và giới hạn

- APK không có kết nối Internet, không có quảng cáo hoặc thu thập dữ liệu.
- Broadcast receiver nhận lệnh từ shell và là exported, do đó ứng dụng khác trên điện thoại cũng có thể gửi lệnh volume giả. Để dùng riêng cần bổ sung xác thực.
- Shell bridge có thể bị hệ thống dừng khi khóa màn hình hoặc khi khởi động lại. Không tự động cấp quyền root.
- Watchdog can thiệp `nubia_game_scene`, có khả năng ảnh hưởng đến trạng thái game của hãng; tắt app nếu thấy bất thường.
- Chỉ dùng gỡ lỗi USB với máy tính bạn tin cậy và tắt khi không cần.

## Nguồn nghiên cứu

- RedTrigger MIT: https://github.com/zampierilucas/RedTrigger (được báo cáo thử trên RedMagic 11 Pro, không phải RM8).
- Linux keycodes F7/F8 là 0x41 / 0x42.

**Đây là prototype cần kiểm tra thực tế, không phải bản đã xác nhận hoạt động đầy đủ trên OS 10.**
