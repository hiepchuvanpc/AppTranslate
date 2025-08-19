# 📱 OCR Manager - Bộ lõi nhận dạng văn bản thông minh

## 🎯 Tổng quan

**OcrManager** là một module OCR (Optical Character Recognition) tiên tiến được xây dựng trên nền tảng Google ML Kit, hỗ trợ nhận dạng văn bản đa ngôn ngữ một cách thông minh và hiệu quả.

## ✨ Tính năng chính

### 🌍 Hỗ trợ đa ngôn ngữ
- **Chữ Latin**: Tiếng Anh, Pháp, Đức, Tây Ban Nha, Ý, Bồ Đào Nha, Nga, Ả Rập
- **Chữ Hán**: Tiếng Trung (Giản thể & Phồn thể)
- **Chữ Nhật**: Hiragana, Katakana, Kanji
- **Chữ Hàn**: Hangul (Tiếng Hàn Quốc)
- **Chữ Devanagari**: Hindi, Sanskrit, Nepal, Marathi

### 🔧 Tính năng kỹ thuật
- **Tự động chọn bộ nhận dạng**: Dựa trên mã ngôn ngữ đầu vào
- **Cache thông minh**: Tái sử dụng các recognizer để tối ưu hiệu suất
- **Tiền xử lý ảnh**: Tự động tối ưu ảnh cho OCR
- **Kết quả chi tiết**: Trả về cấu trúc dữ liệu phong phú với tọa độ và confidence

## 🏗️ Kiến trúc

```
OcrManager
├── ScriptType (Enum)          # Định nghĩa các hệ chữ
├── OcrResult (Data Class)     # Kết quả OCR
├── OcrListener (Interface)    # Callback cho kết quả
├── OcrHelper (Helper Class)   # Tích hợp với UI
└── Core Functions             # Logic xử lý chính
```

## 🚀 Cách sử dụng

### 1. Khởi tạo OcrManager

```kotlin
val ocrManager = OcrManager()
```

### 2. Nhận dạng văn bản cơ bản

```kotlin
ocrManager.recognizeText(bitmap, "ja", object : OcrListener {
    override fun onSuccess(result: OcrResult) {
        // Xử lý kết quả thành công
        println("Văn bản: ${result.fullText}")
        println("Thời gian xử lý: ${result.processingTimeMs}ms")
        println("Hệ chữ: ${result.scriptType}")
    }

    override fun onFailure(exception: Exception) {
        // Xử lý lỗi
        println("Lỗi OCR: ${exception.message}")
    }
})
```

### 3. Sử dụng với OcrHelper (Khuyến nghị)

```kotlin
// Trong Fragment/Activity
class MyFragment : Fragment() {
    private lateinit var ocrHelper: OcrHelper

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Khởi tạo helper
        ocrHelper = OcrHelper.getInstance(requireContext())

        // Thiết lập observers
        ocrHelper.setupObservers(
            viewLifecycleOwner,
            onResult = { result -> handleOcrResult(result) },
            onError = { error -> showError(error) },
            onProcessingChange = { isProcessing -> updateUI(isProcessing) }
        )

        // Nhận dạng từ Uri
        ocrHelper.recognizeTextFromUri(imageUri, "zh")
    }

    private fun handleOcrResult(result: OcrResult) {
        textView.text = result.fullText

        // Lấy thống kê chi tiết
        val stats = ocrHelper.getOcrStatistics(result)
        println("Số khối văn bản: ${stats["totalBlocks"]}")
        println("Độ tin cậy trung bình: ${stats["averageConfidence"]}")
    }
}
```

## 📊 Cấu trúc dữ liệu kết quả

### OcrResult
```kotlin
data class OcrResult(
    val textBlocks: List<TextBlock>,     // Danh sách các khối văn bản
    val fullText: String,                // Toàn bộ văn bản
    val processingTimeMs: Long,          // Thời gian xử lý
    val scriptType: ScriptType           // Loại hệ chữ đã sử dụng
)
```

### TextBlock
```kotlin
data class TextBlock(
    val text: String,                    // Nội dung khối văn bản
    val boundingBox: Rect?,              // Vùng chứa
    val confidence: Float?,              // Độ tin cậy (0-1)
    val lines: List<TextLine>            // Danh sách dòng trong khối
)
```

### TextLine & TextElement
```kotlin
data class TextLine(
    val text: String,                    // Nội dung dòng
    val boundingBox: Rect?,              // Vùng chứa dòng
    val confidence: Float?,              // Độ tin cậy
    val elements: List<TextElement>      // Danh sách từ/ký tự
)

data class TextElement(
    val text: String,                    // Nội dung từ/ký tự
    val boundingBox: Rect?,              // Vùng chứa
    val confidence: Float?               // Độ tin cậy
)
```

## 🎨 Tiền xử lý ảnh

Module tự động thực hiện các bước tiền xử lý để tối ưu cho OCR:

1. **Resize ảnh**: Giảm kích thước nếu > 1280px
2. **Grayscale**: Chuyển sang thang độ xám
3. **Tăng contrast**: Làm rõ nét chữ
4. **Binarization**: Nhị phân hóa để tối ưu

## 🔍 Mapping ngôn ngữ

| Mã ngôn ngữ | Hệ chữ | Recognizer |
|-------------|---------|------------|
| `ja` | JAPANESE | JapaneseTextRecognizer |
| `zh`, `zh-cn`, `zh-tw` | CHINESE | ChineseTextRecognizer |
| `ko` | KOREAN | KoreanTextRecognizer |
| `hi`, `sa`, `ne`, `mr` | DEVANAGARI | DevanagariTextRecognizer |
| `en`, `es`, `fr`, etc. | LATIN | TextRecognizer (Default) |

## 📋 Dependencies

Thêm vào `build.gradle.kts`:

```kotlin
dependencies {
    // Google ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
```

## 🔒 Permissions

Thêm vào `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

## 🛠️ Utility Functions

### Kiểm tra hỗ trợ ngôn ngữ
```kotlin
if (OcrManager.isLanguageSupported("ja")) {
    // Tiếng Nhật được hỗ trợ
}
```

### Lấy danh sách ngôn ngữ được hỗ trợ
```kotlin
val supportedLanguages = OcrManager.getSupportedLanguages()
// Returns: Set<String>
```

### Xuất kết quả theo format khác nhau
```kotlin
val plainText = ocrHelper.exportResult(result, "plain")
val jsonText = ocrHelper.exportResult(result, "json")
val structuredText = ocrHelper.exportResult(result, "structured")
```

## ⚡ Hiệu suất

- **Cache recognizer**: Tránh khởi tạo lại không cần thiết
- **Tiền xử lý ảnh**: Tối ưu kích thước và chất lượng
- **Async processing**: Không block UI thread
- **Memory management**: Tự động dọn dẹp tài nguyên

## 🧪 Testing

### Test cơ bản
```kotlin
@Test
fun testOcrWithJapanese() {
    val bitmap = loadTestBitmap("japanese_text.jpg")
    var result: OcrResult? = null

    ocrManager.recognizeText(bitmap, "ja", object : OcrListener {
        override fun onSuccess(ocrResult: OcrResult) {
            result = ocrResult
        }
        override fun onFailure(exception: Exception) {
            fail("OCR failed: ${exception.message}")
        }
    })

    // Wait for async result
    await().until { result != null }

    assertThat(result!!.scriptType).isEqualTo(ScriptType.JAPANESE)
    assertThat(result!!.fullText).isNotEmpty()
}
```

## 🐛 Troubleshooting

### Lỗi thường gặp

1. **"Language not supported"**
   - Kiểm tra mã ngôn ngữ có trong danh sách hỗ trợ
   - Sử dụng `OcrManager.isLanguageSupported()`

2. **"Cannot read image"**
   - Kiểm tra quyền READ_EXTERNAL_STORAGE
   - Đảm bảo Uri hợp lệ

3. **OCR accuracy thấp**
   - Sử dụng ảnh có độ phân giải cao
   - Đảm bảo văn bản rõ nét, không bị mờ
   - Chọn đúng ngôn ngữ

### Best Practices

1. **Chuẩn bị ảnh tốt**:
   - Độ phân giải cao (ít nhất 300 DPI)
   - Văn bản rõ nét, tương phản cao
   - Ít nhiễu, không bị xoay

2. **Chọn ngôn ngữ chính xác**:
   - Sử dụng mã ngôn ngữ ISO chuẩn
   - Ưu tiên ngôn ngữ chính trong ảnh

3. **Quản lý tài nguyên**:
   - Gọi `cleanup()` khi không sử dụng
   - Sử dụng singleton pattern cho OcrHelper

## 📞 Hỗ trợ

- **Documentation**: Xem code comments chi tiết
- **Examples**: Tham khảo `OcrFragment.kt`
- **Issues**: Báo cáo lỗi qua GitHub Issues

---

🎉 **Chúc bạn sử dụng OCR Manager thành công!**
