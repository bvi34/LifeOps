package com.citation.core.sync

/**
 * **Two orthogonal state machines on a book record**, kept deliberately un-collapsed.
 *
 * The temptation is to fold "I can't find this book to buy" into "I haven't read it". They are
 * different axes and collapsing them loses information: a book can be *acquired but unread*, or
 * *unavailable but something you've already partly read from a library copy*. So a book record
 * carries both an [AcquisitionState] (owned by the reader — can I get the artifact?) and a
 * [ReadingState] (how far through am I?), and they move independently.
 */
data class BookLifecycle(
    val acquisition: AcquisitionState,
    val reading: ReadingState
) {
    /** Apply an acquisition transition, leaving reading untouched. Illegal moves are rejected. */
    fun acquire(event: AcquisitionEvent): BookLifecycle =
        copy(acquisition = acquisition.on(event))

    /** Apply a reading transition, leaving acquisition untouched. */
    fun read(event: ReadingEvent): BookLifecycle =
        copy(reading = reading.on(event))

    companion object {
        /** A center-authored "wanted" book starts life wanted-and-unread. */
        fun wanted() = BookLifecycle(AcquisitionState.WANTED, ReadingState.TO_READ)

        /** A book you already hold (an imported EPUB/PDF) starts acquired-and-unread. */
        fun owned() = BookLifecycle(AcquisitionState.ACQUIRED, ReadingState.TO_READ)
    }
}

/**
 * *Acquisition* axis, owned by the reader: can I turn this wanted book into a concrete artifact I
 * can open? `wanted → resolving → acquired`, with `unavailable` as an honest dead-end that never
 * pretends to be a reading state.
 */
enum class AcquisitionState {
    WANTED,
    RESOLVING,
    ACQUIRED,
    UNAVAILABLE;

    fun on(event: AcquisitionEvent): AcquisitionState = when (event) {
        AcquisitionEvent.START_RESOLVING -> requireFrom(WANTED, UNAVAILABLE) { RESOLVING }
        AcquisitionEvent.RESOLVED -> requireFrom(WANTED, RESOLVING) { ACQUIRED }
        AcquisitionEvent.MARK_UNAVAILABLE -> requireFrom(WANTED, RESOLVING) { UNAVAILABLE }
    }

    private inline fun requireFrom(vararg from: AcquisitionState, to: () -> AcquisitionState): AcquisitionState {
        require(this in from) { "Illegal acquisition transition from $this" }
        return to()
    }
}

enum class AcquisitionEvent { START_RESOLVING, RESOLVED, MARK_UNAVAILABLE }

/**
 * *Reading progress* axis: `to-read → reading → done`. Reopening a finished book to reread moves it
 * back to reading; nothing here ever encodes whether you actually *have* the book — that's the
 * other axis's job.
 */
enum class ReadingState {
    TO_READ,
    READING,
    DONE;

    fun on(event: ReadingEvent): ReadingState = when (event) {
        ReadingEvent.START_READING -> READING          // from to-read or (reread) done
        ReadingEvent.FINISH -> requireFrom(READING) { DONE }
        ReadingEvent.RESET -> TO_READ
    }

    private inline fun requireFrom(vararg from: ReadingState, to: () -> ReadingState): ReadingState {
        require(this in from) { "Illegal reading transition from $this" }
        return to()
    }
}

enum class ReadingEvent { START_READING, FINISH, RESET }
