package com.phyll.overlaytranslator.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.phyll.overlaytranslator.BuildConfig
import com.phyll.overlaytranslator.MainActivity
import com.phyll.overlaytranslator.R
import com.phyll.overlaytranslator.SettingsActivity
import com.phyll.overlaytranslator.data.HistoryItem
import com.phyll.overlaytranslator.data.Languages
import com.phyll.overlaytranslator.data.SettingsStore
import com.phyll.overlaytranslator.data.VocabularyItem
import com.phyll.overlaytranslator.network.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class OverlayViewController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope
) {
    private val themedContext: Context =
        ContextThemeWrapper(context, R.style.Theme_OverlayTranslator)

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var dismissView: View? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var bubbleX = 0
    private var bubbleY = 200

    private val geminiClient = GeminiClient()
    private val settings = SettingsStore(context)
    private var translateJob: Job? = null

    private var tts: TextToSpeech? = null
    private var lastBubbleTapTime = 0L

    private val tapThresholdPx = dpToPx(10f)
    private val tapTimeThresholdMs = 200L

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Initialized
                }
            }
        } catch (_: Exception) {}
    }

    fun create() {
        showBubble()
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null

        removeDismissTarget()
        removeBubble()
        removePanel()
    }

    private fun parseLanguageToLocale(langName: String): Locale {
        val clean = langName.lowercase()
        return when {
            clean.contains("vietnamese") || clean.contains("việt") -> Locale("vi", "VN")
            clean.contains("english") || clean.contains("anh") -> Locale.US
            clean.contains("chinese") || clean.contains("trung") -> Locale.SIMPLIFIED_CHINESE
            clean.contains("japanese") || clean.contains("nhật") -> Locale.JAPANESE
            clean.contains("korean") || clean.contains("hàn") -> Locale.KOREAN
            clean.contains("thai") || clean.contains("thái") -> Locale("th", "TH")
            clean.contains("french") || clean.contains("pháp") -> Locale.FRENCH
            clean.contains("german") || clean.contains("đức") -> Locale.GERMAN
            clean.contains("spanish") || clean.contains("tây ban nha") -> Locale("es", "ES")
            clean.contains("russian") || clean.contains("nga") -> Locale("ru", "RU")
            clean.contains("indonesian") -> Locale("id", "ID")
            else -> Locale.getDefault()
        }
    }

    private fun speakText(text: String, langName: String) {
        if (text.isBlank()) return
        try {
            val locale = parseLanguageToLocale(langName)
            tts?.language = locale
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_SPEAK_ID_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Toast.makeText(themedContext, "Lỗi đọc phát âm: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPrimaryColorInt(): Int {
        return try {
            Color.parseColor(settings.primaryColor)
        } catch (_: Exception) {
            Color.parseColor(SettingsStore.DEFAULT_COLOR)
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(themedContext)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val primaryColorInt = getPrimaryColorInt()
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(primaryColorInt)
        }
        bubbleView?.background = bgDrawable

        val params = WindowManager.LayoutParams(
            dpToPx(56f).toInt(),
            dpToPx(56f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }

        setupBubbleTouchListener(bubbleView!!, params)
        bubbleView?.alpha = settings.overlayOpacity
        windowManager.addView(bubbleView, params)
    }

    private fun removeBubble() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null
    }

    private fun showDismissTarget() {
        if (dismissView != null) return

        val inflater = LayoutInflater.from(themedContext)
        dismissView = inflater.inflate(R.layout.overlay_dismiss_target, null)

        val sizePx = dpToPx(64f).toInt()
        dismissParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(48f).toInt()
        }

        dismissView?.alpha = 0f
        windowManager.addView(dismissView, dismissParams)
        dismissView?.animate()?.alpha(1f)?.setDuration(150)?.start()
    }

    private fun removeDismissTarget() {
        dismissView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dismissView = null
        dismissParams = null
    }

    private fun showPanel() {
        if (panelView != null) return

        val inflater = LayoutInflater.from(themedContext)
        panelView = inflater.inflate(R.layout.overlay_panel, null)

        val metrics = context.resources.displayMetrics
        val panelWidth = (metrics.widthPixels * 0.94f).toInt().coerceAtMost(dpToPx(420f).toInt())

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (bubbleY - dpToPx(30f)).toInt().coerceIn(dpToPx(40f).toInt(), (metrics.heightPixels * 0.45f).toInt())
        }

        // Tap outside to collapse back to bubble
        panelView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                collapse()
                true
            } else {
                false
            }
        }

        // Draggable floating header
        val layoutHeader = panelView?.findViewById<View>(R.id.layoutHeader)
        var downX = 0f
        var downY = 0f
        layoutHeader?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    params.x += dx.toInt()
                    params.y += dy.toInt()
                    try {
                        windowManager.updateViewLayout(panelView, params)
                    } catch (_: Exception) {}
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        setupPanel(panelView!!)
        panelView?.findViewById<View>(R.id.cardPanel)?.alpha = settings.overlayOpacity
        windowManager.addView(panelView, params)
    }

    private fun removePanel() {
        translateJob?.cancel()
        panelView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        panelView = null
    }

    private fun expand() {
        if (isExpanded) return
        isExpanded = true

        bubbleView?.let { bv ->
            val lp = bv.layoutParams as WindowManager.LayoutParams
            bubbleX = lp.x
            bubbleY = lp.y
        }
        removeBubble()
        showPanel()
    }

    private fun expandWithQuickPaste() {
        expand()
        panelView?.postDelayed({
            panelView?.let { panel ->
                val btnPaste = panel.findViewById<MaterialButton>(R.id.btnPaste)
                btnPaste?.performClick()
            }
        }, 150L)
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        removePanel()
        showBubble()
    }

    private fun setupBubbleTouchListener(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var downTime = 0L
        var isDragging = false

        view.setOnTouchListener { _, event ->
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val dismissCenterX = screenWidth / 2f
            val dismissCenterY = screenHeight - dpToPx(48f) - dpToPx(32f)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downTime = System.currentTimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val totalMove = Math.hypot(
                        (event.rawX - downRawX).toDouble(),
                        (event.rawY - downRawY).toDouble()
                    )
                    if (totalMove > tapThresholdPx) {
                        if (!isDragging) {
                            isDragging = true
                            showDismissTarget()
                        }
                    }

                    if (isDragging) {
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager.updateViewLayout(view, params)
                        downX = event.rawX
                        downY = event.rawY

                        val bubbleCenterX = params.x + dpToPx(28f)
                        val bubbleCenterY = params.y + dpToPx(28f)
                        val dist = Math.hypot(
                            (bubbleCenterX - dismissCenterX).toDouble(),
                            (bubbleCenterY - dismissCenterY).toDouble()
                        )
                        val dismissThreshold = dpToPx(110f)

                        dismissView?.let { dv ->
                            val container = dv.findViewById<View>(R.id.dismissContainer)
                            if (dist < dismissThreshold) {
                                dv.scaleX = 1.3f
                                dv.scaleY = 1.3f
                                val bg = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.parseColor("#E74C3C"))
                                }
                                container?.background = bg
                            } else {
                                dv.scaleX = 1.0f
                                dv.scaleY = 1.0f
                                container?.setBackgroundResource(R.drawable.bg_dismiss_target)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    val totalMove = Math.hypot(
                        (event.rawX - downRawX).toDouble(),
                        (event.rawY - downRawY).toDouble()
                    )

                    val bubbleCenterX = params.x + dpToPx(28f)
                    val bubbleCenterY = params.y + dpToPx(28f)
                    val dist = Math.hypot(
                        (bubbleCenterX - dismissCenterX).toDouble(),
                        (bubbleCenterY - dismissCenterY).toDouble()
                    )
                    val dismissThreshold = dpToPx(110f)

                    if (isDragging && dist < dismissThreshold) {
                        removeDismissTarget()
                        removeBubble()
                        val stopIntent = Intent(context, OverlayBubbleService::class.java).apply {
                            action = OverlayBubbleService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } else if (!isDragging && totalMove < tapThresholdPx && elapsed < tapTimeThresholdMs) {
                        removeDismissTarget()
                        val now = System.currentTimeMillis()
                        if (now - lastBubbleTapTime < 350L) {
                            // Double-tap: quick paste & translate!
                            lastBubbleTapTime = 0L
                            expandWithQuickPaste()
                        } else {
                            lastBubbleTapTime = now
                            view.postDelayed({
                                if (lastBubbleTapTime != 0L && System.currentTimeMillis() - lastBubbleTapTime >= 300L) {
                                    lastBubbleTapTime = 0L
                                    expand()
                                }
                            }, 320L)
                        }
                    } else {
                        removeDismissTarget()
                        if (isDragging) {
                            snapToEdge(view, params)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val bubbleWidth = view.width

        val centerX = params.x + bubbleWidth / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - bubbleWidth

        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 250
        animator.interpolator = OvershootInterpolator(1.5f)
        animator.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
        animator.start()

        bubbleX = targetX
        bubbleY = params.y
    }

    private fun startSpeechToText(inputEditText: EditText) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Toast.makeText(themedContext, "Cần cấp quyền Micro trong ứng dụng chính", Toast.LENGTH_LONG).show()
            context.startActivity(intent)
            return
        }

        try {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(themedContext)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói câu cần dịch...")
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(themedContext, "🎙 Đang nghe...", Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Toast.makeText(themedContext, "Chưa nhận diện được giọng nói", Toast.LENGTH_SHORT).show()
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        inputEditText.setText(text)
                        inputEditText.setSelection(text.length)
                    }
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(themedContext, "Không hỗ trợ nhận diện giọng nói: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPronunciationEvaluation(
        targetText: String,
        targetLang: String,
        txtAnalysis: TextView,
        lblAnalysisHeader: TextView,
        progressBar: ProgressBar
    ) {
        if (targetText.isBlank()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Toast.makeText(themedContext, "Cần cấp quyền Micro trong ứng dụng chính", Toast.LENGTH_LONG).show()
            context.startActivity(intent)
            return
        }

        try {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(themedContext)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Đọc câu mẫu để AI chấm điểm...")
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(themedContext, "🎯 Đang nghe bạn đọc...", Toast.LENGTH_SHORT).show()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Toast.makeText(themedContext, "Chưa nhận diện được giọng đọc", Toast.LENGTH_SHORT).show()
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        progressBar.visibility = View.VISIBLE
                        scope.launch {
                            try {
                                val evalResult = geminiClient.evaluatePronunciation(
                                    targetText = targetText,
                                    spokenText = spokenText,
                                    langName = targetLang,
                                    apiKey = settings.apiKey,
                                    model = settings.model
                                )
                                progressBar.visibility = View.GONE
                                lblAnalysisHeader.visibility = View.VISIBLE
                                txtAnalysis.visibility = View.VISIBLE
                                txtAnalysis.text = "🎯 ĐIỂM PHÁT ÂM AI: ${evalResult.gradeLabel}\n\n🗣 BẠN ĐÃ ĐỌC:\n\"$spokenText\"\n\n🔍 NHẬN XÉT CHI TIẾT:\n${evalResult.feedback}"
                            } catch (e: Exception) {
                                progressBar.visibility = View.GONE
                                Toast.makeText(themedContext, "Lỗi chấm điểm: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    try { speechRecognizer.destroy() } catch (_: Exception) {}
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(themedContext, "Lỗi nhận diện giọng đọc: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPanel(panel: View) {
        panel.findViewById<TextView>(R.id.txtPanelAppTitle)?.text = "Ins Translator to Phyll v${BuildConfig.VERSION_NAME}"
        val btnCollapse = panel.findViewById<ImageButton>(R.id.btnCollapse)
        val btnSettings = panel.findViewById<ImageButton>(R.id.btnSettings)
        val inputSource = panel.findViewById<EditText>(R.id.inputSource)
        val spinnerPersonaStyle = panel.findViewById<Spinner>(R.id.spinnerPersonaStyle)
        val btnCropScan = panel.findViewById<MaterialButton>(R.id.btnCropScan)
        val btnMic = panel.findViewById<MaterialButton>(R.id.btnMic)
        val btnSpeakSource = panel.findViewById<MaterialButton>(R.id.btnSpeakSource)
        val btnEvalSpeech = panel.findViewById<MaterialButton>(R.id.btnEvalSpeech)
        val btnSpeakResult = panel.findViewById<MaterialButton>(R.id.btnSpeakResult)
        val langSourceSelector = panel.findViewById<AutoCompleteTextView>(R.id.langSourceSelector)
        val langSelector = panel.findViewById<AutoCompleteTextView>(R.id.langSelector)
        val txtLangLabel = panel.findViewById<TextView>(R.id.txtLangLabel)
        val btnTranslate = panel.findViewById<MaterialButton>(R.id.btnTranslate)
        val btnPaste = panel.findViewById<MaterialButton>(R.id.btnPaste)
        val progressBar = panel.findViewById<ProgressBar>(R.id.progressBar)
        val txtError = panel.findViewById<TextView>(R.id.txtError)
        val txtResult = panel.findViewById<TextView>(R.id.txtResult)
        val lblAnalysisHeader = panel.findViewById<TextView>(R.id.lblAnalysisHeader)
        val txtAnalysis = panel.findViewById<TextView>(R.id.txtAnalysis)
        val btnBookmark = panel.findViewById<MaterialButton>(R.id.btnBookmark)
        val btnCopy = panel.findViewById<MaterialButton>(R.id.btnCopy)
        val scrim = panel.findViewById<View>(R.id.scrim)
        val tabIncoming = panel.findViewById<TextView>(R.id.tabIncoming)
        val tabOutgoing = panel.findViewById<TextView>(R.id.tabOutgoing)

        val primaryColorInt = getPrimaryColorInt()

        var isOutgoingMode = false

        // Persona Style Adapter
        val personaAdapter = ArrayAdapter(
            themedContext,
            android.R.layout.simple_spinner_dropdown_item,
            SettingsStore.PERSONA_STYLES
        )
        spinnerPersonaStyle.adapter = personaAdapter
        val savedPersonaIndex = SettingsStore.PERSONA_STYLES.indexOf(settings.favoritePersonaStyle)
        if (savedPersonaIndex >= 0) {
            spinnerPersonaStyle.setSelection(savedPersonaIndex)
        }

        spinnerPersonaStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in SettingsStore.PERSONA_STYLES.indices) {
                    settings.favoritePersonaStyle = SettingsStore.PERSONA_STYLES[position]
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Source Language Adapter
        val sourceLangList = listOf("Auto Detect") + Languages.LIST
        val sourceAdapter = ArrayAdapter(
            themedContext,
            android.R.layout.simple_dropdown_item_1line,
            sourceLangList
        )
        langSourceSelector.setAdapter(sourceAdapter)
        langSourceSelector.threshold = 1
        langSourceSelector.setText("Auto Detect", false)

        // Target Language Adapter
        val targetAdapter = ArrayAdapter(
            themedContext,
            android.R.layout.simple_dropdown_item_1line,
            Languages.LIST
        )
        langSelector.setAdapter(targetAdapter)
        langSelector.threshold = 1

        btnCropScan.setOnClickListener {
            Toast.makeText(themedContext, "📸 Mở ứng dụng chính để tải ảnh OCR!", Toast.LENGTH_SHORT).show()
            collapse()
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        btnMic.setOnClickListener {
            startSpeechToText(inputSource)
        }

        btnSpeakSource.setOnClickListener {
            val text = inputSource.text.toString().trim()
            val lang = langSourceSelector.text.toString().trim()
            speakText(text, lang)
        }

        btnSpeakResult.setOnClickListener {
            val text = txtResult.text.toString().trim()
            val lang = langSelector.text.toString().trim()
            speakText(text, lang)
        }

        btnEvalSpeech.setOnClickListener {
            val targetText = txtResult.text.toString().trim()
            val lang = langSelector.text.toString().trim()
            startPronunciationEvaluation(targetText, lang, txtAnalysis, lblAnalysisHeader, progressBar)
        }

        fun executeTranslation(autoCopyResult: Boolean) {
            val text = inputSource.text.toString().trim()
            if (text.isEmpty()) {
                txtError.text = if (isOutgoingMode) "Nhập tin nhắn cần dịch" else "Dán hoặc nhập text cần dịch"
                txtError.visibility = View.VISIBLE
                return
            }

            val targetLang = langSelector.text.toString().trim()
            if (targetLang.isEmpty() || targetLang !in Languages.LIST) {
                txtError.text = "Chọn ngôn ngữ đích hợp lệ"
                txtError.visibility = View.VISIBLE
                return
            }

            val sourceLang = langSourceSelector.text.toString().trim()
            val selectedPersona = SettingsStore.PERSONA_STYLES.getOrElse(spinnerPersonaStyle.selectedItemPosition) { "Mặc định" }

            txtError.visibility = View.GONE
            txtResult.text = ""
            txtAnalysis.text = ""
            lblAnalysisHeader.visibility = View.GONE
            txtAnalysis.visibility = View.GONE
            btnCopy.visibility = View.GONE
            btnBookmark.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            btnTranslate.isEnabled = false
            btnPaste.isEnabled = false

            translateJob?.cancel()
            translateJob = scope.launch {
                try {
                    val recentHistory = settings.getHistoryList().take(5)
                    val result = geminiClient.translate(
                        text = text,
                        targetLang = targetLang,
                        apiKey = settings.apiKey,
                        model = settings.model,
                        styleInstruction = settings.customStyleInstruction,
                        isOutgoingMode = isOutgoingMode,
                        sourceLang = sourceLang,
                        personaStyle = selectedPersona,
                        recentHistory = recentHistory
                    )
                    progressBar.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    btnPaste.isEnabled = true

                    if (result.translation.isNotEmpty()) {
                        txtResult.text = result.translation
                        btnCopy.visibility = View.VISIBLE
                        btnBookmark.visibility = View.VISIBLE

                        // Save History automatically
                        val historyItem = HistoryItem(
                            sourceText = text,
                            translation = result.translation,
                            phonetics = result.phonetics,
                            analysis = result.analysis,
                            sourceLang = sourceLang,
                            targetLang = targetLang,
                            isOutgoing = isOutgoingMode
                        )
                        settings.saveHistory(historyItem)

                        if (result.detectedLang.isNotBlank() && result.detectedLang != "?") {
                            if (sourceLang == "Auto Detect") {
                                langSourceSelector.setText(result.detectedLang, false)
                            }
                        }

                        val analysisText = when {
                            result.analysis.isNotBlank() && result.phonetics.isNotBlank() -> "🗣 Phiên âm: ${result.phonetics}\n\n${result.analysis}"
                            result.analysis.isNotBlank() -> result.analysis
                            result.phonetics.isNotBlank() -> "🗣 Phiên âm: ${result.phonetics}"
                            else -> ""
                        }

                        txtAnalysis.text = analysisText
                        lblAnalysisHeader.visibility = View.VISIBLE
                        lblAnalysisHeader.text = "💡 Xem giải thích & ngữ pháp ▼"
                        txtAnalysis.visibility = View.GONE

                        if (autoCopyResult) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("translation", result.translation))
                            Toast.makeText(context, "Đã dịch & copy!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        txtError.text = "Không nhận được bản dịch"
                        txtError.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    progressBar.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    btnPaste.isEnabled = true
                    txtError.text = e.message ?: "Dịch thất bại"
                    txtError.visibility = View.VISIBLE
                }
            }
        }

        fun applyMode() {
            txtError.visibility = View.GONE
            txtResult.text = ""
            txtAnalysis.text = ""
            lblAnalysisHeader.visibility = View.GONE
            txtAnalysis.visibility = View.GONE
            btnCopy.visibility = View.GONE
            btnBookmark.visibility = View.GONE
            inputSource.text.clear()

            val selectedBg = GradientDrawable().apply {
                cornerRadius = dpToPx(16f)
                setColor(primaryColorInt)
            }

            val typedValue = TypedValue()
            themedContext.theme.resolveAttribute(R.attr.overlayTextSecondaryColor, typedValue, true)
            val textSecondaryColor = if (typedValue.data != 0) typedValue.data else context.getColor(R.color.text_secondary)

            if (isOutgoingMode) {
                tabOutgoing.background = selectedBg
                tabOutgoing.setTextColor(Color.WHITE)
                tabOutgoing.setTypeface(null, Typeface.BOLD)
                tabIncoming.background = null
                tabIncoming.setTextColor(textSecondaryColor)
                tabIncoming.setTypeface(null, Typeface.NORMAL)

                inputSource.hint = "Soạn tin trả lời..."
                langSourceSelector.setText("Vietnamese", false)
                langSelector.setText(settings.outgoingTargetLang, false)
                btnPaste.text = "Dịch & Copy"
                btnTranslate.text = "Dịch"
            } else {
                tabIncoming.background = selectedBg
                tabIncoming.setTextColor(Color.WHITE)
                tabIncoming.setTypeface(null, Typeface.BOLD)
                tabOutgoing.background = null
                tabOutgoing.setTextColor(textSecondaryColor)
                tabOutgoing.setTypeface(null, Typeface.NORMAL)

                inputSource.hint = "Dán tin nhắn nhận được..."
                langSourceSelector.setText("Auto Detect", false)
                langSelector.setText(settings.defaultTargetLang, false)
                btnPaste.text = "Paste & Dịch"
                btnTranslate.text = "Dịch"
            }
        }

        applyMode()

        tabIncoming.setOnClickListener {
            if (isOutgoingMode) {
                isOutgoingMode = false
                applyMode()
            }
        }

        tabOutgoing.setOnClickListener {
            if (!isOutgoingMode) {
                isOutgoingMode = true
                applyMode()
            }
        }

        langSelector.setOnItemClickListener { _, _, position, _ ->
            val selected = targetAdapter.getItem(position) ?: return@setOnItemClickListener
            if (isOutgoingMode) {
                settings.outgoingTargetLang = selected
            } else {
                settings.defaultTargetLang = selected
            }
        }

        btnCollapse.setOnClickListener { collapse() }

        lblAnalysisHeader.setOnClickListener {
            if (txtAnalysis.visibility == View.VISIBLE) {
                txtAnalysis.visibility = View.GONE
                lblAnalysisHeader.text = "💡 Xem giải thích & ngữ pháp ▼"
            } else {
                val currentText = inputSource.text.toString().trim()
                val currentTrans = txtResult.text.toString().trim()
                val targetLang = langSelector.text.toString().trim()

                if (txtAnalysis.text.isBlank() && currentText.isNotEmpty() && currentTrans.isNotEmpty()) {
                    lblAnalysisHeader.text = "⏳ Đang phân tích ngữ pháp..."
                    scope.launch {
                        try {
                            val analysis = geminiClient.analyzeGrammar(
                                text = currentText,
                                translation = currentTrans,
                                targetLang = targetLang,
                                apiKey = settings.apiKey,
                                model = settings.model
                            )
                            if (analysis.isNotBlank()) {
                                txtAnalysis.text = analysis
                                txtAnalysis.visibility = View.VISIBLE
                                lblAnalysisHeader.text = "💡 Thu gọn giải thích ▲"
                            } else {
                                lblAnalysisHeader.text = "💡 Xem giải thích & ngữ pháp ▼"
                            }
                        } catch (e: Exception) {
                            lblAnalysisHeader.text = "💡 Xem giải thích & ngữ pháp ▼"
                            Toast.makeText(themedContext, "Lỗi phân tích: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    txtAnalysis.visibility = View.VISIBLE
                    lblAnalysisHeader.text = "💡 Thu gọn giải thích ▲"
                }
            }
        }

        scrim?.setOnClickListener { collapse() }

        btnSettings.setOnClickListener {
            collapse()
            val intent = Intent(context, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        btnTranslate.setOnClickListener {
            executeTranslation(autoCopyResult = false)
        }

        btnPaste.setOnClickListener {
            if (isOutgoingMode) {
                // Outgoing mode: Dịch & Copy
                executeTranslation(autoCopyResult = true)
            } else {
                // Incoming mode: Paste & Dịch
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val pastedText = clip.getItemAt(0).coerceToText(context).toString()
                    inputSource.setText(pastedText)
                    inputSource.setSelection(pastedText.length)
                    txtError.visibility = View.GONE
                    executeTranslation(autoCopyResult = false)
                } else {
                    txtError.text = "Clipboard trống"
                    txtError.visibility = View.VISIBLE
                }
            }
        }

        btnBookmark.setOnClickListener {
            val src = inputSource.text.toString().trim()
            val trans = txtResult.text.toString().trim()
            val analysis = txtAnalysis.text.toString().trim()
            if (src.isNotEmpty() && trans.isNotEmpty()) {
                val item = VocabularyItem(
                    sourceText = src,
                    translation = trans,
                    analysis = analysis
                )
                settings.saveVocabulary(item)
                Toast.makeText(context, "⭐ Đã lưu vào Sổ từ vựng!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopy.setOnClickListener {
            val resultText = txtResult.text.toString()
            if (resultText.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("translation", resultText))
                Toast.makeText(context, "Đã copy bản dịch!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
        )
    }
}
