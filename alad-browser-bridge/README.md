# ALAD Universal Browser Bridge v1.1

Dùng với **ALAD Windows v6.1 Universal Browser**.

## Trang hỗ trợ

Bridge không còn giới hạn YouTube. Nó tự tìm player HTML5 trên trang:

- YouTube
- X / Twitter
- Facebook
- TikTok web
- Vimeo
- các trang phim/video dùng HTML5 player
- audio player HTML5

Nếu player có **TextTrack/captions** hoặc phụ đề hiển thị trong DOM, ALAD ưu tiên phụ đề để dịch.
Nếu không tìm thấy phụ đề, **ALAD tự chuyển sang Live Audio fallback**.

Một số player DRM, canvas hoặc iframe đặc biệt có thể không cho extension đọc phụ đề/timeline; trong trường hợp đó phần Live Audio vẫn hoạt động.

## Cài Chrome / Edge

1. Giải nén ZIP.
2. Chrome: `chrome://extensions` — Edge: `edge://extensions`.
3. Bật **Developer mode**.
4. Chọn **Load unpacked / Tải tiện ích đã giải nén**.
5. Chọn thư mục `alad-browser-bridge`.

Chrome/Edge sẽ cảnh báo tiện ích có quyền đọc dữ liệu trên các trang web vì chế độ Universal cần phát hiện player/caption trên nhiều trang. Bridge chỉ gửi trạng thái player/caption về `127.0.0.1:37921` trên máy đang chạy ALAD.

## Dùng

1. Mở ALAD Windows v6.1.
2. Chọn nguồn **Hybrid Browser — phụ đề + sync + Live fallback**.
3. Bấm **Bắt đầu**.
4. Mở trang có video/audio.

Trạng thái:
- `Hybrid: phụ đề` = đang ưu tiên caption/text track.
- `Hybrid: không có phụ đề · Live audio fallback` = đang nghe audio trực tiếp.
