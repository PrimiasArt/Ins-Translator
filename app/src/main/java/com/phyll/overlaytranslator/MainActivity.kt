package com.phyll.overlaytranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.phyll.overlaytranslator.data.FlashcardItem
import com.phyll.overlaytranslator.data.Languages
import com.phyll.overlaytranslator.data.SettingsStore
import com.phyll.overlaytranslator.network.GeminiClient
import com.phyll.overlaytranslator.network.GeminiModel
import com.phyll.overlaytranslator.overlay.OverlayBubbleService
import com.phyll.overlaytranslator.overlay.SubtitleBarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

data class ChatMessage(
    val sender: String, // "AI" or "USER"
    val text: String,
    val correction: String = ""
)

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var geminiClient: GeminiClient
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var containerContent: FrameLayout
    private lateinit var navDashboard: TextView
    private lateinit var navAITutor: TextView
    private lateinit var navFlashcards: TextView
    private lateinit var navSettings: TextView
    private lateinit var txtHeaderStreak: TextView

    private var activeTab = 1 // 1: Dashboard, 2: AI Tutor, 3: Flashcards, 4: Settings
    private val chatMessages = mutableListOf<ChatMessage>()
    private var isAITyping = false
    private var tts: TextToSpeech? = null
    private var subtitleBarController: SubtitleBarController? = null

    private var loadedModels: List<GeminiModel> = emptyList()
    private var selectedColorHex: String = SettingsStore.DEFAULT_COLOR

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (activeTab == 1) renderDashboardTab()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (activeTab == 1) renderDashboardTab()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(settings.themeMode)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geminiClient = GeminiClient()
        selectedColorHex = settings.primaryColor

        try {
            tts = TextToSpeech(applicationContext) { _ -> }
        } catch (_: Exception) {}

        containerContent = findViewById(R.id.containerContent)
        navDashboard = findViewById(R.id.navDashboard)
        navAITutor = findViewById(R.id.navAITutor)
        navFlashcards = findViewById(R.id.navFlashcards)
        navSettings = findViewById(R.id.navSettings)
        txtHeaderStreak = findViewById(R.id.txtHeaderStreak)

        setupNavigationLabels()
        setupNavigation()
        switchTab(1)
    }

    override fun onResume() {
        super.onResume()
        txtHeaderStreak.text = "🔥 1 Ngày Streak"
        setupNavigationLabels()
        if (activeTab == 1) renderDashboardTab()
    }

    override fun onDestroy() {
        try {
            subtitleBarController?.dismiss()
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        subtitleBarController = null
        activityScope.cancel()
        super.onDestroy()
    }

    private fun setupNavigationLabels() {
        val lang = settings.appInterfaceLang
        when (lang) {
            "en" -> {
                navDashboard.text = "🏠 Home"
                navAITutor.text = "🤖 AI Tutor"
                navFlashcards.text = "🎴 Flashcards"
                navSettings.text = "⚙️ Settings"
            }
            "th" -> {
                navDashboard.text = "🏠 หน้าหลัก"
                navAITutor.text = "🤖 AI ติวเตอร์"
                navFlashcards.text = "🎴 คำศัพท์"
                navSettings.text = "⚙️ ตั้งค่า"
            }
            else -> {
                navDashboard.text = "🏠 Trang chủ"
                navAITutor.text = "🤖 AI Tutor"
                navFlashcards.text = "🎴 Sổ từ vựng"
                navSettings.text = "⚙️ Cài đặt"
            }
        }
    }

    private fun speakText(text: String, langName: String) {
        if (text.isBlank()) return
        try {
            val locale = when {
                langName.lowercase().contains("thai") -> Locale("th", "TH")
                langName.lowercase().contains("chinese") -> Locale.SIMPLIFIED_CHINESE
                langName.lowercase().contains("japanese") -> Locale.JAPANESE
                langName.lowercase().contains("korean") -> Locale.KOREAN
                else -> Locale.US
            }
            tts?.language = locale
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_MAIN_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi phát âm: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNavigation() {
        navDashboard.setOnClickListener { switchTab(1) }
        navAITutor.setOnClickListener { switchTab(2) }
        navFlashcards.setOnClickListener { switchTab(3) }
        navSettings.setOnClickListener { switchTab(4) }
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex
        val primaryColorInt = try { Color.parseColor(settings.primaryColor) } catch (_: Exception) { Color.parseColor(SettingsStore.DEFAULT_COLOR) }

        val selectedBg = GradientDrawable().apply {
            cornerRadius = dpToPx(12f)
            setColor(primaryColorInt)
        }

        val textSecondaryColor = getColor(R.color.text_secondary)

        listOf(navDashboard, navAITutor, navFlashcards, navSettings).forEachIndexed { idx, tv ->
            if (idx + 1 == tabIndex) {
                tv.background = selectedBg
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = null
                tv.setTextColor(textSecondaryColor)
            }
        }

        when (tabIndex) {
            1 -> renderDashboardTab()
            2 -> renderAITutorTab()
            3 -> renderFlashcardsTab()
            4 -> renderSettingsTab()
        }
    }

    // --- TAB 1: DASHBOARD ---
    private fun renderDashboardTab() {
        containerContent.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Card 1: Daily Streak Banner
        val cardStreak = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            radius = dpToPx(16f)
            cardElevation = dpToPx(4f)
        }
        val streakLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
        }
        val txtStreakTitle = TextView(this).apply {
            text = "🔥 Daily Streak: 1 Ngày Liên Tiếp!"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E65100"))
        }
        val txtStreakSub = TextView(this).apply {
            text = "Hãy duy trì học ${settings.learningLanguage} và giao tiếp với AI mỗi ngày để làm chủ ngôn ngữ!"
            textSize = 13f
            setTextColor(Color.parseColor("#F57C00"))
            setPadding(0, 6, 0, 0)
        }
        streakLayout.addView(txtStreakTitle)
        streakLayout.addView(txtStreakSub)
        cardStreak.addView(streakLayout)

        // Card 2: Stats Grid
        val cardStats = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            radius = dpToPx(16f)
            cardElevation = dpToPx(2f)
        }
        val statsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
        }
        val totalVocab = settings.getFlashcardList().size
        val totalHistory = settings.getHistoryList().size

        val stat1 = createStatItem("🇹🇭 Mục tiêu", settings.learningLanguage)
        val stat2 = createStatItem("🎴 Từ vựng", "$totalVocab từ")
        val stat3 = createStatItem("📜 Lượt dịch", "$totalHistory lượt")

        statsLayout.addView(stat1)
        statsLayout.addView(stat2)
        statsLayout.addView(stat3)
        cardStats.addView(statsLayout)

        // Card 3: Floating Bubble Control & Subtitle Bar
        val cardBubble = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            radius = dpToPx(16f)
            cardElevation = dpToPx(4f)
        }
        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val txtBubbleTitle = TextView(this).apply {
            text = "🎈 Trợ Lý Bong Bóng & Phụ Đề Nổi"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        val txtBubbleStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 4, 0, 12)
        }

        val btnOverlayPerm = MaterialButton(this).apply {
            text = "Cấp quyền đè màn hình (Overlay)"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                overlayPermissionLauncher.launch(intent)
            }
        }

        val btnNotifPerm = MaterialButton(this).apply {
            text = "Cấp quyền Thông báo"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        val btnStartStop = MaterialButton(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dpToPxInt()).apply { setMargins(0, 4, 0, 8) }
        }

        val btnToggleSubtitle = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = if (subtitleBarController != null) "🛑 Tắt Thanh Phụ Đề Ngang (Subtitle Bar)" else "🌐 Bật Thanh Phụ Đề Ngang (Subtitle Bar)"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dpToPxInt()).apply { setMargins(0, 0, 0, 4) }
            setOnClickListener {
                if (subtitleBarController != null) {
                    subtitleBarController?.dismiss()
                    subtitleBarController = null
                } else {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    subtitleBarController = SubtitleBarController(this@MainActivity, wm, activityScope)
                    subtitleBarController?.show()
                }
                renderDashboardTab()
            }
        }

        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true

        btnOverlayPerm.visibility = if (overlayOk) View.GONE else View.VISIBLE
        btnNotifPerm.visibility = if (notifOk || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) View.GONE else View.VISIBLE

        val allOk = overlayOk && notifOk
        btnStartStop.isEnabled = allOk

        if (!allOk) {
            txtBubbleStatus.text = "Cần cấp các quyền bên dưới để bật trợ lý nổi."
            btnStartStop.text = "Bật Bong Bóng Dịch Nổi"
        } else if (serviceRunning) {
            txtBubbleStatus.text = "🟢 Bong bóng trợ lý nổi đang hoạt động trên màn hình!"
            btnStartStop.text = "Tắt Bong Bóng Dịch"
        } else {
            txtBubbleStatus.text = "Ready - Bấm nút bên dưới để khởi chạy bong bóng nổi."
            btnStartStop.text = "Bật Bong Bóng Dịch Nổi"
        }

        btnStartStop.setOnClickListener {
            if (serviceRunning) {
                stopService(Intent(this, OverlayBubbleService::class.java))
                serviceRunning = false
            } else {
                ContextCompat.startForegroundService(this, Intent(this, OverlayBubbleService::class.java))
                serviceRunning = true
            }
            renderDashboardTab()
        }

        bubbleLayout.addView(txtBubbleTitle)
        bubbleLayout.addView(txtBubbleStatus)
        bubbleLayout.addView(btnOverlayPerm)
        bubbleLayout.addView(btnNotifPerm)
        bubbleLayout.addView(btnStartStop)
        bubbleLayout.addView(btnToggleSubtitle)
        cardBubble.addView(bubbleLayout)

        layout.addView(cardStreak)
        layout.addView(cardStats)
        layout.addView(cardBubble)

        containerContent.addView(layout)
    }

    private fun createStatItem(title: String, value: String): View {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER

            val txtVal = TextView(context).apply {
                text = value
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.primary))
                gravity = Gravity.CENTER
            }
            val txtLbl = TextView(context).apply {
                text = title
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                gravity = Gravity.CENTER
            }
            addView(txtVal)
            addView(txtLbl)
        }
    }

    // --- TAB 2: EXPANDED EXPANDABLE MULTI-LANGUAGE AI TUTOR CHAT WITH ANIMATED TYPING INDICATOR ---
    private fun renderAITutorTab() {
        containerContent.removeAllViews()

        val layout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
        }

        val txtHeader = TextView(this).apply {
            text = "🤖 AI Tutor Chat Assistant"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }

        // Setup Controls Layout: Language & Level
        val configLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        val spinnerLang = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 44.dpToPxInt(), 1f).apply { setMargins(0, 0, 4, 0) }
            val langOptions = listOf("Thai", "English", "Chinese", "Japanese", "Korean", "French", "German", "Spanish")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langOptions)
            setSelection(langOptions.indexOf(settings.learningLanguage).coerceAtLeast(0))
        }

        val spinnerLevel = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 44.dpToPxInt(), 1.2f).apply { setMargins(4, 0, 0, 0) }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, SettingsStore.LEARNING_LEVELS)
            setSelection(SettingsStore.LEARNING_LEVELS.indexOf(settings.learningLevel).coerceAtLeast(0))
        }

        configLayout.addView(spinnerLang)
        configLayout.addView(spinnerLevel)

        val spinnerScenario = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 44.dpToPxInt()).apply { setMargins(0, 0, 0, 10) }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                "💬 Trò chuyện tự do hàng ngày",
                "💼 Phỏng vấn xin việc (Job Interview)",
                "✈️ Giao tiếp Du lịch & Hỏi đường",
                "🍔 Đặt đồ ăn & Mua sắm"
            ))
        }

        // Expanded Chat Container (Fills screen height with weight 1f)
        val chatContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 0, 0, 10) }
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(16, 16, 16, 16)
        }

        val chatScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val chatListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        chatScrollView.addView(chatListLayout)
        chatContainer.addView(chatScrollView)

        if (chatMessages.isEmpty()) {
            val initialGreeting = if (spinnerLang.selectedItem?.toString() == "Thai") {
                "สวัสดีครับ (Sawatdee khrap)! Xin chào bạn! Mình là AI Tutor dạy Tiếng Thái.\n\n💡 Bạn có thể hỏi bất kỳ điều gì bằng Tiếng Việt (Ví dụ: \"Dạy mình câu chào hỏi\" hoặc \"Dạy mình bảng chữ cái Thái\"), mình sẽ giảng giải tỉ mỉ bằng Tiếng Việt kèm phiên âm từng từ nhé!"
            } else {
                "Xin chào! Mình là AI Tutor dạy ${spinnerLang.selectedItem}. Mình sẽ luôn giảng giải bằng Tiếng Việt kèm phiên âm bài học cho bạn!"
            }
            chatMessages.add(ChatMessage("AI", initialGreeting))
        }

        fun updateChatUI() {
            chatListLayout.removeAllViews()
            chatMessages.forEach { msg ->
                val bubbleCard = MaterialCardView(this).apply {
                    radius = dpToPx(14f)
                    cardElevation = dpToPx(2f)
                    setCardBackgroundColor(if (msg.sender == "USER") getColor(R.color.primary) else Color.parseColor("#F5F5F5"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 12)
                        gravity = if (msg.sender == "USER") Gravity.END else Gravity.START
                    }
                }

                val bubbleContent = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 16, 20, 16)
                }

                val txtMsg = TextView(this).apply {
                    text = msg.text
                    textSize = 14f
                    setTextColor(if (msg.sender == "USER") Color.WHITE else Color.parseColor("#212121"))
                }
                bubbleContent.addView(txtMsg)

                if (msg.sender == "AI") {
                    val actionRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, 10, 0, 0)
                    }

                    val btnSpeak = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "🔊 Nghe"
                        textSize = 11f
                        setOnClickListener {
                            speakText(msg.text, spinnerLang.selectedItem.toString())
                        }
                    }

                    val btnSaveVocab = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "⭐ Lưu"
                        textSize = 11f
                        setOnClickListener {
                            val fc = FlashcardItem(
                                sourceText = msg.text.take(40),
                                translation = msg.text,
                                analysis = "Bài học AI Tutor"
                            )
                            settings.saveFlashcard(fc)
                            Toast.makeText(this@MainActivity, "⭐ Đã lưu bài học vào Flashcards!", Toast.LENGTH_SHORT).show()
                        }
                    }

                    actionRow.addView(btnSpeak)
                    actionRow.addView(btnSaveVocab)
                    bubbleContent.addView(actionRow)
                }

                bubbleCard.addView(bubbleContent)
                chatListLayout.addView(bubbleCard)
            }

            // Render Typing Indicator Bubble if AI is generating response
            if (isAITyping) {
                val typingBubble = MaterialCardView(this).apply {
                    radius = dpToPx(14f)
                    cardElevation = dpToPx(2f)
                    setCardBackgroundColor(Color.parseColor("#E8EAF6"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 12)
                        gravity = Gravity.START
                    }
                }
                val typingLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(20, 14, 20, 14)
                }
                val txtTyping = TextView(this).apply {
                    text = "🤖 AI Tutor đang soạn bài học... ✍️"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD_ITALIC)
                    setTextColor(Color.parseColor("#3F51B5"))
                }
                typingLayout.addView(txtTyping)
                typingBubble.addView(typingLayout)
                chatListLayout.addView(typingBubble)
            }

            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        }

        updateChatUI()

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputMsg = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            hint = "Nhập tin nhắn..."
            textSize = 14f
        }

        val btnSend = MaterialButton(this).apply {
            text = "🚀 Gửi"
            setOnClickListener {
                val userText = inputMsg.text.toString().trim()
                if (userText.isNotEmpty()) {
                    chatMessages.add(ChatMessage("USER", userText))
                    inputMsg.text.clear()
                    isAITyping = true
                    updateChatUI()

                    val targetLang = spinnerLang.selectedItem.toString()
                    val level = spinnerLevel.selectedItem.toString()
                    val scenario = spinnerScenario.selectedItem.toString()

                    activityScope.launch {
                        try {
                            val reply = geminiClient.chatWithAITutor(
                                userMessage = userText,
                                learningLang = targetLang,
                                learnerLevel = level,
                                scenario = scenario,
                                chatHistory = chatMessages,
                                apiKey = settings.apiKey,
                                model = settings.model
                            )
                            isAITyping = false
                            chatMessages.add(ChatMessage("AI", if (reply.isNotBlank()) reply else "Rất tốt! Hãy tiếp tục thực hành nhé!"))
                            updateChatUI()
                        } catch (e: Exception) {
                            isAITyping = false
                            chatMessages.add(ChatMessage("AI", "Lỗi phản hồi: ${e.message}"))
                            updateChatUI()
                        }
                    }
                }
            }
        }

        inputLayout.addView(inputMsg)
        inputLayout.addView(btnSend)

        layout.addView(txtHeader)
        layout.addView(configLayout)
        layout.addView(spinnerScenario)
        layout.addView(chatContainer)
        layout.addView(inputLayout)

        containerContent.addView(layout)
    }

    // --- TAB 3: FLASHCARDS ---
    private fun renderFlashcardsTab() {
        containerContent.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val txtTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "🎴 Flashcard SRS Notebook"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }

        val btnPractice = MaterialButton(this).apply {
            text = "🎴 Luyện Flashcards"
            setOnClickListener { startFlashcardPractice() }
        }

        headerLayout.addView(txtTitle)
        headerLayout.addView(btnPractice)

        val vocabContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fcList = settings.getFlashcardList()

        if (fcList.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "Chưa có từ vựng nào được lưu. Bấm nút ⭐ trong bảng dịch bong bóng nổi để thêm từ vựng mới!"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 16, 0, 16)
            }
            vocabContainer.addView(emptyTxt)
        } else {
            fcList.forEach { item ->
                val card = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
                    radius = dpToPx(12f)
                    cardElevation = dpToPx(2f)
                }

                val cardContent = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 24, 24, 24)
                }

                val rowHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val txtSrc = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "⭐ ${item.sourceText}"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.text_primary))
                }
                val btnDel = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "🗑 Xóa"
                    textSize = 12f
                    setOnClickListener {
                        settings.deleteFlashcard(item.id)
                        renderFlashcardsTab()
                    }
                }
                rowHeader.addView(txtSrc)
                rowHeader.addView(btnDel)

                val txtTrans = TextView(this).apply {
                    text = "➜ ${item.translation}"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(getColor(R.color.primary))
                    setPadding(0, 4, 0, 4)
                }

                val txtLevel = TextView(this).apply {
                    text = "Level SRS: ${item.level}/5 | Đã ôn: ${item.reviewCount} lần"
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                }

                cardContent.addView(rowHeader)
                cardContent.addView(txtTrans)
                cardContent.addView(txtLevel)
                card.addView(cardContent)
                vocabContainer.addView(card)
            }
        }

        layout.addView(headerLayout)
        layout.addView(vocabContainer)

        containerContent.addView(layout)
    }

    private fun startFlashcardPractice() {
        val fcList = settings.getFlashcardList()
        if (fcList.isEmpty()) {
            Toast.makeText(this, "Hãy lưu một vài từ vựng ⭐ trước khi luyện tập!", Toast.LENGTH_LONG).show()
            return
        }

        var currentIndex = 0
        var isFlipped = false

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }

        val txtProgress = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        }

        val cardView = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180.dpToPxInt()).apply { setMargins(0, 16, 0, 16) }
            radius = dpToPx(16f)
            cardElevation = dpToPx(6f)
        }

        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        val txtCardFront = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
        }

        val txtCardBack = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.primary))
            visibility = View.GONE
        }

        val txtCardHint = TextView(this).apply {
            text = "👆 Nhấp vào thẻ để lật xem đáp án"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 12, 0, 0)
        }

        cardContent.addView(txtCardFront)
        cardContent.addView(txtCardBack)
        cardContent.addView(txtCardHint)
        cardView.addView(cardContent)

        val btnActionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnWrong = MaterialButton(this).apply {
            text = "🔴 Chưa thuộc"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
        }

        val btnCorrect = MaterialButton(this).apply {
            text = "🟢 Đã thuộc"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
        }

        btnActionLayout.addView(btnWrong)
        btnActionLayout.addView(btnCorrect)

        dialogView.addView(txtProgress)
        dialogView.addView(cardView)
        dialogView.addView(btnActionLayout)

        val dialog = AlertDialog.Builder(this)
            .setTitle("🎴 Ôn Tập Flashcards SRS")
            .setView(dialogView)
            .setNegativeButton("Đóng") { d, _ -> d.dismiss() }
            .create()

        fun updateCardDisplay() {
            if (currentIndex !in fcList.indices) {
                Toast.makeText(this, "🎉 Đã hoàn thành vòng ôn tập!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                renderFlashcardsTab()
                return
            }

            val fc = fcList[currentIndex]
            txtProgress.text = "Thẻ ${currentIndex + 1} / ${fcList.size} | Level ${fc.level}"

            txtCardFront.text = fc.sourceText
            val backBuilder = StringBuilder()
            backBuilder.append("➜ ${fc.translation}\n")
            if (fc.analysis.isNotBlank()) {
                backBuilder.append("\n${fc.analysis}")
            }
            txtCardBack.text = backBuilder.toString()

            isFlipped = false
            txtCardFront.visibility = View.VISIBLE
            txtCardBack.visibility = View.GONE
        }

        cardView.setOnClickListener {
            isFlipped = !isFlipped
            if (isFlipped) {
                txtCardFront.visibility = View.GONE
                txtCardBack.visibility = View.VISIBLE
            } else {
                txtCardFront.visibility = View.VISIBLE
                txtCardBack.visibility = View.GONE
            }
        }

        btnWrong.setOnClickListener {
            val fc = fcList[currentIndex]
            settings.updateFlashcardLevel(fc.id, isCorrect = false)
            currentIndex++
            updateCardDisplay()
        }

        btnCorrect.setOnClickListener {
            val fc = fcList[currentIndex]
            settings.updateFlashcardLevel(fc.id, isCorrect = true)
            currentIndex++
            updateCardDisplay()
        }

        updateCardDisplay()
        dialog.show()
    }

    // --- TAB 4: FULL FEATURED SETTINGS HUB ---
    private fun renderSettingsTab() {
        containerContent.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val txtTitle = TextView(this).apply {
            text = "⚙️ Cấu Hình Ứng Dụng Đầy Đủ (Settings Hub)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, 16)
        }

        // Section 1: Gemini API
        val lblApiKey = TextView(this).apply {
            text = "Gemini API Key"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        }
        val inputApiKey = EditText(this).apply {
            hint = "Paste Gemini API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
        }

        val lblModel = TextView(this).apply {
            text = "Model Gemini"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 12, 0, 4)
        }

        val modelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val spinnerModel = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dpToPxInt(), 1f)
        }
        val btnLoadModels = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Load Models"
        }
        modelLayout.addView(spinnerModel)
        modelLayout.addView(btnLoadModels)

        val progressModels = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val txtModelHint = TextView(this).apply {
            text = "Nhập API Key rồi bấm Load Models"
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 2, 0, 16)
        }

        val savedModel = settings.model
        if (savedModel.isNotEmpty()) {
            spinnerModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(savedModel))
        }

        btnLoadModels.setOnClickListener {
            val key = inputApiKey.text.toString().trim()
            if (key.isEmpty()) {
                txtModelHint.text = "Vui lòng nhập API Key trước!"
                return@setOnClickListener
            }
            btnLoadModels.isEnabled = false
            progressModels.visibility = View.VISIBLE
            txtModelHint.text = "Đang tải danh sách models..."

            activityScope.launch {
                try {
                    loadedModels = geminiClient.fetchModels(key)
                    progressModels.visibility = View.GONE
                    btnLoadModels.isEnabled = true
                    val displayNames = loadedModels.map { it.displayName }
                    spinnerModel.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, displayNames)
                    val savedIndex = loadedModels.indexOfFirst { it.id == savedModel }
                    if (savedIndex >= 0) spinnerModel.setSelection(savedIndex)
                    txtModelHint.text = "Đã tải ${loadedModels.size} models!"
                } catch (e: Exception) {
                    progressModels.visibility = View.GONE
                    btnLoadModels.isEnabled = true
                    txtModelHint.text = "Lỗi: ${e.message}"
                }
            }
        }

        // Section 2: App Interface Language & Learning Language Setup
        val lblLangHeader = TextView(this).apply {
            text = "Thiết Lập Ngôn Ngữ (Localization & Learning)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
            setPadding(0, 8, 0, 8)
        }

        val lblInterfaceLang = TextView(this).apply { text = "Ngôn ngữ giao diện App (Interface Language)"; textSize = 13f; setTypeface(null, Typeface.BOLD) }
        val spinnerInterfaceLang = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dpToPxInt()).apply { setMargins(0, 0, 0, 8) }
            val intLangKeys = SettingsStore.INTERFACE_LANGUAGES.keys.toList()
            val intLangVals = SettingsStore.INTERFACE_LANGUAGES.values.toList()
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, intLangVals)
            setSelection(intLangKeys.indexOf(settings.appInterfaceLang).coerceAtLeast(0))
        }

        val langAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Languages.LIST)

        val lblIncoming = TextView(this).apply { text = "Ngôn ngữ học chính (Ví dụ: Thai)"; textSize = 13f; setTypeface(null, Typeface.BOLD) }
        val langIncoming = AutoCompleteTextView(this).apply {
            setAdapter(langAdapter)
            threshold = 1
            setText(settings.learningLanguage, false)
        }

        val lblOutgoing = TextView(this).apply { text = "Ngôn ngữ phụ (Outgoing)"; textSize = 13f; setTypeface(null, Typeface.BOLD); setPadding(0, 8, 0, 0) }
        val langOutgoing = AutoCompleteTextView(this).apply {
            setAdapter(langAdapter)
            threshold = 1
            setText(settings.outgoingTargetLang, false)
        }

        // Section 3: Custom Style Instructions
        val lblStyleHeader = TextView(this).apply {
            text = "Hướng Dẫn Phong Cách Dịch (Prompt Tùy Chỉnh)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 12, 0, 4)
        }
        val inputStyleInstruction = EditText(this).apply {
            hint = "VD: xưng anh/em thân mật, giải thích chi tiết cho người mới"
            setText(settings.customStyleInstruction)
            minLines = 2
        }

        // Section 4: Dark Mode & Opacity
        val lblThemeHeader = TextView(this).apply {
            text = "Giao Diện & Chế Độ Sáng/Tối"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
            setPadding(0, 12, 0, 8)
        }

        val radioGroupTheme = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val radioSystem = RadioButton(this).apply { text = "Hệ thống"; layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val radioLight = RadioButton(this).apply { text = "Sáng"; layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val radioDark = RadioButton(this).apply { text = "Tối"; layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f) }
        radioGroupTheme.addView(radioSystem)
        radioGroupTheme.addView(radioLight)
        radioGroupTheme.addView(radioDark)

        when (settings.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> radioDark.isChecked = true
            else -> radioSystem.isChecked = true
        }

        radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                radioLight.id -> AppCompatDelegate.MODE_NIGHT_NO
                radioDark.id -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settings.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        val opacityLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 4) }
        val lblOpacity = TextView(this).apply { text = "Độ trong suốt khung dịch (Opacity):"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setTypeface(null, Typeface.BOLD) }
        val txtOpacityVal = TextView(this).apply { text = "${(settings.overlayOpacity * 100).toInt()}%"; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.primary)) }
        opacityLayout.addView(lblOpacity)
        opacityLayout.addView(txtOpacityVal)

        val seekBarOpacity = SeekBar(this).apply {
            max = 100
            progress = (settings.overlayOpacity * 100).toInt().coerceIn(30, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceAtLeast(30)
                    txtOpacityVal.text = "$p%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val p = (seekBar?.progress ?: 95).coerceAtLeast(30)
                    settings.overlayOpacity = p / 100f
                }
            })
        }

        // Section 5: Color Palette
        val lblPalette = TextView(this).apply { text = "Màu sắc ứng dụng:"; setTypeface(null, Typeface.BOLD); setPadding(0, 12, 0, 8) }
        val colorPaletteContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun renderPalette() {
            colorPaletteContainer.removeAllViews()
            val sizePx = 36.dpToPxInt()
            val marginPx = 10.dpToPxInt()
            SettingsStore.COLOR_PALETTE.forEach { hex ->
                val v = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { setMargins(0, 0, marginPx, 0) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(hex))
                        if (hex.equals(selectedColorHex, ignoreCase = true)) {
                            setStroke(3.dpToPxInt(), Color.BLACK)
                        }
                    }
                    setOnClickListener {
                        selectedColorHex = hex
                        settings.primaryColor = hex
                        renderPalette()
                    }
                }
                colorPaletteContainer.addView(v)
            }
        }
        renderPalette()

        val btnClearHistory = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "🗑 Xóa toàn bộ lịch sử dịch"
            setOnClickListener {
                settings.clearHistory()
                Toast.makeText(this@MainActivity, "Đã xóa lịch sử dịch!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnSaveSettings = MaterialButton(this).apply {
            text = "Lưu Cài Đặt Đầy Đủ"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dpToPxInt()).apply { setMargins(0, 16, 0, 16) }
            setOnClickListener {
                settings.apiKey = inputApiKey.text.toString().trim()
                val selModelIdx = spinnerModel.selectedItemPosition
                if (loadedModels.isNotEmpty() && selModelIdx in loadedModels.indices) {
                    settings.model = loadedModels[selModelIdx].id
                }

                val intLangKeys = SettingsStore.INTERFACE_LANGUAGES.keys.toList()
                val selIntLangIdx = spinnerInterfaceLang.selectedItemPosition
                if (selIntLangIdx in intLangKeys.indices) {
                    settings.appInterfaceLang = intLangKeys[selIntLangIdx]
                }

                val inc = langIncoming.text.toString().trim()
                if (inc in Languages.LIST) {
                    settings.defaultTargetLang = inc
                    settings.learningLanguage = inc
                }

                val out = langOutgoing.text.toString().trim()
                if (out in Languages.LIST) settings.outgoingTargetLang = out

                settings.customStyleInstruction = inputStyleInstruction.text.toString().trim()
                settings.primaryColor = selectedColorHex

                Toast.makeText(this@MainActivity, "Đã lưu toàn bộ cài đặt!", Toast.LENGTH_SHORT).show()
                setupNavigationLabels()
            }
        }

        layout.addView(txtTitle)
        layout.addView(lblApiKey)
        layout.addView(inputApiKey)
        layout.addView(lblModel)
        layout.addView(modelLayout)
        layout.addView(progressModels)
        layout.addView(txtModelHint)
        layout.addView(lblLangHeader)
        layout.addView(lblInterfaceLang)
        layout.addView(spinnerInterfaceLang)
        layout.addView(lblIncoming)
        layout.addView(langIncoming)
        layout.addView(lblOutgoing)
        layout.addView(langOutgoing)
        layout.addView(lblStyleHeader)
        layout.addView(inputStyleInstruction)
        layout.addView(lblThemeHeader)
        layout.addView(radioGroupTheme)
        layout.addView(opacityLayout)
        layout.addView(seekBarOpacity)
        layout.addView(lblPalette)
        layout.addView(colorPaletteContainer)
        layout.addView(btnClearHistory)
        layout.addView(btnSaveSettings)

        containerContent.addView(layout)
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    }

    private fun Int.dpToPxInt(): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics).toInt()
    }

    companion object {
        var serviceRunning = false
    }
}
