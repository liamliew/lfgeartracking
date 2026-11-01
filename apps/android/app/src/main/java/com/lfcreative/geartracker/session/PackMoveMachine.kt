package com.lfcreative.geartracker.session

import com.lfcreative.geartracker.session.ResolvedTag.Container
import com.lfcreative.geartracker.session.ResolvedTag.Item
import com.lfcreative.geartracker.session.ResolvedTag.Target

/**
 * Pack / Move scan session as a pure state machine: (state, input, now) -> (state, effects).
 * No Android, no clocks, no I/O, so every row of the transition table is unit tested.
 *
 * Idle      --item-->                  Basket[add]
 * Idle      --target-->                Target(t)
 * Basket    --item-->                  Basket[add | duplicate]
 * Basket    --target-->                Idle[commit basket INTO t]
 * Target(t) --item-->                  Target(t)[place item in t]
 * Target(t) --t again-->               Idle[close]
 * Target(t) --other target u-->        Target(u)[SWITCH, offer NEST for 6 s]
 * Target(t) --ancestor of t-->         reject (cycle)
 * Target(t) --Done / idle timeout-->   Idle
 * any       --Undo-->                  previous state + compensating events
 * any       --unknown tag-->           NeedsDecision (no state change)
 */
class PackMoveMachine(private val config: Config = Config()) {

    data class Config(
        val idleTimeoutMs: Long = 90_000,
        val warnBeforeMs: Long = 10_000,
        val nestOfferMs: Long = 6_000,
        val undoDepth: Int = 20,
    )

    sealed interface Phase {
        data object Idle : Phase
        data class Basket(val items: List<Item>) : Phase
        data class InTarget(val target: Target, val placed: List<Item>) : Phase
    }

    data class NestOffer(val from: Container, val to: Target, val expiresAtMs: Long)

    data class UndoFrame(val phase: Phase, val known: Map<String, Placement>, val compensate: List<PlannedEvent>)

    data class State(
        val phase: Phase = Phase.Idle,
        val nestOffer: NestOffer? = null,
        /** Placements this session has already changed, so undo/duplicate checks never use stale cache data. */
        val known: Map<String, Placement> = emptyMap(),
        val undo: List<UndoFrame> = emptyList(),
        val lastActivityMs: Long = 0,
        val warned: Boolean = false,
        /** Human readable result of the last action ("5 items -> Bin 7"). */
        val message: String? = null,
    )

    sealed interface Input {
        data class Scanned(val tag: ResolvedTag) : Input
        data object Done : Input
        data object ClearBasket : Input
        data object Undo : Input
        data object ConfirmNest : Input
        data object Tick : Input
    }

    sealed interface Effect {
        data class Feedback(val kind: FeedbackKind) : Effect
        data class Emit(val events: List<PlannedEvent>) : Effect
        data class NeedsDecision(val value: String) : Effect
    }

    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun reduce(state: State, input: Input, nowMs: Long): Transition {
        val live = if (input is Input.Tick) state else state.copy(lastActivityMs = nowMs, warned = false)
        val expired = live.nestOffer?.takeIf { nowMs > it.expiresAtMs }
        val s = if (expired != null) live.copy(nestOffer = null) else live
        return when (input) {
            is Input.Scanned -> onScan(s, input.tag, nowMs)
            Input.Done -> onDone(s)
            Input.ClearBasket -> onClear(s)
            Input.Undo -> onUndo(s)
            Input.ConfirmNest -> onNest(s, nowMs)
            Input.Tick -> onTick(s, nowMs)
        }
    }

    /** Milliseconds until the open target auto-closes; null when no target is open. For the countdown ring. */
    fun remainingMs(state: State, nowMs: Long): Long? =
        if (state.phase is Phase.InTarget) (config.idleTimeoutMs - (nowMs - state.lastActivityMs)).coerceAtLeast(0) else null

    // ---- scans ----------------------------------------------------------------------------------------------

    private fun onScan(s: State, tag: ResolvedTag, now: Long): Transition = when (tag) {
        is ResolvedTag.Unknown ->
            Transition(s, listOf(Effect.Feedback(FeedbackKind.NEEDS_DECISION), Effect.NeedsDecision(tag.value)))
        is Item -> onItem(s, tag)
        is Target -> onTarget(s, tag, now)
    }

    private fun onItem(s: State, item: Item): Transition {
        if (item.retired) return reject(s, "${item.name} is retired")
        return when (val p = s.phase) {
            Phase.Idle -> accept(s, Phase.Basket(listOf(item)), message = "1 item in basket")
            is Phase.Basket ->
                if (p.items.any { it.id == item.id }) duplicate(s, "${item.name} is already in the basket")
                else accept(s, Phase.Basket(p.items + item), message = "${p.items.size + 1} items in basket")
            is Phase.InTarget -> {
                if (p.placed.any { it.id == item.id } || isAlreadyIn(s, item, p.target)) {
                    return duplicate(s, "${item.name} is already in ${p.target.name}")
                }
                val event = placeEvent(item.id, p.target)
                val comp = compensation(s, item)
                val phase = p.copy(placed = p.placed + item)
                accept(
                    s, phase, emit = listOf(event), compensate = listOf(comp),
                    known = s.known + (item.id to placementFor(p.target)),
                    message = "${phase.placed.size} in ${p.target.name}",
                )
            }
        }
    }

    private fun onTarget(s: State, t: Target, now: Long): Transition {
        if (t is ResolvedTag.Location && t.archived) return reject(s, "${t.name} is archived")
        if (t is Container && t.retired) return reject(s, "${t.name} is retired")
        return when (val p = s.phase) {
            Phase.Idle -> push(s, Phase.InTarget(t, emptyList()), FeedbackKind.TARGET_SET, message = "Putting items in ${t.name}")

            is Phase.Basket -> {
                val movable = p.items.filter { !isAlreadyIn(s, it, t) }
                if (movable.isEmpty()) return duplicate(s, "Everything is already in ${t.name}")
                val events = movable.map { placeEvent(it.id, t) }
                val comps = movable.map { compensation(s, it) }
                val known = s.known + movable.associate { it.id to placementFor(t) }
                val skipped = p.items.size - movable.size
                val msg = "${movable.size} ${if (movable.size == 1) "item" else "items"} -> ${t.name}" +
                    if (skipped > 0) " ($skipped already there)" else ""
                accept(s, Phase.Idle, emit = events, compensate = comps, known = known, message = msg, restore = p)
            }

            is Phase.InTarget -> when {
                t.id == p.target.id ->
                    push(s, Phase.Idle, FeedbackKind.TARGET_CLOSED, message = "Closed ${p.target.name} (${p.placed.size} added)", restore = p)
                t.id in p.target.ancestorIds -> reject(s, "${p.target.name} is inside ${t.name}; that would make a loop")
                else -> {
                    val from = p.target
                    val offer = (from as? Container)
                        ?.takeIf { canPlace(it, t) }
                        ?.let { NestOffer(it, t, now + config.nestOfferMs) }
                    push(
                        s, Phase.InTarget(t, emptyList()), FeedbackKind.TARGET_SWITCHED,
                        message = "Now putting items in ${t.name}", restore = p, nestOffer = offer,
                    )
                }
            }
        }
    }

    // ---- buttons --------------------------------------------------------------------------------------------

    private fun onDone(s: State): Transition = when (val p = s.phase) {
        is Phase.InTarget -> push(s, Phase.Idle, FeedbackKind.TARGET_CLOSED, message = "Closed ${p.target.name} (${p.placed.size} added)", restore = p)
        is Phase.Basket -> Transition(s.copy(message = "Scan a container or location to place ${p.items.size} item(s)"))
        Phase.Idle -> Transition(s)
    }

    private fun onClear(s: State): Transition = when (val p = s.phase) {
        is Phase.Basket -> push(s, Phase.Idle, FeedbackKind.TARGET_CLOSED, message = "Basket cleared", restore = p)
        else -> Transition(s)
    }

    private fun onUndo(s: State): Transition {
        val frame = s.undo.lastOrNull() ?: return Transition(s.copy(message = "Nothing to undo"))
        val next = s.copy(phase = frame.phase, known = frame.known, undo = s.undo.dropLast(1), nestOffer = null, message = "Undone")
        val effects = buildList {
            add(Effect.Feedback(FeedbackKind.UNDONE))
            if (frame.compensate.isNotEmpty()) add(Effect.Emit(frame.compensate))
        }
        return Transition(next, effects)
    }

    private fun onNest(s: State, now: Long): Transition {
        val offer = s.nestOffer
        val phase = s.phase
        if (offer == null || now > offer.expiresAtMs || phase !is Phase.InTarget || phase.target.id != offer.to.id) {
            return Transition(s.copy(nestOffer = null, message = "Nest offer expired"))
        }
        if (!canPlace(offer.from, offer.to)) return reject(s.copy(nestOffer = null), "That would make a loop")
        val prior = placementOf(s, offer.from.id, offer.from.placement)
        val event = placeEvent(offer.from.id, offer.to)
        val comp = compensationFor(offer.from.id, prior)
        return accept(
            s.copy(nestOffer = null), phase, emit = listOf(event), compensate = listOf(comp),
            known = s.known + (offer.from.id to placementFor(offer.to)),
            message = "${offer.from.name} is now inside ${offer.to.name}",
        )
    }

    private fun onTick(s: State, now: Long): Transition {
        val offerCleared = s.nestOffer?.takeIf { now > it.expiresAtMs }?.let { s.copy(nestOffer = null) } ?: s
        val p = offerCleared.phase as? Phase.InTarget ?: return Transition(offerCleared)
        val idle = now - offerCleared.lastActivityMs
        return when {
            idle >= config.idleTimeoutMs ->
                push(offerCleared, Phase.Idle, FeedbackKind.TARGET_CLOSED, message = "Closed ${p.target.name} (idle)", restore = p)
            idle >= config.idleTimeoutMs - config.warnBeforeMs && !offerCleared.warned ->
                Transition(offerCleared.copy(warned = true), listOf(Effect.Feedback(FeedbackKind.IDLE_WARNING)))
            else -> Transition(offerCleared)
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** Place [x] inside [t]: never into itself or into one of its own descendants. Only containers can form loops. */
    private fun canPlace(x: Container, t: Target) = x.id != t.id && x.id !in t.ancestorIds

    private fun placementOf(s: State, id: String, fallback: Placement) = s.known[id] ?: fallback

    private fun isAlreadyIn(s: State, item: Item, t: Target): Boolean {
        val p = placementOf(s, item.id, item.placement)
        return when (t) {
            is Container -> p.containerId == t.id
            is ResolvedTag.Location -> p.containerId == null && p.locationId == t.id
        }
    }

    private fun placementFor(t: Target) = when (t) {
        is Container -> Placement(containerId = t.id)
        is ResolvedTag.Location -> Placement(locationId = t.id)
    }

    private fun placeEvent(assetId: String, t: Target) = when (t) {
        is Container -> PlannedEvent("placed_in_container", assetId, toAssetId = t.id)
        is ResolvedTag.Location -> PlannedEvent("placed_in_location", assetId, toLocationId = t.id)
    }

    private fun compensation(s: State, item: Item) = compensationFor(item.id, placementOf(s, item.id, item.placement))

    private fun compensationFor(assetId: String, before: Placement) = when {
        before.containerId != null -> PlannedEvent("placed_in_container", assetId, toAssetId = before.containerId)
        before.locationId != null -> PlannedEvent("placed_in_location", assetId, toLocationId = before.locationId)
        else -> PlannedEvent("removed_from_container", assetId)
    }

    private fun frame(s: State, restore: Phase, compensate: List<PlannedEvent>) =
        (s.undo + UndoFrame(restore, s.known, compensate)).takeLast(config.undoDepth)

    private fun accept(
        s: State,
        phase: Phase,
        emit: List<PlannedEvent> = emptyList(),
        compensate: List<PlannedEvent> = emptyList(),
        known: Map<String, Placement> = s.known,
        message: String? = null,
        restore: Phase = s.phase,
    ): Transition {
        val effects = buildList {
            add(Effect.Feedback(FeedbackKind.ACCEPTED))
            if (emit.isNotEmpty()) add(Effect.Emit(emit))
        }
        return Transition(
            s.copy(phase = phase, known = known, undo = frame(s, restore, compensate), message = message),
            effects,
        )
    }

    private fun push(
        s: State,
        phase: Phase,
        kind: FeedbackKind,
        message: String?,
        restore: Phase = s.phase,
        nestOffer: NestOffer? = null,
    ) = Transition(
        s.copy(phase = phase, nestOffer = nestOffer, undo = frame(s, restore, emptyList()), message = message),
        listOf(Effect.Feedback(kind)),
    )

    private fun duplicate(s: State, message: String) =
        Transition(s.copy(message = message), listOf(Effect.Feedback(FeedbackKind.DUPLICATE)))

    private fun reject(s: State, message: String) =
        Transition(s.copy(message = message), listOf(Effect.Feedback(FeedbackKind.ERROR)))
}
