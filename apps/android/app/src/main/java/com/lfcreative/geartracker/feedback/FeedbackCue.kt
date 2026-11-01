package com.lfcreative.geartracker.feedback

import com.lfcreative.geartracker.session.FeedbackKind

enum class CueColor { GREEN, BLUE, GREY, AMBER, RED }
enum class CueIcon { CHECK, TARGET, SWITCH, CLOSE, REPEAT, QUESTION, ERROR, UNDO, CLOCK }
enum class Sound { HIGH_BEEP, RISING_TWO, RISING_THREE, FALLING_TWO, LOW_BLIP, DOUBLE_LOW, BUZZ }

/** A vibration pulse: [SHORT] or [LONG] milliseconds. */
object Pulse { const val SHORT = 80L; const val LONG = 400L }

/**
 * Colour + icon + text are ALWAYS shown (never colour alone); sound and vibration are extra channels.
 * Table follows the spec in docs/plan.md section 6.
 */
data class FeedbackCue(
    val color: CueColor,
    val icon: CueIcon,
    val label: String,
    val sound: Sound,
    val pulses: List<Long>,
)

object FeedbackCues {
    fun forKind(kind: FeedbackKind): FeedbackCue = when (kind) {
        FeedbackKind.ACCEPTED -> FeedbackCue(CueColor.GREEN, CueIcon.CHECK, "Added", Sound.HIGH_BEEP, listOf(Pulse.SHORT))
        FeedbackKind.TARGET_SET -> FeedbackCue(CueColor.BLUE, CueIcon.TARGET, "Target set", Sound.RISING_TWO, listOf(Pulse.SHORT, Pulse.SHORT))
        FeedbackKind.TARGET_SWITCHED ->
            FeedbackCue(CueColor.BLUE, CueIcon.SWITCH, "Target switched", Sound.RISING_THREE, listOf(Pulse.SHORT, Pulse.SHORT, Pulse.SHORT))
        FeedbackKind.TARGET_CLOSED -> FeedbackCue(CueColor.GREY, CueIcon.CLOSE, "Target closed", Sound.FALLING_TWO, listOf(Pulse.LONG))
        FeedbackKind.DUPLICATE -> FeedbackCue(CueColor.AMBER, CueIcon.REPEAT, "Already scanned", Sound.LOW_BLIP, emptyList())
        FeedbackKind.NEEDS_DECISION -> FeedbackCue(CueColor.AMBER, CueIcon.QUESTION, "Unknown tag: choose", Sound.DOUBLE_LOW, listOf(Pulse.LONG, Pulse.LONG))
        FeedbackKind.ERROR -> FeedbackCue(CueColor.RED, CueIcon.ERROR, "Not allowed", Sound.BUZZ, listOf(Pulse.SHORT, Pulse.SHORT, Pulse.SHORT))
        FeedbackKind.UNDONE -> FeedbackCue(CueColor.GREY, CueIcon.UNDO, "Undone", Sound.LOW_BLIP, listOf(Pulse.SHORT))
        FeedbackKind.IDLE_WARNING -> FeedbackCue(CueColor.AMBER, CueIcon.CLOCK, "Closing soon", Sound.LOW_BLIP, emptyList())
    }
}
