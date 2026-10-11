package com.phyll.overlaytranslator.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

data class VocabularyItem(
    val id: String = System.currentTimeMillis().toString(),
    val sourceText: String,
    val translation: String,
    val analysis: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class HistoryItem(
    val id: String = System.currentTimeMillis().toString(),
    val sourceText: String,
    val translation: String,
    val phonetics: String = "",
    val analysis: String = "",
    val sourceLang: String = "",
    val targetLang: String = "",
    val isOutgoing: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class FlashcardItem(
    val id: String = System.currentTimeMillis().toString(),
    val sourceText: String,
    val translation: String,
    val phonetics: String = "",
    val analysis: String = "",
    var level: Int = 1,
    var reviewCount: Int = 0,
    var lastReviewedTime: Long = System.currentTimeMillis()
)

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var defaultTargetLang: String
        get() = prefs.getString(KEY_TARGET_LANG, "Thai") ?: "Thai"
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    var outgoingTargetLang: String
        get() = prefs.getString(KEY_OUTGOING_LANG, "Thai") ?: "Thai"
        set(value) = prefs.edit().putString(KEY_OUTGOING_LANG, value).apply()

    var learningLanguage: String
        get() = prefs.getString(KEY_LEARNING_LANG, "Thai") ?: "Thai"
        set(value) = prefs.edit().putString(KEY_LEARNING_LANG, value).apply()

    var learningLevel: String
        get() = prefs.getString(KEY_LEARNING_LEVEL, "🌱 Người mới bắt đầu (Dịch & Giải thích Tiếng Việt)") ?: "🌱 Người mới bắt đầu (Dịch & Giải thích Tiếng Việt)"
        set(value) = prefs.edit().putString(KEY_LEARNING_LEVEL, value).apply()

    var appInterfaceLang: String
        get() = prefs.getString(KEY_INTERFACE_LANG, "vi") ?: "vi"
        set(value) = prefs.edit().putString(KEY_INTERFACE_LANG, value).apply()

    var customStyleInstruction: String
        get() = prefs.getString(KEY_STYLE_INSTRUCTION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STYLE_INSTRUCTION, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    var primaryColor: String
        get() = prefs.getString(KEY_PRIMARY_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        set(value) = prefs.edit().putString(KEY_PRIMARY_COLOR, value).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat(KEY_OVERLAY_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit().putFloat(KEY_OVERLAY_OPACITY, value).apply()

    var favoritePersonaStyle: String
        get() = prefs.getString(KEY_PERSONA_STYLE, "Mặc định") ?: "Mặc định"
        set(value) = prefs.edit().putString(KEY_PERSONA_STYLE, value).apply()

    // --- Translation History Storage ---
    fun getHistoryList(): List<HistoryItem> {
        val jsonStr = prefs.getString(KEY_HISTORY_LIST, "[]") ?: "[]"
        val list = mutableListOf<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        sourceText = obj.optString("sourceText", ""),
                        translation = obj.optString("translation", ""),
                        phonetics = obj.optString("phonetics", ""),
                        analysis = obj.optString("analysis", ""),
                        sourceLang = obj.optString("sourceLang", ""),
                        targetLang = obj.optString("targetLang", ""),
                        isOutgoing = obj.optBoolean("isOutgoing", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.timestamp }
    }

    fun saveHistory(item: HistoryItem) {
        val currentList = getHistoryList().toMutableList()
        currentList.removeAll { it.sourceText == item.sourceText && it.translation == item.translation }
        currentList.add(0, item)

        val jsonArray = JSONArray()
        currentList.take(300).forEach { history ->
            val obj = JSONObject().apply {
                put("id", history.id)
                put("sourceText", history.sourceText)
                put("translation", history.translation)
                put("phonetics", history.phonetics)
                put("analysis", history.analysis)
                put("sourceLang", history.sourceLang)
                put("targetLang", history.targetLang)
                put("isOutgoing", history.isOutgoing)
                put("timestamp", history.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_LIST, jsonArray.toString()).apply()
    }

    fun deleteHistoryItem(id: String) {
        val currentList = getHistoryList().filterNot { it.id == id }
        val jsonArray = JSONArray()
        currentList.forEach { history ->
            val obj = JSONObject().apply {
                put("id", history.id)
                put("sourceText", history.sourceText)
                put("translation", history.translation)
                put("phonetics", history.phonetics)
                put("analysis", history.analysis)
                put("sourceLang", history.sourceLang)
                put("targetLang", history.targetLang)
                put("isOutgoing", history.isOutgoing)
                put("timestamp", history.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY_LIST, jsonArray.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY_LIST).apply()
    }

    // --- Flashcard Notebook Storage ---
    fun getFlashcardList(): List<FlashcardItem> {
        val jsonStr = prefs.getString(KEY_FLASHCARD_LIST, "[]") ?: "[]"
        val list = mutableListOf<FlashcardItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    FlashcardItem(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        sourceText = obj.optString("sourceText", ""),
                        translation = obj.optString("translation", ""),
                        phonetics = obj.optString("phonetics", ""),
                        analysis = obj.optString("analysis", ""),
                        level = obj.optInt("level", 1),
                        reviewCount = obj.optInt("reviewCount", 0),
                        lastReviewedTime = obj.optLong("lastReviewedTime", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.lastReviewedTime }
    }

    fun saveFlashcard(item: FlashcardItem) {
        val currentList = getFlashcardList().toMutableList()
        currentList.removeAll { it.sourceText == item.sourceText && it.translation == item.translation }
        currentList.add(0, item)

        saveFlashcardsListToPrefs(currentList)
    }

    fun updateFlashcardLevel(id: String, isCorrect: Boolean) {
        val currentList = getFlashcardList().toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) {
            val fc = currentList[index]
            val newLevel = if (isCorrect) (fc.level + 1).coerceAtMost(5) else 1
            val updated = fc.copy(
                level = newLevel,
                reviewCount = fc.reviewCount + 1,
                lastReviewedTime = System.currentTimeMillis()
            )
            currentList[index] = updated
            saveFlashcardsListToPrefs(currentList)
        }
    }

    fun deleteFlashcard(id: String) {
        val currentList = getFlashcardList().filterNot { it.id == id }
        saveFlashcardsListToPrefs(currentList)
    }

    private fun saveFlashcardsListToPrefs(list: List<FlashcardItem>) {
        val jsonArray = JSONArray()
        list.take(300).forEach { fc ->
            val obj = JSONObject().apply {
                put("id", fc.id)
                put("sourceText", fc.sourceText)
                put("translation", fc.translation)
                put("phonetics", fc.phonetics)
                put("analysis", fc.analysis)
                put("level", fc.level)
                put("reviewCount", fc.reviewCount)
                put("lastReviewedTime", fc.lastReviewedTime)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_FLASHCARD_LIST, jsonArray.toString()).apply()
    }

    // --- Legacy Vocabulary Methods ---
    fun getVocabularyList(): List<VocabularyItem> {
        val jsonStr = prefs.getString(KEY_VOCABULARY_LIST, "[]") ?: "[]"
        val list = mutableListOf<VocabularyItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    VocabularyItem(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        sourceText = obj.optString("sourceText", ""),
                        translation = obj.optString("translation", ""),
                        analysis = obj.optString("analysis", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.timestamp }
    }

    fun saveVocabulary(item: VocabularyItem) {
        val fc = FlashcardItem(
            sourceText = item.sourceText,
            translation = item.translation,
            analysis = item.analysis
        )
        saveFlashcard(fc)
    }

    fun deleteVocabulary(id: String) {
        deleteFlashcard(id)
    }

    companion object {
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_TARGET_LANG = "defaultTargetLang"
        private const val KEY_OUTGOING_LANG = "outgoingTargetLang"
        private const val KEY_LEARNING_LANG = "learningLanguage"
        private const val KEY_LEARNING_LEVEL = "learningLevel"
        private const val KEY_INTERFACE_LANG = "appInterfaceLang"
        private const val KEY_STYLE_INSTRUCTION = "customStyleInstruction"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_PRIMARY_COLOR = "primaryColor"
        private const val KEY_OVERLAY_OPACITY = "overlayOpacity"
        private const val KEY_PERSONA_STYLE = "favoritePersonaStyle"
        private const val KEY_VOCABULARY_LIST = "savedVocabularyList"
        private const val KEY_HISTORY_LIST = "savedHistoryList"
        private const val KEY_FLASHCARD_LIST = "savedFlashcardList"

        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_COLOR = "#405DE6"
        const val DEFAULT_OPACITY = 0.95f

        val PERSONA_STYLES = listOf(
            "Mặc định",
            "💖 Thân mật (Casual/Affectionate)",
            "💼 Trang trọng (Formal/Business)",
            "🤣 Gen Z / Từ lóng (Trendy/Slang)",
            "💻 Kỹ thuật / IT (Technical)"
        )

        val LEARNING_LEVELS = listOf(
            "🌱 Người mới bắt đầu (Dịch & Giải thích Tiếng Việt)",
            "🌿 Trung cấp (Nói Tiếng Thái + Giải thích Tiếng Việt khi có từ khó)",
            "🌳 Nâng cao (Giao tiếp thuần Tiếng Thái)"
        )

        val INTERFACE_LANGUAGES = mapOf(
            "vi" to "🇻🇳 Tiếng Việt",
            "en" to "🇬🇧 English",
            "th" to "🇹🇭 ภาษาไทย"
        )

        val COLOR_PALETTE = listOf(
            "#405DE6", // Classic Blue
            "#E91E63", // Pink/Magenta
            "#009688", // Teal
            "#8E44AD", // Purple
            "#FF9800", // Orange
            "#2ECC71", // Emerald Green
            "#34495E"  // Slate Gray
        )
    }
}
