package com.speedalert.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Plays the beep / voice / vibration alerts. Music is ducked (lowered) while speaking. */
class AlertManager(context: Context) : TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (e: RuntimeException) {
        null
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        engine.language = Locale.US
        engine.setAudioAttributes(speechAttributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                audioManager?.abandonAudioFocusRequest(focusRequest)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager?.abandonAudioFocusRequest(focusRequest)
            }
        })
        ttsReady = true
    }

    fun limitChanged(newLimit: Int, previous: Int?, beep: Boolean, voice: Boolean, vibrate: Boolean) {
        if (beep) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        if (vibrate) vibrate(longArrayOf(0, 120, 100, 120))
        if (voice) {
            val text = when {
                previous == null -> "Speed limit $newLimit"
                newLimit < previous -> "Speed limit drops to $newLimit"
                else -> "Speed limit $newLimit"
            }
            say(text, if (beep) 450L else 0L)
        }
    }

    fun speeding(limit: Int, overBy: Int, beep: Boolean, voice: Boolean, vibrate: Boolean) {
        if (beep) tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        if (vibrate) vibrate(longArrayOf(0, 400, 150, 400))
        if (voice) say("Slow down. $overBy over. Limit $limit", if (beep) 700L else 0L)
    }

    private fun say(text: String, delayMs: Long) {
        handler.postDelayed({
            if (!ttsReady) return@postDelayed
            audioManager?.requestAudioFocus(focusRequest)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speedalert-" + System.nanoTime())
        }, delayMs)
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        tone?.release()
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }
}
