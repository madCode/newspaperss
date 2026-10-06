package com.app.newspaperss.testutil

import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import java.io.File
import java.time.LocalDate

/** Writes an edition's book of [articles] (or [sections]) to this file, as the app's builder would. */
fun File.writeEpub(
    articles: List<EditionArticle>,
    title: String = "T",
    date: LocalDate = LocalDate.of(2026, 9, 29),
    sections: List<EditionSection> = listOf(EditionSection(null, articles)),
): File = apply {
    outputStream().use { EpubWriter.write(EditionDoc(title, date, "urn:uuid:5b1f3c8e-0000-4000-8000-${"%012d".format(java.util.Locale.ROOT, title.hashCode().toLong() and 0xFFFFFFFF)}", sections), it) }
}
