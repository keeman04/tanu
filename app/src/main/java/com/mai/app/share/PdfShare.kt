package com.mai.app.share

import android.content.ActivityNotFoundException
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
        val file = createCached(context, meeting)
        context.startActivity(Intent.createChooser(sendIntent(context, meeting, file), "Share MAI MOM"))
    }

    fun shareWhatsApp(context: Context, meeting: MeetingRecord) {
        val file = createCached(context, meeting)
        val base = sendIntent(context, meeting, file)
        try {
            context.startActivity(Intent(base).setPackage("com.whatsapp"))
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(base).setPackage("com.whatsapp.w4b"))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(base, "Share MAI MOM"))
            }
        }
    }

    private fun sendIntent(context: Context, meeting: MeetingRecord, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val people = meeting.participants.joinToString(", ") { it.name }
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "MAI Minutes · ${meeting.title}\nFor: $people")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createCached(context: Context, meeting: MeetingRecord): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "MAI-${meeting.id.take(8)}.pdf")
        create(file, meeting)
        return file
    }

    private fun create(file: File, meeting: MeetingRecord) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 21f; isFakeBoldText = true }
        val h = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 10.5f }
        val meta = Paint().apply { textSize = 9.5f }
        var y = 52f
        c.drawText("MAI · ${meeting.title.take(48)}", 40f, y, title); y += 22
        c.drawText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt)), 40f, y, meta); y += 16
        c.drawText("Participants: ${meeting.participants.joinToString(", ") { it.name }.take(95)}", 40f, y, meta); y += 27

        fun section(name: String, lines: List<String>, maxItems: Int, maxWrappedLines: Int) {
            val clean = lines.map(String::trim).filter(String::isNotBlank).take(maxItems)
            if (clean.isEmpty() || y > 770f) return
            c.drawText(name, 40f, y, h); y += 16
            var used = 0
            for (line in clean) {
                for (part in wrap(line, 88)) {
                    if (used >= maxWrappedLines || y > 785f) break
                    c.drawText("• $part", 46f, y, body); y += 14; used++
                }
                if (used >= maxWrappedLines || y > 785f) break
            }
            y += 8
        }

        section("SUMMARY", listOf(meeting.summary), 1, 5)
        section("DECISIONS", meeting.decisions, 6, 9)
        section("ACTIONS", meeting.actions.map { a ->
            listOfNotNull(a.text, a.owner?.let { "Owner: $it" }, a.due?.let { "Due: $it" }).joinToString(" · ")
        }, 8, 15)
        section("FOLLOW-UP", meeting.followUps, 5, 8)
        if (meeting.status == "processing" && y < 790f) {
            c.drawText("MAI Cloud Intelligence is still refining this meeting.", 40f, y, meta)
        }
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    private fun wrap(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val words = text.split(' ')
        val out = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val next = if (current.isBlank()) word else "$current $word"
            if (next.length > max) {
                if (current.isNotBlank()) out += current
                current = word
            } else current = next
        }
        if (current.isNotBlank()) out += current
        return out
    }
}
