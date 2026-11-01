package com.lfcreative.geartracker.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.lfcreative.geartracker.data.settings.SettingsRepository
import com.lfcreative.geartracker.session.FeedbackKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface FeedbackSink {
    fun play(kind: FeedbackKind)
}

/**
 * Plays the cue for a [FeedbackKind] using the system tone generator and vibrator, honouring the Sound and
 * Vibration switches in Settings. Tones use DTMF pitches as stand-ins; tune them on a real Zebra.
 */
@Singleton
class FeedbackPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : FeedbackSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (_: RuntimeException) {
        null
    }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun play(kind: FeedbackKind) {
        val cue = FeedbackCues.forKind(kind)
        val s = settings.settings.value
        if (s.vibrationEnabled && cue.pulses.isNotEmpty()) vibrate(cue.pulses)
        if (s.soundEnabled) scope.launch { playSound(cue.sound) }
    }

    private fun vibrate(pulses: List<Long>) {
        val timings = buildList {
            add(0L)
            pulses.forEachIndexed { i, p -> if (i > 0) add(GAP_MS); add(p) }
        }.toLongArray()
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private suspend fun playSound(sound: Sound) {
        val steps: List<Pair<Int, Int>> = when (sound) {
            Sound.HIGH_BEEP -> listOf(ToneGenerator.TONE_DTMF_D to 90)
            Sound.RISING_TWO -> listOf(ToneGenerator.TONE_DTMF_1 to 80, ToneGenerator.TONE_DTMF_D to 80)
            Sound.RISING_THREE -> listOf(ToneGenerator.TONE_DTMF_1 to 70, ToneGenerator.TONE_DTMF_5 to 70, ToneGenerator.TONE_DTMF_D to 70)
            Sound.FALLING_TWO -> listOf(ToneGenerator.TONE_DTMF_D to 90, ToneGenerator.TONE_DTMF_1 to 130)
            Sound.LOW_BLIP -> listOf(ToneGenerator.TONE_DTMF_0 to 120)
            Sound.DOUBLE_LOW -> listOf(ToneGenerator.TONE_DTMF_0 to 100, ToneGenerator.TONE_DTMF_0 to 100)
            Sound.BUZZ -> listOf(ToneGenerator.TONE_CDMA_ABBR_ALERT to 400)
        }
        steps.forEach { (type, ms) ->
            tone?.startTone(type, ms)
            delay(ms + GAP_MS)
        }
    }

    private companion object { const val GAP_MS = 60L }
}
