package com.mai.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Keep ordinary no-argument runCatching calls working inside the UI package. */
internal inline fun <R> runCatching(block: () -> R): Result<R> = kotlin.runCatching(block)

/**
 * Compatibility overload for the Actions screen's formatter-shaped date lambda.
 * It accepts both date formats MAI persists today.
 */
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
