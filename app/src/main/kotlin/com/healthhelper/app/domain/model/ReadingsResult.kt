package com.healthhelper.app.domain.model

/**
 * Result of a time-range read.
 *
 * @property readings readings sorted by timestamp ascending.
 * @property truncated true when more readings may exist in the requested range than were returned
 *   (a read limit was reached, or the read timed out part-way). Callers must not treat a truncated
 *   result as "caught up".
 *
 * Read failures are not represented here: repositories throw instead, so callers can tell
 * "read failed" apart from "no records".
 */
data class ReadingsResult<T>(
    val readings: List<T>,
    val truncated: Boolean = false,
)
