package com.phyll.overlaytranslator.network

import android.util.Base64
import com.phyll.overlaytranslator.ChatMessage
import com.phyll.overlaytranslator.data.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TranslationResult(
    val detectedLang: String,
    val translation: String,
    val phonetics: String = "",
    val analysis: String = ""
)

data class PronunciationEvaluationResult(
    val score: Int,
    val gradeLabel: String,
    val feedback: String
)

data class StyleSuggestionsResult(
    val casual: String,
    val formal: String,
    val concise: String
)

data class GeminiModel(
    val id: String,
    val displayName: String
)

class GeminiClient {

    suspend fun translate(
        text: String,
        targetLang: String,
        apiKey: String,
        model: String,
        styleInstruction: String = "",
        isOutgoingMode: Boolean = false,
        sourceLang: String = "Auto Detect",
        personaStyle: String = "Mặc định",
        recentHistory: List<HistoryItem> = emptyList()
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext TranslationResult("", "", "", "")
        if (apiKey.isBlank()) throw GeminiException("API key is not configured. Open Settings to enter your Gemini API key.")

        val langDirection = if (sourceLang.isNotBlank() && sourceLang != "Auto Detect") {
            "Translate from $sourceLang to $targetLang."
        } else {
            "Detect the source language of the TEXT and translate it into $targetLang."
        }

        val modeInstruction = if (isOutgoingMode) {
            "OUTGOING MODE: The user is drafting a message to send in $targetLang. $langDirection"
        } else {
            "INCOMING MODE: The user received a message and wants to read/understand it in $targetLang. $langDirection"
        }

        val personaPrompt = when {
            personaStyle.contains("Thân mật") -> "Use a warm, casual, affectionate conversational tone with friendly pronouns (e.g. anh/em/bạn)."
            personaStyle.contains("Trang trọng") -> "Use a highly professional, polite, formal business tone with respectful grammar and terminology."
            personaStyle.contains("Gen Z") -> "Use trendy, casual, youthful Gen Z slang and internet conversational style."
            personaStyle.contains("Kỹ thuật") -> "Use precise technical terms, IT/engineering terminology and clear concise phrasing."
            else -> ""
        }

        val promptLines = mutableListOf(
            "You are a real-time chat translation engine and expert AI language tutor.",
            modeInstruction,
            "Provide strict JSON containing:",
            "1. 'detectedLang': detected or confirmed source language name.",
            "2. 'translation': pure natural translated text in $targetLang, maintaining 100% pronoun consistency and context continuity with previous turns.",
            "3. 'phonetics': pronunciation / IPA / pinyin / romaji reading guide.",
            "4. 'analysis': a professional, comprehensive language learning breakdown formatted in clear Vietnamese with line breaks and bullet points, containing:",
            "   • PHÂN TÍCH & TÁCH TỪ (Word-by-word segmentation with reading/pronunciation and exact meaning)",
            "   • CẤU TRÚC CÂU & NGỮ PHÁP (Grammar patterns and sentence structure breakdown)",
            "   • SO SÁNH CÂU GỐC & CÂU DỊCH (Direct comparison explaining nuances, tone, or word choice differences)"
        )

        if (personaPrompt.isNotBlank()) {
            promptLines.add("PERSONA TONE STYLE: $personaPrompt")
        }

        if (styleInstruction.isNotBlank()) {
            promptLines.add(
                "Follow these additional custom style/tone instructions: ${styleInstruction.trim()}"
            )
        }

        if (recentHistory.isNotEmpty()) {
            val historyLines = recentHistory.reversed().mapIndexed { index, item ->
                val role = if (item.isOutgoing) "Tin nhắn đi (User)" else "Tin nhắn đến (Sender)"
                "  Lượt ${index + 1} [$role]: \"${item.sourceText}\" ➔ Bản dịch: \"${item.translation}\""
            }.joinToString("\n")

            promptLines.add(
                """
                LỊCH SỬ HỘI THOẠI TRƯỚC ĐÓ (Dùng làm ngữ cảnh bắt buộc để chọn xưng hô phù hợp, nhất quán mối quan hệ và chủ đề):
                $historyLines
                """.trimIndent()
            )
        }

        promptLines.addAll(
            listOf(
                "Respond with STRICT JSON only, no markdown formatting outside JSON, no code fences, in exactly this shape:",
                """{"detectedLang":"<language name>","translation":"<translated text>","phonetics":"<pronunciation guide>","analysis":"<detailed Vietnamese analysis>"}""",
                "TEXT:",
                "```",
                text,
                "```"
            )
        )

        val prompt = promptLines.joinToString("\n")
        val raw = callGemini(prompt, apiKey, model)

        try {
            val cleaned = raw.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            TranslationResult(
                detectedLang = json.optString("detectedLang", "?"),
                translation = json.optString("translation", ""),
                phonetics = json.optString("phonetics", ""),
                analysis = json.optString("analysis", "")
            )
        } catch (e: Exception) {
            TranslationResult(detectedLang = "?", translation = raw.trim(), phonetics = "", analysis = "")
        }
    }

    suspend fun chatWithAITutor(
        userMessage: String,
        learningLang: String,
        learnerLevel: String,
        scenario: String,
        chatHistory: List<ChatMessage>,
        apiKey: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("Enter your Gemini API key in Settings.")

        val historyText = chatHistory.takeLast(10).joinToString("\n") {
            "${if (it.sender == "USER") "Học viên" else "AI Tutor"}: ${it.text}"
        }

        val systemPrompt = """
            Bạn là một Giáo Viên AI (AI Tutor) kiên nhẫn, thân thiện và giàu kinh nghiệm đang dạy tiếng $learningLang cho học viên người Việt Nam.
            Trình độ học viên: $learnerLevel
            Chủ đề/Kịch bản: $scenario

            QUY TẮC BẮT BUỘC VỀ ĐỊNH DẠNG TRẢ LỜI (DỄ ĐỌC, ĐẸP MẮT):
            1. KHÔNG ĐƯỢC CHỈ DỊCH THUẦN CÂU CỦA HỌC VIÊN SANG TIẾNG $learningLang!
            2. Trò chuyện trực tiếp với học viên bằng TIẾNG VIỆT để giải thích, hướng dẫn bài học.
            3. ĐỊNH DẠNG CÂU TRẢ LỜI TRỰC QUAN, RÕ RÀNG:
               - Sử dụng xuống dòng thật (\n) rõ ràng giữa các đoạn.
               - Dùng gạch đầu dòng (•) hoặc số (1., 2., 3.) khi liệt kê từ vựng/mẫu câu.
               - Khi giới thiệu từ vựng/mẫu câu, hãy sắp xếp cấu trúc danh sách rõ ràng:
                 • [Từ Tiếng $learningLang] - [Phiên âm Latinh/Karaoke] : [Nghĩa Tiếng Việt & Cách dùng]
               - Giải thích chi tiết ngữ pháp bên dưới bằng Tiếng Việt.
            4. Mỗi khi đưa ra câu hoặc từ vựng tiếng $learningLang, BẮT BUỘC phải bao gồm:
               - Câu/Từ tiếng $learningLang gốc (Ví dụ: สวัสดีครับ)
               - Phiên âm Karaoke/Latinh đọc mẫu trong ngoặc đơn (Ví dụ: Sawatdee khrap)
               - Nghĩa tiếng Việt & Giải thích chi tiết.
            5. Nếu học viên yêu cầu học điều gì (ví dụ: "mình muốn học bảng chữ cái Thái" hoặc "hãy phản hồi bằng tiếng Việt"), hãy nhiệt tình trả lời và giải thích bằng TIẾNG VIỆT, đưa ra 2-3 ví dụ từ vựng/chữ cái tiếng $learningLang kèm phiên âm rõ ràng!
            6. Nếu học viên nói "không hiểu" hoặc bối rối, hãy giải thích lại bằng TIẾNG VIỆT một cách đơn giản, ngắn gọn và tỉ mỉ hơn.
            7. BẮT BUỘC TRẢ VỀ KẾT QUẢ DƯỚI DẠNG STRICT JSON DUY NHẤT có hình dạng:
               {"reply":"<nội dung câu trả lời bằng tiếng Việt và tiếng $learningLang, có xuống dòng bằng \n>"}

            Lịch sử hội thoại gần đây:
            $historyText

            Tin nhắn mới từ học viên: "$userMessage"

            Hãy trả lời người học dưới dạng JSON chính xác:
        """.trimIndent()

        val raw = callGemini(systemPrompt, apiKey, model)
        try {
            val cleaned = raw.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            val replyText = json.optString("reply", raw)
            replyText.replace("\\n", "\n").replace("\\\"", "\"").trim()
        } catch (e: Exception) {
            raw.replace("```json", "").replace("```", "").replace("\\n", "\n").replace("\\\"", "\"").trim()
        }
    }

    suspend fun evaluatePronunciation(
        targetText: String,
        spokenText: String,
        langName: String,
        apiKey: String,
        model: String
    ): PronunciationEvaluationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("Enter your Gemini API key in Settings.")

        val prompt = """
            You are an AI Speech & Pronunciation Coach.
            Reference Sentence in $langName: "$targetText"
            User's Speech-to-Text Recorded Attempt: "$spokenText"

            Compare the user's spoken attempt against the reference sentence in $langName.
            Evaluate pronunciation accuracy, missing or wrong words, and phonetic clarity.
            Provide STRICT JSON with:
            1. 'score': Integer from 0 to 100 representing accuracy.
            2. 'gradeLabel': Short grade string like "85/100 - Khá Tốt!" or "95/100 - Xuất sắc!".
            3. 'feedback': A friendly, encouraging Vietnamese analysis explaining specific mispronounced/missing words and tips to improve pronunciation.

            JSON shape:
            {"score":85,"gradeLabel":"85/100 - Khá Tốt!","feedback":"<detailed Vietnamese feedback>"}
        """.trimIndent()

        val raw = callGemini(prompt, apiKey, model)
        try {
            val cleaned = raw.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            PronunciationEvaluationResult(
                score = json.optInt("score", 70),
                gradeLabel = json.optString("gradeLabel", "70/100 - Khá tốt!"),
                feedback = json.optString("feedback", "Phát âm gần chính xác!")
            )
        } catch (e: Exception) {
            PronunciationEvaluationResult(
                score = 75,
                gradeLabel = "75/100 - Tốt!",
                feedback = "Nói khá rõ ràng: '$spokenText'"
            )
        }
    }

    suspend fun getStyleSuggestions(
        text: String,
        targetLang: String,
        apiKey: String,
        model: String
    ): StyleSuggestionsResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("Enter your Gemini API key in Settings.")

        val prompt = """
            Provide 3 distinct translation variations of the text into $targetLang:
            1. 'casual': Friendly, warm, conversational tone (Thân mật).
            2. 'formal': Highly polite, professional business tone (Trang trọng).
            3. 'concise': Short, direct, concise phrasing (Súc tích).

            Text: "$text"

            STRICT JSON shape:
            {"casual":"...","formal":"...","concise":"..."}
        """.trimIndent()

        val raw = callGemini(prompt, apiKey, model)
        try {
            val cleaned = raw.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            StyleSuggestionsResult(
                casual = json.optString("casual", ""),
                formal = json.optString("formal", ""),
                concise = json.optString("concise", "")
            )
        } catch (e: Exception) {
            StyleSuggestionsResult("", "", "")
        }
    }

    suspend fun translateImage(
        imageBytes: ByteArray,
        targetLang: String,
        apiKey: String,
        model: String,
        styleInstruction: String = "",
        personaStyle: String = "Mặc định",
        recentHistory: List<HistoryItem> = emptyList()
    ): TranslationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("API key is not configured. Open Settings to enter your Gemini API key.")

        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val personaPrompt = when {
            personaStyle.contains("Thân mật") -> "Use a warm, casual, affectionate conversational tone with friendly pronouns (e.g. anh/em/bạn)."
            personaStyle.contains("Trang trọng") -> "Use a highly professional, polite, formal business tone with respectful grammar and terminology."
            personaStyle.contains("Gen Z") -> "Use trendy, casual, youthful Gen Z slang and internet conversational style."
            personaStyle.contains("Kỹ thuật") -> "Use precise technical terms, IT/engineering terminology and clear concise phrasing."
            else -> ""
        }

        val promptLines = mutableListOf(
            "You are an expert AI OCR text extractor, translation engine, and language tutor.",
            "Extract all text from the provided image, identify its source language, and translate it into $targetLang.",
            "Provide strict JSON containing:",
            "1. 'detectedLang': detected source language name of the text in image.",
            "2. 'translation': pure natural translated text in $targetLang, maintaining pronoun consistency and context continuity with previous conversation.",
            "3. 'phonetics': pronunciation / IPA / pinyin / romaji reading guide for the extracted text.",
            "4. 'analysis': a professional, comprehensive language learning breakdown formatted in clear Vietnamese with line breaks and bullet points, containing:",
            "   • 📸 VĂN BẢN TRÍCH XUẤT TỪ ẢNH (Extracted original text from image)",
            "   • 🗣 PHIÊN ÂM & PHÁT ÂM (Phonetics & Pronunciation)",
            "   • 📚 TÁCH TỪ & NGHĨA TỪNG TỪ (Word-by-word segmentation)",
            "   • 📐 CẤU TRÚC CÂU & NGỮ PHÁP (Grammar and sentence structure analysis)",
            "   • 🔄 SO SÁNH CÂU GỐC & CÂU DỊCH (Sentence comparison)"
        )

        if (personaPrompt.isNotBlank()) {
            promptLines.add("PERSONA TONE STYLE: $personaPrompt")
        }

        if (styleInstruction.isNotBlank()) {
            promptLines.add("Follow custom style instructions: ${styleInstruction.trim()}")
        }

        if (recentHistory.isNotEmpty()) {
            val historyLines = recentHistory.reversed().mapIndexed { index, item ->
                val role = if (item.isOutgoing) "Tin nhắn đi (User)" else "Tin nhắn đến (Sender)"
                "  Lượt ${index + 1} [$role]: \"${item.sourceText}\" ➔ Bản dịch: \"${item.translation}\""
            }.joinToString("\n")

            promptLines.add(
                """
                LỊCH SỬ HỘI THOẠI TRƯỚC ĐÓ (Dùng làm ngữ cảnh bắt buộc để chọn xưng hô phù hợp, nhất quán mối quan hệ và chủ đề):
                $historyLines
                """.trimIndent()
            )
        }

        promptLines.add("Respond with STRICT JSON only, no markdown formatting outside JSON, no code fences, in shape: {\"detectedLang\":\"...\",\"translation\":\"...\",\"phonetics\":\"...\",\"analysis\":\"...\"}")

        val prompt = promptLines.joinToString("\n")
        val raw = callGeminiWithImage(prompt, base64Image, apiKey, model)

        try {
            val cleaned = raw.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            TranslationResult(
                detectedLang = json.optString("detectedLang", "?"),
                translation = json.optString("translation", ""),
                phonetics = json.optString("phonetics", ""),
                analysis = json.optString("analysis", "")
            )
        } catch (e: Exception) {
            TranslationResult(detectedLang = "?", translation = raw.trim(), phonetics = "", analysis = "")
        }
    }

    suspend fun fetchModels(apiKey: String): List<GeminiModel> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw GeminiException("Enter your API key first.")

        val models = mutableListOf<GeminiModel>()
        var pageToken = ""

        do {
            val urlBuilder = StringBuilder(
                "https://generativelanguage.googleapis.com/v1beta/models?key=${URLEncoder.encode(apiKey, "UTF-8")}&pageSize=100"
            )
            if (pageToken.isNotEmpty()) {
                urlBuilder.append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
            }

            val url = URL(urlBuilder.toString())
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            try {
                if (conn.responseCode != 200) {
                    val errBody = readStream(conn.errorStream ?: conn.inputStream)
                    throw GeminiException("HTTP ${conn.responseCode}: ${errBody.take(300)}")
                }

                val body = readStream(conn.inputStream)
                val data = JSONObject(body)
                val modelsArray = data.optJSONArray("models") ?: JSONArray()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val methodsList = (0 until methods.length()).map { methods.getString(it) }
                    if ("generateContent" !in methodsList) continue

                    val name = m.optString("name", "").removePrefix("models/")
                    if (name.isEmpty()) continue

                    val displayName = m.optString("displayName", name)
                    models.add(GeminiModel(id = name, displayName = "$displayName ($name)"))
                }

                pageToken = data.optString("nextPageToken", "")
            } finally {
                conn.disconnect()
            }
        } while (pageToken.isNotEmpty())

        models.sortBy { it.id }
        models
    }

    private fun callGemini(prompt: String, apiKey: String, model: String): String {
        val encodedModel = URLEncoder.encode(model, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent?key=$encodedKey"
        )

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("responseMimeType", "application/json")
            })
        }

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody.toString()) }

            if (conn.responseCode != 200) {
                val errBody = readStream(conn.errorStream ?: conn.inputStream)
                throw GeminiException("Gemini API error (${conn.responseCode}): ${errBody.take(300)}")
            }

            val body = readStream(conn.inputStream)
            val data = JSONObject(body)
            val parts = data
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return ""

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun callGeminiWithImage(prompt: String, base64Image: String, apiKey: String, model: String): String {
        val encodedModel = URLEncoder.encode(model, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent?key=$encodedKey"
        )

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("responseMimeType", "application/json")
            })
        }

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody.toString()) }

            if (conn.responseCode != 200) {
                val errBody = readStream(conn.errorStream ?: conn.inputStream)
                throw GeminiException("Gemini Vision API error (${conn.responseCode}): ${errBody.take(300)}")
            }

            val body = readStream(conn.inputStream)
            val data = JSONObject(body)
            val parts = data
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return ""

            val sb = StringBuilder()
            for (i in  0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}

class GeminiException(message: String) : Exception(message)
