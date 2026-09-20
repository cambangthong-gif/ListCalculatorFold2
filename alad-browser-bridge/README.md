# ALAD Universal Browser Bridge v1.2

Bản sửa cho **ALAD v7.1 Subtitle Dubbing**.

Điểm sửa chính: YouTube dùng player phụ đề riêng, nên nếu nút CC đang tắt thì v1.1 không có caption nào để gửi cho ALAD. v1.2 tự bật CC khi ALAD đang kết nối. Với player HTML5 chuẩn, bridge đưa TextTrack sang chế độ `hidden` để tải cue mà không bắt buộc hiện chữ trên màn hình.

Bridge còn gửi trạng thái `captionStatus` để ALAD báo rõ đang nhận phụ đề hay không.

Cài lại:
1. Xóa/disable bridge v1.1.
2. Giải nén ZIP v1.2.
3. Mở `chrome://extensions` hoặc `edge://extensions`.
4. Bật Developer mode → Load unpacked → chọn thư mục bridge v1.2.
5. Reload tab video sau khi cài.
