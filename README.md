# 🚀 Ins Translator to Phyll v4.5

> **Hệ sinh thái AI Translation & Language Learning** tích hợp Bong bóng trợ lý nổi (*Floating Bubble*), Thẻ dịch nổi thu gọn (*Draggable Floating Card*), Thanh phụ đề ngang kính mờ (*Glass Subtitle Bar*), Sổ tay từ vựng *SRS Flashcards* và *AI Tutor Chat* song ngữ chuyên sâu (Đặc biệt tối ưu cho Tiếng Thái 🇹🇭).

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![SDK](https://img.shields.io/badge/Target%20SDK-34-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)
![AI Engine](https://img.shields.io/badge/AI-Google%20Gemini%20API-orange.svg)

---

## 🌟 Tính Năng Mới Trên v4.5
- ⚡ **Dịch siêu tốc (< 1s)**: Tối ưu Gemini 2.5 Flash tắt thinking latency, prompt tinh gọn, in-memory cache phản hồi tức thì.
- 🫧 **Thẻ dịch nổi gọn nhẹ (Floating Compact Card)**: Không làm tối màn hình (`FLAG_DIM_BEHIND` removed), nhìn rõ 100% ứng dụng chat bên dưới.
- ⠿ **Kéo thả di chuyển tự do**: Vuốt thanh tiêu đề để di chuyển thẻ dịch tránh che ô nhập hay tin nhắn.
- 💡 **Phân tích ngữ pháp thu gọn/mở rộng**: Gập gọn phân tích để màn hình thoáng đãng, chỉ mở ra khi muốn học ngữ pháp.

---

## 🌟 Tính Năng Cốt Lõi

### 1. 🫧 Bong bóng nổi & Thanh phụ đề ngang (Floating Overlays)
- **Bong bóng nổi (Floating Bubble)**: Luôn hiển thị trên màn hình mọi ứng dụng (Instagram, Line, Messenger, v.v.), kéo thả mượt mà, chạm để mở bảng dịch nhanh 2 chiều (Tin đến / Tin đi).
- **Thanh phụ đề kính mờ (Glass Subtitle Bar)**: Thiết kế dạng thanh ngang kính mờ đè phía trên bàn phím/ô chat, cho phép dán và dịch tức thì, tự động copy kết quả vào clipboard hoặc thay thế trực tiếp vào ô nhập.

### 2. 🧠 Động cơ AI Gemini thông minh
- **Duy trì ngữ cảnh & Vai xưng hô**: Tự động đưa lịch sử hội thoại gần nhất vào prompt để AI chọn đại từ xưng hô tự nhiên và nhất quán (anh/em/bạn,...).
- **Văn phong cá nhân hóa (Persona Styles)**: Tùy biến giọng điệu bản dịch: *Thân mật*, *Trang trọng*, *Gen Z*, *Kỹ thuật* hoặc chỉ dẫn tùy chỉnh.
- **Phân tích học tập chuyên sâu**: Mỗi bản dịch đi kèm phiên âm chuẩn (*Latinh/Karaoke/IPA*), bóc tách từng từ vựng và giải thích ngữ pháp chi tiết bằng Tiếng Việt.
- **Dịch ảnh & Chụp màn hình (Multimodal OCR)**: Trích xuất và dịch văn bản trực tiếp từ hình ảnh bằng Gemini Vision.

### 3. 🎓 Trung tâm học ngoại ngữ (Language Learning Hub)
- **🤖 AI Tutor Chat**: Trò chuyện tương tác với gia sư AI, giải thích cặn kẽ ngữ pháp, bảng chữ cái và các điểm ngữ pháp khó.
- **🎴 Flashcards SRS (Spaced Repetition System)**: Lưu nhanh các từ/câu quan trọng vào sổ tay với 5 cấp độ ôn tập ngắt quãng khoa học.
- **🎯 AI Pronunciation Coach**: Thu âm giọng đọc của bạn, AI sẽ đối chiếu với câu mẫu, chấm điểm (0-100) và đưa ra nhận xét cải thiện phát âm.

---

## 🏗️ Cấu Trúc Mã Nguồn

```
app/src/main/
├── AndroidManifest.xml                  # Cấu hình quyền hệ thống (Overlay, Mic, FGS)
└── java/com/phyll/overlaytranslator/
    ├── MainActivity.kt                  # Hub chính (Dashboard, AI Tutor, Flashcards, Settings)
    ├── SettingsActivity.kt              # Màn hình cấu hình chi tiết
    ├── data/
    │   ├── Languages.kt                 # Danh sách 70+ ngôn ngữ
    │   └── SettingsStore.kt             # Quản lý cấu hình & lưu trữ SharedPreferences
    ├── network/
    │   └── GeminiClient.kt              # Tầng kết nối REST API với Google Gemini
    └── overlay/
        ├── OverlayBubbleService.kt      # Foreground Service chạy nền cho bong bóng
        ├── OverlayViewController.kt     # Bộ điều khiển Bong bóng & Bảng dịch nổi
        └── SubtitleBarController.kt     # Bộ điều khiển Thanh phụ đề ngang kính mờ
```

---

## 🛠️ Hướng Dẫn Cài Đặt & Build

### Yêu cầu hệ thống
* Android Studio (Jellyfish / Iguana hoặc mới hơn)
* JDK 17
* Android SDK 34 (hỗ trợ từ Android 8.0 - `minSdk 26`)

### Build dự án
```bash
# Clone repository
git clone https://github.com/PrimiasArt/Ins-Translator.git

# Build APK Debug
./gradlew :app:assembleDebug
```

File APK sau khi build sẽ nằm tại: `app/build/outputs/apk/debug/`

### Cấu hình Gemini API Key
1. Lấy API Key miễn phí tại [Google AI Studio](https://aistudio.google.com/).
2. Mở ứng dụng, vào tab **Cài đặt (Settings)**.
3. Dán API Key và nhấn **Load Models** để kiểm tra kết nối và tải danh sách các model Gemini khả dụng.

---

## 📄 License
Dự án được phát triển bởi [PrimiasArt](https://github.com/PrimiasArt).
