# Hướng dẫn cài đặt & cấu hình Na Na (`AI_TALK`) trên Android (iQOO Z10 Turbo Pro · OriginOS 6)

> **Bảo mật tuyệt đối:** Không bao giờ gắn cứng API key hay OAuth secret vào mã nguồn, file test, hoặc commit lên Git. Mọi API key đều được nhập trực tiếp trong app và mã hóa trên máy bằng **Android Keystore (`EncryptedSharedPreferences`)**.

---

## 1. Lấy file APK và cài lên điện thoại

### Cách A — Tải APK tự động từ GitHub Actions (khuyên dùng)
1. Push code lên nhánh `main` (hoặc chạy workflow thủ công trong tab **Actions** -> **Android Debug APK & Tests**).
2. Khi workflow chạy xanh, mở run mới nhất và tải artifact **`nana-debug-apk`** (`app-debug.apk`).
3. Chuyển file `app-debug.apk` vào máy **iQOO Z10 Turbo Pro**, mở bằng Trình quản lý tệp và chọn **Cài đặt** (cho phép cài ứng dụng từ nguồn không xác định cho Trình quản lý tệp nếu được hỏi).

### Cách B — Build APK trực tiếp trên máy tính
1. Sao chép `local.properties.example` thành `local.properties` và trỏ `sdk.dir` tới Android SDK (API 35, Build-Tools 35.0.0).
2. Chạy lệnh:
   ```bash
   ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
   ```
   (Trên Windows dùng `.\gradlew.bat ...`)
3. File APK sinh ra tại: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 2. Nhập API Key trong app (Tab **Cài đặt** -> **Kho API Key**)

Mở app **Na Na** -> tab **Cài đặt** -> mục **Kho API Key (Mã hóa trên máy)**:

1. **Gemini Pro #1 & Gemini Pro #2** *(ưu tiên chạy chính cho Na Na)*:
   - Bấm nút mẫu **`+ Gemini Pro #1`** (và **`+ Gemini Pro #2`** nếu có key thứ 2).
   - Dán API key của bạn:
     - Nếu key bắt đầu bằng **`AQ.`** -> app tự động định tuyến qua **Google Vertex AI Express** (`aiplatform.googleapis.com`).
     - Nếu key bắt đầu bằng **`AIza`** -> app tự động định tuyến qua **Google AI Studio** (`generativelanguage.googleapis.com`).
   - Model mặc định gợi ý: `gemini-2.5-flash`.
   - Bấm **Kiểm tra key** để xác nhận kết nối thành công.
2. **Free Key cho chế độ Em gái** *(khi cả 2 key chính hết quota)*:
   - Bấm **`+ Free Key`**, nhập Gemini Free key (hoặc bật checkbox **Dành cho Em gái (Free tier)** cho key OpenAI-compatible miễn phí).
   - Model gợi ý: `gemini-2.5-flash-lite`.
   - Khi hết quota key chính, Na Na sẽ báo mệt và hỏi anh bằng giọng nói tiếng Việt có muốn chuyển sang Em gái nói chuyện tiếp không.
3. **Claude & ChatGPT** *(tùy chọn dự phòng)*:
   - Bấm **`+ Claude`** (model gợi ý `claude-sonnet-4-5`) hoặc **`+ ChatGPT`** (model gợi ý `gpt-4o-mini`).
   - ChatGPT còn hỗ trợ Whisper STT (`whisper-1`) và OpenAI TTS (`tts-1`) làm lớp dự phòng.
4. **Key TTS riêng** *(tùy chọn)*:
   - Bấm **`+ Key TTS riêng`** nếu muốn tách riêng hạn mức đọc giọng nói Neural TTS mà không ảnh hưởng key chat.
5. Khi key hết cooldown hoặc vừa thêm key mới, bấm **Hồi sức Na Na ngay** để reset cooldown và đưa Na Na trở lại ngay.

---

## 3. Cấu hình Google Cloud OAuth Client ID (Đồng bộ & Khôi phục Google Drive)

App đồng bộ lịch sử trò chuyện, ký ức (`MemoryFact`) và ghi âm cuộc gọi lên thư mục **`AI_TALK_NaNa/`** trên Google Drive cá nhân **chỉ khi kết nối Wi-Fi không giới hạn (Strict Wi-Fi-Only)**.

1. Truy cập [Google Cloud Console](https://console.cloud.google.com/) -> tạo hoặc chọn Project -> bật **Google Drive API**.
2. Vào **APIs & Services -> OAuth consent screen**:
   - Chọn **External** (nếu dùng Gmail cá nhân), điền tên app `Na Na`.
   - Thêm scope: `https://www.googleapis.com/auth/drive.file`, `openid`, `email`, `profile`.
   - Thêm email Gmail của bạn vào **Test users** (lưu ý: ở chế độ Testing, Refresh Token sẽ hết hạn sau 7 ngày; khi đó banner **`Đăng nhập lại Google Drive`** sẽ hiện ngay trên màn hình chính để bạn đăng nhập lại bằng 1 chạm mà không mất dữ liệu trên máy).
3. Vào **APIs & Services -> Credentials -> Create Credentials -> OAuth client ID**:
   - Chọn Application type: **Android**.
   - Package name: `com.adwewecat.nana`.
   - SHA-1 certificate fingerprint: lấy từ keystore ký APK của bạn (với debug keystore mặc định chạy `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
   - Sao chép **Client ID** dạng `xxxxxxxxxxxx-yyyyyyyyyyyy.apps.googleusercontent.com`.
4. Cung cấp Client ID lúc build (tuyệt đối không commit vào repo):
   - **Trên GitHub Actions:** vào **Repository Settings -> Secrets and variables -> Actions -> New repository secret**, tạo secret tên **`GOOGLE_OAUTH_CLIENT_ID`**.
   - **Khi build local:** thêm dòng `GOOGLE_OAUTH_CLIENT_ID=...` vào file `local.properties` (đã nằm trong `.gitignore`).
5. Trong app -> tab **Cài đặt** -> **Google Drive & Ghi âm** -> bấm **Đăng nhập Google Drive** (hoặc **Khôi phục từ Drive** khi cài lại máy mới).

---

## 4. Cấp quyền & tối ưu chạy nền cho OriginOS 6 (iQOO Z10 Turbo Pro)

Vào tab **Cài đặt** -> **Checklist OriginOS 6 (iQOO)** và bấm lần lượt từng mục:

1. **Micro & Thông báo:** Cho phép `RECORD_AUDIO` và `POST_NOTIFICATIONS` để Na Na lắng nghe và hiển thị điều khiển nhanh.
2. **Điện thoại & Danh bạ:** Cho phép `READ_PHONE_STATE`, `CALL_PHONE`, `READ_CONTACTS`, `READ_MEDIA_AUDIO` để gọi theo tên danh bạ và nhận biết khi cuộc gọi kết thúc (hoàn toàn không yêu cầu quyền Trợ năng hay Lắng nghe thông báo, an toàn 100% với ứng dụng ngân hàng).
3. **Chọn thư mục ghi âm `Record/Call`:**
   - Trong app iQOO **Điện thoại -> Cài đặt ghi âm cuộc gọi**, bật ghi âm cuộc gọi tự động nếu muốn lưu mọi cuộc gọi.
   - Mở tab **Ghi âm** hoặc **Cài đặt** trong Na Na -> bấm **Chọn thư mục OriginOS `Record/Call`** -> cấp quyền thư mục `Record/Call` qua trình chọn thư mục hệ thống (SAF).
   - Sau mỗi cuộc gọi, app đợi 2 giây cho file ghi âm đóng hoàn tất rồi tự copy vào vùng đệm và chờ Wi-Fi để tải lên Drive (không dùng 4G/5G).
4. **Chống đóng app chạy nền trên OriginOS 6 (rất quan trọng):**
   - **Bỏ qua tối ưu pin:** chọn Na Na -> đặt mức **Không hạn chế (Don't optimize / Unrestricted)**.
   - **Tự khởi chạy & Chạy nền cao (iQOO Security / Pin):** bấm **Mở** tại dòng *Tự khởi chạy & Chạy nền cao* -> bật **Autostart** và cho phép **Tiêu thụ pin nền cao (High background power consumption)** cho Na Na.
   - *(Mẹo thêm trên OriginOS 6)*: Vuốt lên mở màn hình Đa nhiệm -> kéo nhẹ thẻ app Na Na xuống hoặc bấm menu góc thẻ -> chọn **Khóa (Lock)** để giữ app không bị dọn RAM.
   - *(Ghi chú pin)*: Tùy chọn **Giữ CPU thức khi tắt màn hình** mặc định **TẮT** để tiết kiệm pin; chỉ bật nếu bạn muốn gọi `"Na Na"` khi màn hình tắt lâu.

---

## 5. Wake-word offline `"Na Na"` (Vosk tiếng Việt)

- Engine wake-word offline sử dụng thư mục model tại `/data/data/com.adwewecat.nana/files/vosk-model-vn/` (chỉ thu âm qua mic tích hợp của điện thoại `TYPE_BUILTIN_MIC` để giữ tai nghe Bluetooth ở chế độ nghe nhạc A2DP chất lượng cao).
- Nếu chưa chép model Vosk vào thư mục trên, app tự động degrade an toàn mà không lỗi — bạn vẫn có thể dùng **nút Mic lớn (Push-to-Talk)**, **ô Quick Settings Tile `Na Na`**, **nút trợ lý hệ thống**, hoặc **gõ tin nhắn trực tiếp** trên tab **Na Na**.
