package com.citation.core.epub

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds minimal but structurally-valid EPUB archives in memory for parser tests. */
object EpubFixtures {

    private data class Doc(val id: String, val href: String, val xhtml: String)

    /**
     * A tiny two-chapter EPUB with title/author and an ISBN. The OPF lives in an `OEBPS/`
     * subdirectory so the test also exercises OPF-relative href resolution.
     */
    fun twoChapterEpub(
        title: String = "The Test Book",
        author: String = "Ada Lovelace",
        isbn: String = "9780132350884"
    ): ByteArray {
        val docs = listOf(
            Doc(
                "ch1", "ch1.xhtml",
                xhtml(
                    "<h1>Chapter One</h1><p>It was a bright cold day in April, " +
                        "and the clocks were striking thirteen.</p>"
                )
            ),
            Doc(
                "ch2", "ch2.xhtml",
                xhtml("<h1>Chapter Two</h1><p>The sky above the port was the color of television.</p>")
            )
        )
        val manifestItems = docs.joinToString("\n") {
            """<item id="${it.id}" href="${it.href}" media-type="application/xhtml+xml"/>"""
        }
        val spine = docs.joinToString("\n") { """<itemref idref="${it.id}"/>""" }
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>$title</dc:title>
                <dc:creator>$author</dc:creator>
                <dc:language>en</dc:language>
                <dc:identifier id="pub-id">urn:isbn:$isbn</dc:identifier>
              </metadata>
              <manifest>
                $manifestItems
              </manifest>
              <spine>
                $spine
              </spine>
            </package>
        """.trimIndent()

        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putEntry("mimetype", "application/epub+zip")
            zip.putEntry("META-INF/container.xml", container)
            zip.putEntry("OEBPS/content.opf", opf)
            docs.forEach { zip.putEntry("OEBPS/${it.href}", it.xhtml) }
        }
        return out.toByteArray()
    }

    private fun xhtml(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head>
           <body>$body</body></html>""".trimIndent()

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
