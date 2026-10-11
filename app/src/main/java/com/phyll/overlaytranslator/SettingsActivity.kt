package com.phyll.overlaytranslator

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.phyll.overlaytranslator.data.FlashcardItem
import com.phyll.overlaytranslator.data.Languages
import com.phyll.overlaytranslator.data.SettingsStore
import com.phyll.overlaytranslator.network.GeminiClient
import com.phyll.overlaytranslator.network.GeminiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var geminiClient: GeminiClient
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var inputApiKey: EditText
    private lateinit var spinnerModel: Spinner
    private lateinit var btnLoadModels: MaterialButton
    private lateinit var txtModelHint: TextView
    private lateinit var progressModels: ProgressBar
    private lateinit var langIncoming: AutoCompleteTextView
    private lateinit var langOutgoing: AutoCompleteTextView
    private lateinit var inputStyleInstruction: EditText
    private lateinit var radioGroupTheme: RadioGroup
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var txtOpacityValue: TextView
    private lateinit var seekBarOpacity: SeekBar
    private lateinit var btnPracticeFlashcards: MaterialButton
    private lateinit var vocabularyContainer: LinearLayout
    private lateinit var btnClearHistory: MaterialButton
    private lateinit var historyContainer: LinearLayout
    private lateinit var btnSave: MaterialButton

    private var loadedModels: List<GeminiModel> = emptyList()
    private var selectedColorHex: String = SettingsStore.DEFAULT_COLOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(settings.themeMode)

        setContentView(R.layout.activity_settings)

        geminiClient = GeminiClient()
        selectedColorHex = settings.primaryColor

        inputApiKey = findViewById(R.id.inputApiKey)
        spinnerModel = findViewById(R.id.spinnerModel)
        btnLoadModels = findViewById(R.id.btnLoadModels)
        txtModelHint = findViewById(R.id.txtModelHint)
        progressModels = findViewById(R.id.progressModels)
        langIncoming = findViewById(R.id.settingsLangIncoming)
        langOutgoing = findViewById(R.id.settingsLangOutgoing)
        inputStyleInstruction = findViewById(R.id.inputStyleInstruction)
        radioGroupTheme = findViewById(R.id.radioGroupTheme)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        txtOpacityValue = findViewById(R.id.txtOpacityValue)
        seekBarOpacity = findViewById(R.id.seekBarOpacity)
        btnPracticeFlashcards = findViewById(R.id.btnPracticeFlashcards)
        vocabularyContainer = findViewById(R.id.vocabularyContainer)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyContainer = findViewById(R.id.historyContainer)
        btnSave = findViewById(R.id.btnSave)

        setupLangSelectors()
        setupThemeRadioGroup()
        setupColorPalette()
        setupOpacitySeekBar()
        setupVocabularyList()
        setupHistoryList()
        loadSettings()

        btnLoadModels.setOnClickListener { fetchModels() }
        btnPracticeFlashcards.setOnClickListener { startFlashcardPractice() }
        btnClearHistory.setOnClickListener {
            settings.clearHistory()
            setupHistoryList()
            Toast.makeText(this, "Đã xóa toàn bộ lịch sử!", Toast.LENGTH_SHORT).show()
        }
        btnSave.setOnClickListener { saveSettings() }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun setupLangSelectors() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            Languages.LIST
        )
        langIncoming.setAdapter(adapter)
        langIncoming.threshold = 1
        langOutgoing.setAdapter(adapter)
        langOutgoing.threshold = 1
    }

    private fun setupThemeRadioGroup() {
        val radioSystem = findViewById<RadioButton>(R.id.radioThemeSystem)
        val radioLight = findViewById<RadioButton>(R.id.radioThemeLight)
        val radioDark = findViewById<RadioButton>(R.id.radioThemeDark)

        when (settings.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> radioLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> radioDark.isChecked = true
            else -> radioSystem.isChecked = true
        }

        radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settings.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun setupOpacitySeekBar() {
        val currentProgress = (settings.overlayOpacity * 100).toInt().coerceIn(30, 100)
        seekBarOpacity.progress = currentProgress
        txtOpacityValue.text = "$currentProgress%"

        seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val safeProgress = progress.coerceAtLeast(30)
                txtOpacityValue.text = "$safeProgress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val safeProgress = (seekBar?.progress ?: 95).coerceAtLeast(30)
                seekBar?.progress = safeProgress
                settings.overlayOpacity = safeProgress / 100f
            }
        })
    }

    private fun setupColorPalette() {
        colorPaletteContainer.removeAllViews()

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics
        ).toInt()
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics
        ).toInt()

        SettingsStore.COLOR_PALETTE.forEach { hex ->
            val colorView = View(this)
            val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                setMargins(0, 0, marginPx, 0)
            }
            colorView.layoutParams = params

            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(Color.parseColor(hex))

            if (hex.equals(selectedColorHex, ignoreCase = true)) {
                val strokeWidth = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
                ).toInt()
                drawable.setStroke(strokeWidth, Color.BLACK)
            }

            colorView.background = drawable
            colorView.setOnClickListener {
                selectedColorHex = hex
                setupColorPalette()
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    private fun setupVocabularyList() {
        vocabularyContainer.removeAllViews()
        val fcList = settings.getFlashcardList()

        if (fcList.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "Chưa có thẻ từ vựng nào được lưu."
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 8, 0, 16)
            }
            vocabularyContainer.addView(emptyTxt)
            return
        }

        fcList.take(50).forEach { item ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics)
                cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics)
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }

            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

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
                    setupVocabularyList()
                }
            }

            headerLayout.addView(txtSrc)
            headerLayout.addView(btnDel)

            val txtTrans = TextView(this).apply {
                text = "➜ ${item.translation}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.primary))
                setPadding(0, 4, 0, 4)
            }

            val txtSRSLevel = TextView(this).apply {
                text = "Level SRS: ${item.level}/5 | Đã ôn: ${item.reviewCount} lần"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 0, 0, 6)
            }

            layout.addView(headerLayout)
            layout.addView(txtTrans)
            layout.addView(txtSRSLevel)

            if (item.analysis.isNotBlank()) {
                val txtAnalysis = TextView(this).apply {
                    text = item.analysis
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                }
                layout.addView(txtAnalysis)
            }

            card.addView(layout)
            vocabularyContainer.addView(card)
        }
    }

    private fun setupHistoryList() {
        historyContainer.removeAllViews()
        val historyList = settings.getHistoryList()

        if (historyList.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "Lịch sử dịch trống."
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 8, 0, 16)
            }
            historyContainer.addView(emptyTxt)
            return
        }

        historyList.take(30).forEach { item ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics)
                cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
            }

            val txtSrc = TextView(this).apply {
                text = "📜 ${item.sourceText}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            }

            val txtTrans = TextView(this).apply {
                text = "➜ ${item.translation}"
                textSize = 14f
                setTextColor(getColor(R.color.primary))
                setPadding(0, 2, 0, 2)
            }

            layout.addView(txtSrc)
            layout.addView(txtTrans)

            card.addView(layout)
            historyContainer.addView(card)
        }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
            ).apply { setMargins(0, 16, 0, 16) }
            radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics)
            cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
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
                setupVocabularyList()
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

    private fun loadSettings() {
        inputApiKey.setText(settings.apiKey)
        langIncoming.setText(settings.defaultTargetLang, false)
        langOutgoing.setText(settings.outgoingTargetLang, false)
        inputStyleInstruction.setText(settings.customStyleInstruction)

        val savedModel = settings.model
        if (savedModel.isNotEmpty()) {
            val modelList = listOf(savedModel)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelList)
            spinnerModel.adapter = adapter
        }

        if (settings.apiKey.isNotEmpty()) {
            fetchModels()
        }
    }

    private fun fetchModels() {
        val apiKey = inputApiKey.text.toString().trim()
        if (apiKey.isEmpty()) {
            txtModelHint.text = "Nhập API Key trước khi bấm Load Models"
            txtModelHint.visibility = View.VISIBLE
            return
        }

        btnLoadModels.isEnabled = false
        progressModels.visibility = View.VISIBLE
        txtModelHint.text = "Đang tải danh sách models..."
        txtModelHint.visibility = View.VISIBLE

        activityScope.launch {
            try {
                loadedModels = geminiClient.fetchModels(apiKey)
                progressModels.visibility = View.GONE
                btnLoadModels.isEnabled = true

                if (loadedModels.isEmpty()) {
                    txtModelHint.text = "Không tìm thấy models nào"
                } else {
                    txtModelHint.text = "Đã tải ${loadedModels.size} models"
                }

                val displayNames = loadedModels.map { it.displayName }
                val adapter = ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    displayNames
                )
                spinnerModel.adapter = adapter

                val savedModel = settings.model
                val savedIndex = loadedModels.indexOfFirst { it.id == savedModel }
                if (savedIndex >= 0) {
                    spinnerModel.setSelection(savedIndex)
                }
            } catch (e: Exception) {
                progressModels.visibility = View.GONE
                btnLoadModels.isEnabled = true
                txtModelHint.text = "Lỗi: ${e.message}"
            }
        }
    }

    private fun saveSettings() {
        settings.apiKey = inputApiKey.text.toString().trim()

        val selectedModelIndex = spinnerModel.selectedItemPosition
        if (loadedModels.isNotEmpty() && selectedModelIndex in loadedModels.indices) {
            settings.model = loadedModels[selectedModelIndex].id
        }

        val incLang = langIncoming.text.toString().trim()
        if (incLang in Languages.LIST) {
            settings.defaultTargetLang = incLang
        }

        val outLang = langOutgoing.text.toString().trim()
        if (outLang in Languages.LIST) {
            settings.outgoingTargetLang = outLang
        }

        val opacityProgress = seekBarOpacity.progress.coerceAtLeast(30)
        settings.overlayOpacity = opacityProgress / 100f
        settings.customStyleInstruction = inputStyleInstruction.text.toString().trim()
        settings.primaryColor = selectedColorHex

        Toast.makeText(this, "Đã lưu cài đặt!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
