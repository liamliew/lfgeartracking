package com.lfcreative.geartracker.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lfcreative.geartracker.core.ScanInput
import com.lfcreative.geartracker.core.scan.ScanInputBus
import com.lfcreative.geartracker.data.outbox.EventOutbox
import com.lfcreative.geartracker.data.settings.SessionSettings
import com.lfcreative.geartracker.feedback.FeedbackSink
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class PackUiState(
    val machine: PackMoveMachine.State = PackMoveMachine.State(),
    val remainingMs: Long? = null,
    /** Set when an unknown tag was scanned; the screen asks what to do with it. */
    val decisionFor: String? = null,
    val lastFeedback: FeedbackKind? = null,
    /** Bumps on every cue so the screen can re-flash the same colour. */
    val feedbackSeq: Int = 0,
)

@HiltViewModel
class PackMoveViewModel @Inject constructor(
    private val bus: ScanInputBus,
    private val resolver: TagResolving,
    private val outbox: EventOutbox,
    private val feedback: FeedbackSink,
    settings: SessionSettings,
    private val clock: Clock,
) : ViewModel() {

    private val machine = PackMoveMachine(PackMoveMachine.Config(idleTimeoutMs = settings.idleTimeoutSec * 1000L))
    private val sessionId = UUID.randomUUID().toString()
    private val _ui = MutableStateFlow(PackUiState())
    val ui: StateFlow<PackUiState> = _ui.asStateFlow()

    // Events are written by one consumer, in order, so an undo can never land before the move it reverses.
    private val outboxQueue = Channel<List<Pair<PlannedEvent, Instant>>>(Channel.UNLIMITED)
    private var lastOccurredMs = 0L

    init {
        viewModelScope.launch {
            for (batch in outboxQueue) batch.forEach { (event, at) -> outbox.enqueue(event.type, fields(event), at) }
        }
        // Sequential on purpose: scans are resolved and applied in the order they were made.
        viewModelScope.launch { bus.scans.collect { dispatch(PackMoveMachine.Input.Scanned(resolver.resolve(it.value))) } }
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                dispatch(PackMoveMachine.Input.Tick)
            }
        }
    }

    fun submitScan(input: ScanInput) {
        bus.submit(input)
    }

    fun onDone() = dispatch(PackMoveMachine.Input.Done)
    fun onClearBasket() = dispatch(PackMoveMachine.Input.ClearBasket)
    fun onUndo() = dispatch(PackMoveMachine.Input.Undo)
    fun onNest() = dispatch(PackMoveMachine.Input.ConfirmNest)
    fun dismissDecision() = _ui.update { it.copy(decisionFor = null) }

    private fun dispatch(input: PackMoveMachine.Input) {
        val now = clock.millis()
        val transition = machine.reduce(_ui.value.machine, input, now)
        _ui.update { it.copy(machine = transition.state, remainingMs = machine.remainingMs(transition.state, now)) }
        for (effect in transition.effects) {
            when (effect) {
                is PackMoveMachine.Effect.Feedback -> {
                    feedback.play(effect.kind)
                    _ui.update { it.copy(lastFeedback = effect.kind, feedbackSeq = it.feedbackSeq + 1) }
                }
                is PackMoveMachine.Effect.Emit -> {
                    // Strictly increasing, so same-millisecond events keep their order on the server.
                    val batch = effect.events.map { e ->
                        lastOccurredMs = maxOf(now, lastOccurredMs + 1)
                        e to Instant.ofEpochMilli(lastOccurredMs)
                    }
                    outboxQueue.trySend(batch)
                }
                is PackMoveMachine.Effect.NeedsDecision -> _ui.update { it.copy(decisionFor = effect.value) }
            }
        }
    }

    private fun fields(e: PlannedEvent) = buildJsonObject {
        put("asset_id", JsonPrimitive(e.assetId))
        e.toLocationId?.let { put("to_location_id", JsonPrimitive(it)) }
        e.toAssetId?.let { put("to_asset_id", JsonPrimitive(it)) }
        put("session_id", JsonPrimitive(sessionId))
        put("session_mode", JsonPrimitive("pack"))
    }

    private companion object { const val TICK_MS = 500L }
}
