# APK cập nhật đè bản cũ

Bản v2.5.5 dùng workflow `Build stable update APK`.

Workflow này cài Gradle 8.9 trực tiếp bằng `gradle/actions/setup-gradle@v4`,
vì vậy KHÔNG cần `gradlew`, `gradlew.bat` hay thư mục `gradle/wrapper`
trong repo để build stable APK.

Điều kiện Android cho phép cài đè:
1. applicationId giữ nguyên: `com.vinh.listcalculatorfold2`
2. versionCode bản mới cao hơn: v2.5.5 = 255
3. mọi bản release dùng cùng một khóa ký

Repository secrets cần có:
- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Không commit file `.jks`, `GITHUB_SECRETS.txt`, Base64 hoặc mật khẩu lên repo public.

Sau khi upload source:
Actions → Build stable update APK → Run workflow → main → Run workflow.
APK nằm trong artifact `ListCalculatorFold2-v2.5.5-signed`.
