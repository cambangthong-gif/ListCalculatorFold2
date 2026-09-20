# ALAD Dub Lite v2

Bản này được viết lại theo hướng caption-first để giảm tải máy.

- YouTube: đọc caption track trực tiếp một lần, không quét DOM liên tục.
- Dịch theo batch nhỏ trong background.
- Gemini 3.1 Flash TTS tạo PCM, cache bằng Cache Storage.
- Content script chỉ giữ một scheduler sự kiện dựa trên play/pause/seek/timeupdate.
- Không còn vòng lặp 120 ms, không sort toàn bộ cue liên tục, không MutationObserver toàn trang.
- Nếu video không có caption, chế độ Auto/Ép AI mới dùng Gemini Video để tạo phụ đề theo block 180 giây.
- Trang khác: chỉ dùng HTML5 TextTrack để giữ extension nhẹ. Adapter riêng từng site có thể bổ sung sau.

Cài: giải nén → chrome://extensions hoặc edge://extensions → Developer mode → Load unpacked → chọn thư mục này.
