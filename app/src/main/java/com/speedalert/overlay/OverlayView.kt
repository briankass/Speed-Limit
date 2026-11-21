package com.speedalert.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** The floating bubble drawn on top of Google Maps (or any app). */
class OverlayView(private val context: Context, private val prefs: Prefs) {

    enum class Level { UNKNOWN, OK, OVER, SPEEDING }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    @SuppressLint("InflateParams")
    private val root: View = LayoutInflater.from(context).inflate(R.layout.overlay, null)
    private val bubble: View = root.findViewById(R.id.bubble)
    private val sign: LinearLayout = root.findViewById(R.id.sign)
    private val limitText: TextView = root.findViewById(R.id.limitText)
    private val newBadge: TextView = root.findViewById(R.id.newBadge)
    private val speedCircle: LinearLayout = root.findViewById(R.id.speedCircle)
    private val speedText: TextView = root.findViewById(R.id.speedText)
    private val unitText: TextView = root.findViewById(R.id.unitText)
    private val messageText: TextView = root.findViewById(R.id.messageText)
    private val sourceText: TextView = root.findViewById(R.id.sourceText)

    private val signBg = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = 7 * density
    }
    private val circleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.overlayX
        y = prefs.overlayY
    }

    private var attached = false
    private var highlightNewLimit = false
    private var messageIsWarning = false
    private val clearNewLimit = Runnable {
        highlightNewLimit = false
        newBadge.visibility = View.GONE
        applySignStyle()
        if (!messageIsWarning) messageText.visibility = View.GONE
    }

    init {
        sign.background = signBg
        speedCircle.background = circleBg
        applySignStyle()
        setLevel(Level.UNKNOWN)
        setupDragging()
    }

    fun show() {
        if (!attached) {
            wm.addView(root, params)
            attached = true
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (attached) {
            wm.removeView(root)
            attached = false
        }
    }

    /** limit = posted limit (null = no data). offline = can't reach the map server. */
    fun setLimit(limit: Int?, offline: Boolean) {
        limitText.text = limit?.toString() ?: if (offline) "!" else "--"
    }

    /** Small label under the limit showing where it came from (OSM / HERE / TOMTOM). */
    fun setSource(label: String) {
        if (sourceText.text.toString() != label) sourceText.text = label
    }

    fun setSpeed(mph: Double?) {
        speedText.text = mph?.roundToInt()?.toString() ?: "--"
    }

    fun setLevel(level: Level) {
        val (fill, stroke, textColor) = when (level) {
            Level.UNKNOWN -> Triple(Color.WHITE, Color.parseColor("#9E9E9E"), Color.BLACK)
            Level.OK -> Triple(Color.WHITE, Color.parseColor("#2E9E3E"), Color.BLACK)
            Level.OVER -> Triple(Color.parseColor("#FFB300"), Color.parseColor("#E69500"), Color.BLACK)
            Level.SPEEDING -> Triple(Color.parseColor("#D32F2F"), Color.parseColor("#B71C1C"), Color.WHITE)
        }
        circleBg.setColor(fill)
        circleBg.setStroke((4 * density).toInt(), stroke)
        speedText.setTextColor(textColor)
        unitText.setTextColor(if (level == Level.SPEEDING) Color.WHITE else Color.parseColor("#555555"))
    }

    /** Highlights the sign for a few seconds after the limit changes. */
    fun flashNewLimit(previous: Int?, limit: Int) {
        highlightNewLimit = true
        newBadge.visibility = View.VISIBLE
        applySignStyle()
        if (previous != null && !messageIsWarning) {
            val verb = if (limit < previous) "dropped" else "raised"
            showMessage("Speed limit $verb $previous → $limit", warning = false)
        }
        handler.removeCallbacks(clearNewLimit)
        handler.postDelayed(clearNewLimit, 6_000)
    }

    fun showSpeedingMessage(overBy: Int?) {
        if (overBy == null) {
            if (messageIsWarning) {
                messageIsWarning = false
                messageText.visibility = View.GONE
            }
        } else {
            showMessage("⚠ $overBy mph over the limit", warning = true)
        }
    }

    private fun showMessage(text: String, warning: Boolean) {
        messageIsWarning = warning
        messageText.text = text
        messageText.visibility = View.VISIBLE
    }

    private fun applySignStyle() {
        if (highlightNewLimit) {
            signBg.setStroke((4 * density).toInt(), Color.parseColor("#1565C0"))
        } else {
            signBg.setStroke((3 * density).toInt(), Color.BLACK)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > 3 || abs(dy) > 3) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        if (attached) wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.overlayX = params.x
                    prefs.overlayY = params.y
                    true
                }
                else -> false
            }
        }
    }
}
