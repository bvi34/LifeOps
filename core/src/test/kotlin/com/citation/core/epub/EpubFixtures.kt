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

    /**
     * A "real publisher" EPUB: a nested EPUB 3 navigation document, a declared cover, illustrations
     * in a sibling directory (so relative-href resolution is exercised), and the shelf metadata a
     * library screen wants — series, subjects, publisher, date, blurb.
     */
    fun richEpub(): ByteArray {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Leviathan Wakes</dc:title>
                <dc:creator>James S. A. Corey</dc:creator>
                <dc:language>en</dc:language>
                <dc:publisher>Orbit</dc:publisher>
                <dc:date>2011-06-15</dc:date>
                <dc:description>Humanity has colonised the solar system.</dc:description>
                <dc:subject>Science Fiction</dc:subject>
                <dc:subject>Space Opera</dc:subject>
                <dc:identifier id="pub-id">urn:isbn:9780316129084</dc:identifier>
                <meta name="calibre:series" content="The Expanse"/>
                <meta name="calibre:series_index" content="1"/>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover-img" href="../img/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="plate" href="../img/plate%20one.png" media-type="image/png"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        // Nested contents: a part containing two chapters, the second pointing mid-file.
        val nav = xhtml(
            """
            <nav epub:type="toc" id="toc">
              <ol>
                <li><a href="ch1.xhtml">Part One</a>
                  <ol>
                    <li><a href="ch1.xhtml#start">Chapter 1: Holden</a></li>
                    <li><a href="ch2.xhtml">Chapter 2: Miller</a></li>
                  </ol>
                </li>
              </ol>
            </nav>
            """.trimIndent()
        )

        val ch1 = xhtml(
            """<h1>Part One</h1>
               <p id="start">The <em>Scopuli</em> had been taken.</p>
               <img src="../img/plate%20one.png" alt="The Canterbury"/>
               <blockquote><p>Doors and corners.</p></blockquote>"""
        )
        val ch2 = xhtml("""<h1>Chapter 2: Miller</h1><p>Ceres was a city.</p>""")

        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/text/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putEntry("mimetype", "application/epub+zip")
            zip.putEntry("META-INF/container.xml", container)
            zip.putEntry("OEBPS/text/content.opf", opf)
            zip.putEntry("OEBPS/text/nav.xhtml", nav)
            zip.putEntry("OEBPS/text/ch1.xhtml", ch1)
            zip.putEntry("OEBPS/text/ch2.xhtml", ch2)
            zip.putBytes("OEBPS/img/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
            zip.putBytes("OEBPS/img/plate one.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
        }
        return out.toByteArray()
    }

    /** The same book as an EPUB 2 would state it: a `toc.ncx` and a `<meta name="cover">` pointer. */
    fun ncxEpub(): ByteArray {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>An Older Book</dc:title>
                <dc:creator>A. Publisher</dc:creator>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="cover-img" href="cover.png" media-type="image/png"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="np1" playOrder="1">
                  <navLabel><text>Book One</text></navLabel>
                  <content src="ch1.xhtml"/>
                  <navPoint id="np2" playOrder="2">
                    <navLabel><text>A Section</text></navLabel>
                    <content src="ch2.xhtml"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putEntry("META-INF/container.xml", container)
            zip.putEntry("content.opf", opf)
            zip.putEntry("toc.ncx", ncx)
            zip.putEntry("ch1.xhtml", xhtml("<h1>Book One</h1><p>Once upon a time.</p>"))
            zip.putEntry("ch2.xhtml", xhtml("<h1>A Section</h1><p>And then.</p>"))
            zip.putBytes("cover.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte()))
        }
        return out.toByteArray()
    }

    /**
     * An **Archive of Our Own** download, in the shape AO3's Calibre export really has: a first
     * spine document that is nothing but a `<dl class="tags">` record, a second that is the actual
     * title page (fic title, `by` byline, Summary), the work, an afterword — and a `toc.ncx` that
     * lists the record page and the work but never the title page.
     */
    fun ao3Epub(
        title: String = "A Test Fic",
        author: String = "anon_writes"
    ): ByteArray {
        val preface = xhtml(
            """<div id="preface">
                 <h2 class="toc-heading">Preface</h2>
                 <p class="message"><b>$title</b><br/>
                   Posted originally on the <a href="https://archiveofourown.org/">Archive of Our Own</a>
                   at <a href="https://archiveofourown.org/works/12345">https://archiveofourown.org/works/12345</a>.
                 </p>
                 <dl class="tags">
                   <dt>Rating:</dt>
                   <dd><a href="https://archiveofourown.org/tags/General%20Audiences">General Audiences</a></dd>
                   <dt>Fandoms:</dt>
                   <dd><a href="https://archiveofourown.org/tags/A">Fandom A</a>, <a href="https://archiveofourown.org/tags/B">Fandom B</a></dd>
                   <dt>Characters:</dt>
                   <dd><a href="https://archiveofourown.org/tags/X">Bee &amp; Cee</a></dd>
                   <dt>Stats:</dt>
                   <dd class="calibre5">
                     Published: 2026-08-24
                     Words: 4,241
                     Chapters: 1/1
                   </dd>
                 </dl>
               </div>"""
        )
        val titlePage = xhtml(
            """<div id="preface"><div>
                 <h1>$title</h1>
                 <div class="byline">by <a href="https://archiveofourown.org/users/$author" rel="author">$author</a></div>
                 <p>Summary</p>
                 <blockquote class="userstuff"><p>They meet. It goes badly.</p></blockquote>
               </div></div>"""
        )
        val work = xhtml("""<div id="chapters"><h2>Chapter 1</h2><p>The door opened.</p></div>""")
        val afterword = xhtml("""<div id="afterword"><h2>Afterword</h2><p>Thanks for reading.</p></div>""")

        val docs = listOf(
            Doc("html4", "fic_split_000.xhtml", preface),
            Doc("html3", "fic_split_001.xhtml", titlePage),
            Doc("html2", "fic_split_002.xhtml", work),
            Doc("html1", "fic_split_003.xhtml", afterword)
        )
        val manifestItems = docs.joinToString("\n") {
            """<item id="${it.id}" href="${it.href}" media-type="application/xhtml+xml"/>"""
        }
        val spine = docs.joinToString("\n") { """<itemref idref="${it.id}"/>""" }
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uuid_id">
              <metadata xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>$title</dc:title>
                <dc:creator opf:role="aut">$author</dc:creator>
                <dc:language>en</dc:language>
                <dc:publisher>Archive of Our Own</dc:publisher>
                <dc:subject>Fanworks</dc:subject>
                <dc:subject>General Audiences</dc:subject>
                <dc:identifier id="uuid_id">urn:uuid:412f1033-a53b-4761-9b63-3c0723fc752d</dc:identifier>
              </metadata>
              <manifest>
                $manifestItems
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
              </manifest>
              <spine toc="ncx">
                $spine
              </spine>
            </package>
        """.trimIndent()

        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>Preface</text></navLabel>
                  <content src="fic_split_000.xhtml"/>
                </navPoint>
                <navPoint id="n2" playOrder="2">
                  <navLabel><text>Chapter 1</text></navLabel>
                  <content src="fic_split_002.xhtml"/>
                </navPoint>
                <navPoint id="n3" playOrder="3">
                  <navLabel><text>Afterword</text></navLabel>
                  <content src="fic_split_003.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putEntry("mimetype", "application/epub+zip")
            zip.putEntry("META-INF/container.xml", container)
            zip.putEntry("content.opf", opf)
            zip.putEntry("toc.ncx", ncx)
            docs.forEach { zip.putEntry(it.href, it.xhtml) }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putBytes(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }
}
