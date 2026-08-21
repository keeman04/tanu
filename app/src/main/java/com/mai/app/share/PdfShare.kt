package com.mai.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.mai.app.data.MeetingRecord
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

object PdfShare {
    fun share(context: Context, meeting: MeetingRecord) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "MAI-${meeting.id.take(8)}.pdf")
        create(file, meeting)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share MOM"))
    }

    private fun create(file: File, meeting: MeetingRecord) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val h = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 11f }
        var y = 55f
        c.drawText("MAI · ${meeting.title}", 42f, y, title); y += 24
        c.drawText(DateFormat.getDateTimeInstance().format(Date(meeting.startedAt)), 42f, y, body); y += 30
        fun section(name: String, lines: List<String>) {
            if (lines.isEmpty()) return
            c.drawText(name, 42f, y, h); y += 18
            lines.forEach { line ->
                wrap(line, 82).forEach { part -> c.drawText("• $part", 48f, y, body); y += 15 }
            }
            y += 10
        }
        section("SUMMARY", listOf(meeting.summary))
        section("DECISIONS", meeting.decisions)
        section("ACTIONS", meeting.actions.map { a -> listOfNotNull(a.text, a.owner?.let { "Owner: $it" }, a.due?.let { "Due: $it" }).joinToString(" · ") })
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    private fun wrap(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val words = text.split(' ')
        val out = mutableListOf<String>(); var current = ""
        words.forEach { w ->
            val next = if (current.isBlank()) w else "$current $w"
            if (next.length > max) { if (current.isNotBlank()) out += current; current = w } else current = next
        }
        if (current.isNotBlank()) out += current
        return out
    }
}
