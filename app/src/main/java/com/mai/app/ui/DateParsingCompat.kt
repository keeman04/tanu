package com.mai.app.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Narrow overload used by the Actions screen's date parser. It accepts the formatter-shaped
 * lambda and tries the two persisted MAI date formats without changing any other runCatching use.
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
