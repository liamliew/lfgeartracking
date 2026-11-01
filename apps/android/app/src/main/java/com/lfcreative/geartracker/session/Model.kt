package com.lfcreative.geartracker.session

/** Direct parent of an asset or container: a location OR a container (never both). Both null = unplaced. */
data class Placement(val locationId: String? = null, val containerId: String? = null)

/** What a scanned value turned out to be. Containers and locations are "targets": things items go INTO. */
sealed interface ResolvedTag {
    val value: String

    data class Unknown(override val value: String) : ResolvedTag

    data class Item(
        override val value: String,
        val id: String,
        val code: String,
        val name: String,
        val placement: Placement = Placement(),
        val retired: Boolean = false,
    ) : ResolvedTag

    sealed interface Target : ResolvedTag {
        val id: String
        val code: String
        val name: String
        /** Every container (for containers) or location (for locations) above this one. Used for cycle checks. */
        val ancestorIds: Set<String>
    }

    data class Container(
        override val value: String,
        override val id: String,
        override val code: String,
        override val name: String,
        override val ancestorIds: Set<String> = emptySet(),
        val placement: Placement = Placement(),
        val retired: Boolean = false,
    ) : Target

    data class Location(
        override val value: String,
        override val id: String,
        override val code: String,
        override val name: String,
        override val ancestorIds: Set<String> = emptySet(),
        val path: String = name,
        val archived: Boolean = false,
    ) : Target
}

/** An event the session wants recorded. The ViewModel turns these into outbox events (client UUID, session, time). */
data class PlannedEvent(
    val type: String,
    val assetId: String,
    val toLocationId: String? = null,
    val toAssetId: String? = null,
)

/** Sound / colour / vibration cue categories (see feedback/FeedbackCue.kt). */
enum class FeedbackKind {
    ACCEPTED, TARGET_SET, TARGET_SWITCHED, TARGET_CLOSED, DUPLICATE, NEEDS_DECISION, ERROR, UNDONE, IDLE_WARNING
}
