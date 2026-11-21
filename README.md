# AI_TALK — Na Na Android Voice Assistant & Companion

Ứng dụng Android thuần (`com.adwewecat.nana`) tối ưu cho **iQOO Z10 Turbo Pro (OriginOS 6 / Android 16)** kết hợp trợ lý giọng nói rảnh tay kích hoạt bằng khẩu lệnh **`"Na Na"`** và bạn đồng hành tâm sự tiếng Việt tự nhiên.

## Tính năng chính
- **Khẩu lệnh `"Na Na"` chạy nền & bảo vệ nhạc Bluetooth:** Foreground Service dùng micro điện thoại (`TYPE_BUILTIN_MIC`) để không làm giảm chất lượng nhạc A2DP trên tai nghe Bluetooth, kèm Quick Settings Tile và nút nói nhanh.
- **Xoay vòng đa API Key & cơ chế `"Na Na → Em gái"`:** Tự động hỗ trợ **Gemini Pro (`AQ.` Vertex AI Express & `AIza` AI Studio)**, **Claude**, **ChatGPT** và **Key miễn phí**; khi hết key trả phí Na Na báo mệt tự nhiên và khi bạn gọi lại sẽ hỏi *"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"* — nói `"ok"` để chuyển ngay sang **Em gái** dùng chung trí nhớ.
- **Giọng nữ dễ thương tách biệt sức khỏe với Chat:** Ưu tiên giọng neural Gemini TTS / OpenAI TTS kèm fallback tiếng Việt cục bộ và cache sẵn câu báo mệt ngay trên máy; lỗi giọng nói không bao giờ làm gián đoạn key chat.
- **Điều khiển máy & Cuộc gọi (SIM + Zalo/Messenger/Telegram):** Mở YouTube tìm & tự bấm phát nhạc/video (`AccessibilityService`), bắt/tắt cuộc gọi qua 3 tầng (*Thông báo cuộc gọi* → `TelecomManager` → `Accessibility`) mà **giữ nguyên trình gọi gốc OriginOS** để bảo toàn ghi âm 2 chiều.
- **Ghi âm cuộc gọi & Đồng bộ Google Drive chỉ qua Wi-Fi:** Tự quét thư mục `Record/Call` của OriginOS (kèm chế độ ghi âm dự phòng trong app), lưu tạm cục bộ rồi chỉ đồng bộ lịch sử chat, ký ức sở thích/thói quen và file ghi âm lên Google Drive (`AI_TALK_NaNa/`) khi có **Wi-Fi không giới hạn** — tự ngắt tức thì khi rớt Wi-Fi hoặc sang 4G/5G, khôi phục nguyên vẹn khi cài lại app.
- **Bảo mật tuyệt đối:** Không lưu cứng bất kỳ API key hay Google OAuth secret nào trong mã nguồn; toàn bộ key nhập trong app và mã hóa bằng Android Keystore (`EncryptedSharedPreferences`).

## Cài đặt & Build
Xem hướng dẫn chi tiết từng bước cài lên máy iQOO, nhập key AI và thiết lập Google Drive OAuth tại [SETUP_ANDROID.md](SETUP_ANDROID.md).

```bash
# Chạy toàn bộ bộ kiểm thử tự động (70 tests: :core + :app Robolectric)
./gradlew :core:test :app:testDebugUnitTest

# Biên dịch file APK cài máy Android
./gradlew :app:assembleDebug
```
