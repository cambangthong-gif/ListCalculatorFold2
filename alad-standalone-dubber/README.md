# ALAD Dub Standalone v1.0

Extension độc lập, không cần ALAD Windows hay localhost bridge.

## YouTube

Với video YouTube công khai, extension gửi trực tiếp URL video đến Gemini 3.8 Flash theo từng cửa sổ khoảng 90 giây. Gemini trả các đoạn thoại đã dịch với timestamp. Extension cache các đoạn này, đọc bằng SpeechSynthesis của trình duyệt và đồng bộ theo `video.currentTime`.

- Có nút **ALAD/DUB** ngay trong thanh điều khiển YouTube.
- Pause / seek / playbackRate được đồng bộ.
- Tự giảm âm lượng video khi giọng dub đang nói.
- Hiện song ngữ trên video nếu bật.
- Cache bản dịch để lần sau không phải tạo lại đoạn đã có.
- Không cần caption của YouTube trong chế độ YouTube AI.

## Trang khác

Extension thử đọc HTML5 `TextTrack` hoặc caption đang hiện trong DOM, gửi từng câu sang Gemini để dịch rồi đọc.

## Cài đặt

1. Giải nén ZIP.
2. Mở `chrome://extensions` hoặc `edge://extensions`.
3. Bật **Developer mode**.
4. Chọn **Load unpacked** và chọn thư mục extension.
5. Bấm icon ALAD Dub để mở Side Panel.
6. Nhập Gemini API key của riêng bạn, chọn tiếng và giọng.
7. Mở video rồi bấm **Bắt đầu lồng tiếng** hoặc nút **ALAD** trong player YouTube.

API key được lưu bằng `chrome.storage.local` nếu bật “Nhớ key”, hoặc `chrome.storage.session` nếu bỏ chọn.

## Giới hạn v1

- YouTube URL input của Gemini chỉ dùng được với video công khai.
- Video private/unlisted/DRM không đi qua YouTube AI mode.
- Giọng đọc v1 dùng voice do Windows/Chrome/Edge cung cấp; chưa cache audio TTS thành file.
- Universal mode phụ thuộc trang có caption/TextTrack mà content script đọc được.
