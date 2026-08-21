@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.mai.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.internal.LowPriorityInOverloadResolution

/** Preserve ordinary Kotlin runCatching semantics inside the MAI UI package. */
internal inline fun <R> runCatching(block: () -> R): Result<R> = kotlin.runCatching(block)

/**
 * Legacy bridge for the formatter-shaped lambda in the Actions screen. Low priority keeps
 * normal no-argument runCatching calls (for example MediaPlayer cleanup) on the generic
 * overload while the explicit one-parameter date lambda resolves here.
 */
@LowPriorityInOverloadResolution
internal fun runCatching(block: (DateTimeFormatter) -> LocalDate): Result<LocalDate> {
    var last: Throwable? = null
    for (formatter in listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd MMM yyyy"))) {
        try {
            return Result.success(block(formatter))
        } catch (t: Throwable) {
            last = t
        }
    }
    return Result.failure(last ?: IllegalArgumentException("Unsupported MAI due date"))
}
