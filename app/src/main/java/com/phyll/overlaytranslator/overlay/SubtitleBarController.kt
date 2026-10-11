package com.phyll.overlaytranslator.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.phyll.overlaytranslator.R
import com.phyll.overlaytranslator.data.HistoryItem
import com.phyll.overlaytranslator.data.SettingsStore
import com.phyll.overlaytranslator.network.GeminiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SubtitleBarController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope
) {
    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_OverlayTranslator)
    private var barView: View? = null
    private val settings = SettingsStore(context)
    private val geminiClient = GeminiClient()

    fun show() {
        if (barView != null) return

        val inflater = LayoutInflater.from(themedContext)
        barView = inflater.inflate(R.layout.overlay_subtitle_bar, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        setupBar(barView!!, params)
        windowManager.addView(barView, params)
    }

    fun dismiss() {
        barView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        barView = null
    }

    private fun setupBar(view: View, params: WindowManager.LayoutParams) {
        val cardRoot = view.findViewById<MaterialCardView>(R.id.cardSubtitleRoot)
        val layoutSubHeader = view.findViewById<View>(R.id.layoutSubHeader)
        val inputIncoming = view.findViewById<EditText>(R.id.inputIncoming)
        val inputOutgoing = view.findViewById<EditText>(R.id.inputOutgoing)
        val btnPasteTranslate = view.findViewById<MaterialButton>(R.id.btnPasteTranslate)
        val btnTranslateCopy = view.findViewById<MaterialButton>(R.id.btnTranslateCopy)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseSub)

        // Apply Glass Morphism tinted with primary color from settings
        val primaryColorInt = try {
            Color.parseColor(settings.primaryColor)
        } catch (_: Exception) {
            Color.parseColor(SettingsStore.DEFAULT_COLOR)
        }
        val glassColor = Color.argb(
            216, // ~85% opacity frosted glass
            Color.red(primaryColorInt),
            Color.green(primaryColorInt),
            Color.blue(primaryColorInt)
        )
        cardRoot.setCardBackgroundColor(glassColor)

        // Make draggable via header
        var downX = 0f
        var downY = 0f
        layoutSubHeader.setOnTouchListener { v, event ->
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
                        windowManager.updateViewLayout(view, params)
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

        btnClose.setOnClickListener {
            dismiss()
        }

        // Paste & Dịch (Incoming)
        btnPasteTranslate.setOnClickListener {
            var text = inputIncoming.text.toString().trim()
            if (text.isEmpty()) {
                val clipboard = themedContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    text = clip.getItemAt(0).coerceToText(themedContext).toString()
                    inputIncoming.setText(text)
                }
            }

            if (text.isNotEmpty()) {
                btnPasteTranslate.isEnabled = false
                scope.launch {
                    try {
                        val recentHistory = settings.getHistoryList().take(5)
                        val res = geminiClient.translate(
                            text = text,
                            targetLang = settings.defaultTargetLang,
                            apiKey = settings.apiKey,
                            model = settings.model,
                            styleInstruction = settings.customStyleInstruction,
                            isOutgoingMode = false,
                            personaStyle = settings.favoritePersonaStyle,
                            recentHistory = recentHistory
                        )
                        btnPasteTranslate.isEnabled = true
                        if (res.translation.isNotEmpty()) {
                            // Replace input text with translation result directly
                            inputIncoming.setText(res.translation)

                            // Also copy result to clipboard
                            val clipboard = themedContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("translation", res.translation))

                            // Save to History
                            val historyItem = HistoryItem(
                                sourceText = text,
                                translation = res.translation,
                                phonetics = res.phonetics,
                                analysis = res.analysis,
                                targetLang = settings.defaultTargetLang,
                                isOutgoing = false
                            )
                            settings.saveHistory(historyItem)
                            Toast.makeText(themedContext, "Đã dịch & copy!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        btnPasteTranslate.isEnabled = true
                        Toast.makeText(themedContext, "Lỗi dịch: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(themedContext, "Clipboard trống", Toast.LENGTH_SHORT).show()
            }
        }

        // Dịch & Copy (Outgoing)
        btnTranslateCopy.setOnClickListener {
            val text = inputOutgoing.text.toString().trim()
            if (text.isNotEmpty()) {
                btnTranslateCopy.isEnabled = false
                scope.launch {
                    try {
                        val recentHistory = settings.getHistoryList().take(5)
                        val res = geminiClient.translate(
                            text = text,
                            targetLang = settings.outgoingTargetLang,
                            apiKey = settings.apiKey,
                            model = settings.model,
                            styleInstruction = settings.customStyleInstruction,
                            isOutgoingMode = true,
                            sourceLang = "Vietnamese",
                            personaStyle = settings.favoritePersonaStyle,
                            recentHistory = recentHistory
                        )
                        btnTranslateCopy.isEnabled = true
                        if (res.translation.isNotEmpty()) {
                            // Replace input text with translation result directly
                            inputOutgoing.setText(res.translation)

                            // Copy to clipboard
                            val clipboard = themedContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("translation", res.translation))

                            // Save to History
                            val historyItem = HistoryItem(
                                sourceText = text,
                                translation = res.translation,
                                phonetics = res.phonetics,
                                analysis = res.analysis,
                                targetLang = settings.outgoingTargetLang,
                                isOutgoing = true
                            )
                            settings.saveHistory(historyItem)
                            Toast.makeText(themedContext, "Đã dịch & copy!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        btnTranslateCopy.isEnabled = true
                        Toast.makeText(themedContext, "Lỗi dịch: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(themedContext, "Vui lòng nhập nội dung cần dịch", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
