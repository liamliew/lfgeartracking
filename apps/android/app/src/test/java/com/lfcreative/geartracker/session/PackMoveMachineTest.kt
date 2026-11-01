package com.lfcreative.geartracker.session

import com.lfcreative.geartracker.session.PackMoveMachine.Effect
import com.lfcreative.geartracker.session.PackMoveMachine.Input
import com.lfcreative.geartracker.session.PackMoveMachine.Phase
import com.lfcreative.geartracker.session.PackMoveMachine.State
import com.lfcreative.geartracker.session.PackMoveMachine.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun item(n: Int, placement: Placement = Placement(locationId = "L-home"), retired: Boolean = false) =
    ResolvedTag.Item("I$n", "i$n", "A00000$n", "Item $n", placement, retired)

private fun container(id: String, ancestors: Set<String> = emptySet(), placement: Placement = Placement(locationId = "L-home")) =
    ResolvedTag.Container(id.uppercase(), id, "C-$id", "Case $id", ancestors, placement)

private fun location(id: String, ancestors: Set<String> = emptySet(), archived: Boolean = false) =
    ResolvedTag.Location(id.uppercase(), id, "L-$id", "Loc $id", ancestors, id, archived)

/** Drives the machine with an explicit clock and keeps the latest state. */
private class Run(config: PackMoveMachine.Config = PackMoveMachine.Config()) {
    val machine = PackMoveMachine(config)
    var state = State()
    var now = 1_000L
    var last = Transition(state)

    fun send(input: Input, advanceMs: Long = 100): Transition {
        now += advanceMs
        last = machine.reduce(state, input, now)
        state = last.state
        return last
    }
    fun scan(tag: ResolvedTag, advanceMs: Long = 100) = send(Input.Scanned(tag), advanceMs)
    fun feedback() = last.effects.filterIsInstance<Effect.Feedback>().map { it.kind }
    fun emitted() = last.effects.filterIsInstance<Effect.Emit>().flatMap { it.events }
}

class PackMoveMachineTest {

    // ---- Idle ---------------------------------------------------------------------------------------------

    @Test fun `idle + item starts a basket`() {
        val r = Run()
        r.scan(item(1))
        assertEquals(Phase.Basket(listOf(item(1))), r.state.phase)
        assertEquals(listOf(FeedbackKind.ACCEPTED), r.feedback())
        assertTrue(r.emitted().isEmpty())
    }

    @Test fun `idle + container or location opens a target`() {
        listOf(container("cA"), location("l1")).forEach { t ->
            val r = Run()
            r.scan(t)
            assertEquals(Phase.InTarget(t as ResolvedTag.Target, emptyList()), r.state.phase)
            assertEquals(listOf(FeedbackKind.TARGET_SET), r.feedback())
        }
    }

    @Test fun `unknown tag asks for a decision and changes nothing`() {
        val r = Run()
        r.scan(item(1))
        val before = r.state
        r.scan(ResolvedTag.Unknown("999"))
        assertEquals(before.phase, r.state.phase)
        assertEquals(listOf(FeedbackKind.NEEDS_DECISION), r.feedback())
        assertEquals(listOf<Effect>(Effect.Feedback(FeedbackKind.NEEDS_DECISION), Effect.NeedsDecision("999")), r.last.effects)
    }

    @Test fun `retired items and archived locations are refused`() {
        val r = Run()
        r.scan(item(1, retired = true))
        assertEquals(listOf(FeedbackKind.ERROR), r.feedback())
        assertEquals(Phase.Idle, r.state.phase)
        r.scan(location("l1", archived = true))
        assertEquals(listOf(FeedbackKind.ERROR), r.feedback())
        assertEquals(Phase.Idle, r.state.phase)
    }

    // ---- Basket -------------------------------------------------------------------------------------------

    @Test fun `basket collects items and flags duplicates`() {
        val r = Run()
        r.scan(item(1)); r.scan(item(2))
        assertEquals(2, (r.state.phase as Phase.Basket).items.size)
        r.scan(item(1))
        assertEquals(listOf(FeedbackKind.DUPLICATE), r.feedback())
        assertEquals(2, (r.state.phase as Phase.Basket).items.size)
    }

    @Test fun `basket + container commits every item into it`() {
        val r = Run()
        r.scan(item(1)); r.scan(item(2))
        r.scan(container("cA"))
        assertEquals(Phase.Idle, r.state.phase)
        assertEquals(
            listOf(PlannedEvent("placed_in_container", "i1", toAssetId = "cA"), PlannedEvent("placed_in_container", "i2", toAssetId = "cA")),
            r.emitted(),
        )
        assertEquals("2 items -> Case cA", r.state.message)
        assertEquals(listOf(FeedbackKind.ACCEPTED), r.feedback())
    }

    @Test fun `basket + location commits as location placements`() {
        val r = Run()
        r.scan(item(1))
        r.scan(location("l2"))
        assertEquals(listOf(PlannedEvent("placed_in_location", "i1", toLocationId = "l2")), r.emitted())
        assertEquals("1 item -> Loc l2", r.state.message)
    }

    @Test fun `basket skips items already in the destination`() {
        val r = Run()
        r.scan(item(1, Placement(containerId = "cA"))); r.scan(item(2))
        r.scan(container("cA"))
        assertEquals(listOf(PlannedEvent("placed_in_container", "i2", toAssetId = "cA")), r.emitted())
        assertEquals("1 item -> Case cA (1 already there)", r.state.message)
    }

    @Test fun `basket where everything is already there stays put with a duplicate cue`() {
        val r = Run()
        r.scan(item(1, Placement(locationId = "l2")))
        r.scan(location("l2"))
        assertEquals(listOf(FeedbackKind.DUPLICATE), r.feedback())
        assertTrue(r.state.phase is Phase.Basket)
        assertTrue(r.emitted().isEmpty())
    }

    @Test fun `clear discards the basket and can be undone`() {
        val r = Run()
        r.scan(item(1))
        r.send(Input.ClearBasket)
        assertEquals(Phase.Idle, r.state.phase)
        r.send(Input.Undo)
        assertEquals(Phase.Basket(listOf(item(1))), r.state.phase)
    }

    // ---- Target -------------------------------------------------------------------------------------------

    @Test fun `target places items one by one and flags repeats`() {
        val r = Run()
        r.scan(container("cA"))
        r.scan(item(1, Placement(locationId = "l1")))
        assertEquals(listOf(PlannedEvent("placed_in_container", "i1", toAssetId = "cA")), r.emitted())
        r.scan(item(1))
        assertEquals(listOf(FeedbackKind.DUPLICATE), r.feedback())
        assertTrue(r.emitted().isEmpty())
        assertEquals(1, (r.state.phase as Phase.InTarget).placed.size)
    }

    @Test fun `an item already in the open target is a duplicate`() {
        val r = Run()
        r.scan(location("l1"))
        r.scan(item(1, Placement(locationId = "l1")))
        assertEquals(listOf(FeedbackKind.DUPLICATE), r.feedback())
        assertTrue(r.emitted().isEmpty())
    }

    @Test fun `scanning the open target again closes it`() {
        val r = Run()
        r.scan(container("cA")); r.scan(item(1))
        r.scan(container("cA"))
        assertEquals(Phase.Idle, r.state.phase)
        assertEquals(listOf(FeedbackKind.TARGET_CLOSED), r.feedback())
        assertEquals("Closed Case cA (1 added)", r.state.message)
    }

    @Test fun `scanning another target switches and offers to nest`() {
        val r = Run()
        r.scan(container("cA"))
        r.scan(container("cB"))
        assertEquals("cB", (r.state.phase as Phase.InTarget).target.id)
        assertEquals(listOf(FeedbackKind.TARGET_SWITCHED), r.feedback())
        val offer = r.state.nestOffer
        assertNotNull(offer)
        assertEquals("cA", offer!!.from.id); assertEquals("cB", offer.to.id)
        assertTrue(r.emitted().isEmpty()) // switching never moves anything on its own
    }

    @Test fun `switching away from a location offers no nesting`() {
        val r = Run()
        r.scan(location("l1")); r.scan(container("cB"))
        assertNull(r.state.nestOffer)
    }

    @Test fun `scanning an ancestor of the open target is a loop and is rejected`() {
        val r = Run()
        r.scan(container("cIn", ancestors = setOf("cOut")))
        val before = r.state.phase
        r.scan(container("cOut"))
        assertEquals(listOf(FeedbackKind.ERROR), r.feedback())
        assertEquals(before, r.state.phase)
    }

    @Test fun `confirming the nest offer puts the old target inside the new one`() {
        val r = Run()
        r.scan(container("cA")); r.scan(container("cB"))
        r.send(Input.ConfirmNest)
        assertEquals(listOf(PlannedEvent("placed_in_container", "cA", toAssetId = "cB")), r.emitted())
        assertNull(r.state.nestOffer)
        assertEquals("Case cA is now inside Case cB", r.state.message)
    }

    @Test fun `nesting into a location places the container in that location`() {
        val r = Run()
        r.scan(container("cA")); r.scan(location("l2"))
        r.send(Input.ConfirmNest)
        assertEquals(listOf(PlannedEvent("placed_in_location", "cA", toLocationId = "l2")), r.emitted())
    }

    @Test fun `the nest offer expires after six seconds`() {
        val r = Run()
        r.scan(container("cA")); r.scan(container("cB"))
        r.send(Input.ConfirmNest, advanceMs = 6_500)
        assertTrue(r.emitted().isEmpty())
        assertNull(r.state.nestOffer)
        assertEquals("Nest offer expired", r.state.message)
    }

    @Test fun `done closes the target, and does nothing useful without one`() {
        val r = Run()
        r.scan(location("l1"))
        r.send(Input.Done)
        assertEquals(Phase.Idle, r.state.phase)
        r.send(Input.Done)
        assertEquals(Phase.Idle, r.state.phase)
        assertTrue(r.last.effects.isEmpty())
    }

    @Test fun `done in a basket explains what to scan`() {
        val r = Run()
        r.scan(item(1)); r.scan(item(2))
        r.send(Input.Done)
        assertTrue(r.state.phase is Phase.Basket)
        assertEquals("Scan a container or location to place 2 item(s)", r.state.message)
    }

    // ---- Undo ---------------------------------------------------------------------------------------------

    @Test fun `undo of a placement puts the item back where it was`() {
        val r = Run()
        r.scan(container("cA"))
        r.scan(item(1, Placement(locationId = "l1")))
        r.send(Input.Undo)
        assertEquals(listOf(PlannedEvent("placed_in_location", "i1", toLocationId = "l1")), r.emitted())
        assertEquals(emptyList<ResolvedTag.Item>(), (r.state.phase as Phase.InTarget).placed)
        assertEquals(listOf(FeedbackKind.UNDONE), r.feedback())
    }

    @Test fun `undo of an unplaced item emits removed_from_container`() {
        val r = Run()
        r.scan(location("l1")); r.scan(item(1, Placement()))
        r.send(Input.Undo)
        assertEquals(listOf(PlannedEvent("removed_from_container", "i1")), r.emitted())
    }

    @Test fun `undo of a basket commit restores the basket and reverses every move`() {
        val r = Run()
        r.scan(item(1, Placement(locationId = "l1"))); r.scan(item(2, Placement(containerId = "cX")))
        r.scan(location("l2"))
        r.send(Input.Undo)
        assertEquals(
            listOf(PlannedEvent("placed_in_location", "i1", toLocationId = "l1"), PlannedEvent("placed_in_container", "i2", toAssetId = "cX")),
            r.emitted(),
        )
        assertEquals(2, (r.state.phase as Phase.Basket).items.size)
    }

    @Test fun `undo of a switch returns to the previous target without events`() {
        val r = Run()
        r.scan(container("cA")); r.scan(item(1)); r.scan(container("cB"))
        r.send(Input.Undo)
        val p = r.state.phase as Phase.InTarget
        assertEquals("cA", p.target.id); assertEquals(1, p.placed.size)
        assertTrue(r.emitted().isEmpty())
        assertNull(r.state.nestOffer)
    }

    @Test fun `undo of a nest reverses it`() {
        val r = Run()
        r.scan(container("cA", placement = Placement(locationId = "l1"))); r.scan(container("cB"))
        r.send(Input.ConfirmNest); r.send(Input.Undo)
        assertEquals(listOf(PlannedEvent("placed_in_location", "cA", toLocationId = "l1")), r.emitted())
    }

    @Test fun `undo with nothing to undo is harmless`() {
        val r = Run()
        r.send(Input.Undo)
        assertTrue(r.last.effects.isEmpty())
        assertEquals("Nothing to undo", r.state.message)
    }

    @Test fun `undo uses what this session already did, not the stale cache`() {
        val r = Run()
        val stale = item(1, Placement(locationId = "l1"))
        r.scan(location("l2")); r.scan(stale)            // l1 -> l2
        r.scan(location("l3"))                            // switch
        r.scan(stale)                                     // l2 -> l3 (cache still says l1)
        r.send(Input.Undo)
        assertEquals(listOf(PlannedEvent("placed_in_location", "i1", toLocationId = "l2")), r.emitted())
    }

    @Test fun `undo history is capped`() {
        val r = Run(PackMoveMachine.Config(undoDepth = 3))
        (1..6).forEach { r.scan(item(it)) }
        assertEquals(3, r.state.undo.size)
    }

    // ---- Timeout ------------------------------------------------------------------------------------------

    @Test fun `idle target warns ten seconds before closing, once`() {
        val r = Run()
        r.scan(location("l1"))
        r.send(Input.Tick, advanceMs = 70_000)
        assertTrue(r.last.effects.isEmpty())
        r.send(Input.Tick, advanceMs = 10_000)             // 80 s idle
        assertEquals(listOf(FeedbackKind.IDLE_WARNING), r.feedback())
        r.send(Input.Tick, advanceMs = 1_000)
        assertTrue(r.last.effects.isEmpty())
    }

    @Test fun `idle target closes after the timeout`() {
        val r = Run()
        r.scan(location("l1"))
        r.send(Input.Tick, advanceMs = 91_000)
        assertEquals(Phase.Idle, r.state.phase)
        assertEquals(listOf(FeedbackKind.TARGET_CLOSED), r.feedback())
    }

    @Test fun `activity resets the idle clock and the warning`() {
        val r = Run()
        r.scan(container("cA"))
        r.send(Input.Tick, advanceMs = 85_000)             // warned
        r.scan(item(1))                                     // activity
        assertEquals(false, r.state.warned)
        r.send(Input.Tick, advanceMs = 60_000)
        assertTrue(r.state.phase is Phase.InTarget)
    }

    @Test fun `a basket never times out`() {
        val r = Run()
        r.scan(item(1))
        r.send(Input.Tick, advanceMs = 600_000)
        assertTrue(r.state.phase is Phase.Basket)
    }

    @Test fun `remaining time drives the countdown ring`() {
        val r = Run()
        assertNull(r.machine.remainingMs(r.state, r.now))
        r.scan(location("l1"))
        assertEquals(90_000L, r.machine.remainingMs(r.state, r.now))
        assertEquals(30_000L, r.machine.remainingMs(r.state, r.now + 60_000))
        assertEquals(0L, r.machine.remainingMs(r.state, r.now + 200_000))
    }
}
