# List Calculator Fold 2

Bản Android native viết mới, tối ưu cho màn hình gập và không phụ thuộc web.

## Chức năng hiện có
- Bảng tính nhiều dòng, tự tính tổng.
- Bảng hủy vé: Tên đại lý + Số lượng + TỔNG.
- Tạo/đổi tên/xóa nhóm bảng, tính Tổng nhóm.
- Nhấn giữ biểu tượng ☰ để kéo đổi thứ tự hoặc kéo bảng sang nhóm khác.
- Đổi tên/xóa/chuyển nhóm từng bảng.
- Rung nhẹ khi thao tác các nút chính.
- Lưu dữ liệu cục bộ bằng SharedPreferences + JSON.
- Chia sẻ toàn bộ hoặc từng bảng dưới dạng PNG được dựng ngay trong app.
- Intent chia sẻ chỉ chứa ảnh PNG, không chèn link và không chèn EXTRA_TEXT.
- Package riêng `com.vinh.listcalculatorfold2` nên có thể cài song song với bản cũ.

## Build
Mở thư mục bằng Android Studio có Android SDK 35, để Gradle sync rồi Build APK.
