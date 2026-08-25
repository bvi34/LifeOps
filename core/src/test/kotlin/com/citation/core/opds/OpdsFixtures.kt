package com.citation.core.opds

/**
 * Feed samples shaped like the servers Citation actually has to browse. Each is trimmed from the
 * real thing and keeps the quirks that matter — Calibre's `dc:` metadata and series elements,
 * Standard Ebooks' facets, Gutenberg's relative hrefs and navigation-only entries.
 */
object OpdsFixtures {

    /** A Calibre content server acquisition feed: `dc:` metadata, series, cover + thumbnail, paging. */
    val calibreAcquisition = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:dc="http://purl.org/dc/terms/"
              xmlns:opds="http://opds-spec.org/2010/catalog">
          <title>calibre library</title>
          <id>urn:uuid:5b3f2a1c</id>
          <updated>2026-02-01T10:00:00+00:00</updated>
          <link rel="self" href="/opds/navcatalog/4372656174656462797469746c65" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="start" href="/opds" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="next" href="/opds/navcatalog/4372?offset=25" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="search" href="/opds/search/{searchTerms}" type="application/atom+xml"/>
          <entry>
            <title>Leviathan Wakes</title>
            <id>urn:uuid:0f2c</id>
            <author><name>James S. A. Corey</name></author>
            <updated>2026-01-05T12:00:00+00:00</updated>
            <dc:language>eng</dc:language>
            <dc:publisher>Orbit</dc:publisher>
            <dc:identifier>urn:isbn:9780316129084</dc:identifier>
            <category term="Science Fiction" label="Science Fiction"/>
            <category term="Space Opera" label="Space Opera"/>
            <series position="1">The Expanse</series>
            <summary>Humanity has colonised the solar system.</summary>
            <link rel="http://opds-spec.org/acquisition" href="/get/EPUB/42/library" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition" href="/get/PDF/42/library" type="application/pdf"/>
            <link rel="http://opds-spec.org/image" href="/get/cover/42/library" type="image/jpeg"/>
            <link rel="http://opds-spec.org/image/thumbnail" href="/get/thumb/42/library" type="image/jpeg"/>
          </entry>
          <entry>
            <title>A Book With Only A PDF</title>
            <id>urn:uuid:0f2d</id>
            <author><name>Someone Else</name></author>
            <link rel="http://opds-spec.org/acquisition" href="/get/PDF/43/library" type="application/pdf"/>
          </entry>
        </feed>
    """.trimIndent()

    /** A navigation feed: folders only, no acquisition links anywhere. */
    val navigationFeed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Project Gutenberg</title>
          <id>http://m.gutenberg.org/ebooks.opds/</id>
          <link rel="self" href="ebooks.opds/" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="search" href="/ebooks/search.opds/?query={searchTerms}" type="application/atom+xml"/>
          <entry>
            <title>Popular</title>
            <id>http://m.gutenberg.org/ebooks/search.opds/?sort_order=downloads</id>
            <content type="text">Our most popular books.</content>
            <link type="application/atom+xml;profile=opds-catalog" href="search.opds/?sort_order=downloads"/>
          </entry>
          <entry>
            <title>Latest</title>
            <id>http://m.gutenberg.org/ebooks/search.opds/?sort_order=release_date</id>
            <link rel="subsection" type="application/atom+xml" href="search.opds/?sort_order=release_date"/>
          </entry>
        </feed>
    """.trimIndent()

    /** Standard Ebooks style: facets, open-access acquisition, an absolute self link. */
    val facetedFeed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:opds="http://opds-spec.org/2010/catalog"
              xmlns:thr="http://purl.org/syndication/thread/1.0">
          <title>Standard Ebooks</title>
          <id>https://standardebooks.org/feeds/opds/all</id>
          <link rel="self" href="https://standardebooks.org/feeds/opds/all" type="application/atom+xml;profile=opds-catalog"/>
          <link rel="http://opds-spec.org/facet" href="/feeds/opds/all?sort=newest" opds:facetGroup="Sort by" opds:activeFacet="true" title="Newest" thr:count="1200"/>
          <link rel="http://opds-spec.org/facet" href="/feeds/opds/all?sort=author" opds:facetGroup="Sort by" title="Author"/>
          <link rel="http://opds-spec.org/facet" href="/feeds/opds/all?lang=en" opds:facetGroup="Language" title="English"/>
          <entry>
            <title>The Time Machine</title>
            <id>https://standardebooks.org/ebooks/h-g-wells/the-time-machine</id>
            <author><name>H. G. Wells</name></author>
            <content type="html">&lt;p&gt;A Victorian scientist &lt;i&gt;travels&lt;/i&gt; forward.&lt;/p&gt;</content>
            <link rel="http://opds-spec.org/acquisition/open-access" href="/ebooks/h-g-wells/the-time-machine/downloads/the-time-machine.epub" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/image/thumbnail" href="/images/covers/the-time-machine-cover-thumb.jpg" type="image/jpeg"/>
          </entry>
        </feed>
    """.trimIndent()

    /** A lending catalog: borrow rather than download. */
    val borrowFeed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Public Library</title>
          <entry>
            <title>A Borrowable Book</title>
            <author><name>An Author</name></author>
            <link rel="http://opds-spec.org/acquisition/borrow" href="/borrow/17" type="application/atom+xml;type=entry;profile=opds-catalog"/>
            <link rel="http://opds-spec.org/acquisition/sample" href="/sample/17.epub" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    /** OPDS 2.0, as a Readium-based server emits it. */
    val opds2 = """
        {
          "metadata": {"title": "My Comics", "identifier": "urn:uuid:9d"},
          "links": [
            {"rel": "self", "href": "/opds/v2/library", "type": "application/opds+json"},
            {"rel": "next", "href": "/opds/v2/library?page=2", "type": "application/opds+json"},
            {"rel": "search", "href": "/opds/v2/search?q={searchTerms}", "type": "application/opds+json"}
          ],
          "navigation": [
            {"title": "Series", "href": "/opds/v2/series", "type": "application/opds+json"}
          ],
          "facets": [
            {"metadata": {"title": "Sort by"},
             "links": [{"title": "Title", "href": "/opds/v2/library?sort=title", "type": "application/opds+json"}]}
          ],
          "publications": [
            {
              "metadata": {
                "title": "Saga, Volume One",
                "author": {"name": "Brian K. Vaughan"},
                "identifier": "urn:isbn:9781607062011",
                "language": "en",
                "publisher": "Image Comics",
                "published": "2012-10-23",
                "description": "A soldier and her enemy have a child.",
                "subject": ["Comics", "Science Fiction"],
                "belongsTo": {"series": {"name": "Saga", "position": 1}}
              },
              "links": [
                {"rel": "http://opds-spec.org/acquisition", "href": "/download/1.epub", "type": "application/epub+zip"}
              ],
              "images": [
                {"href": "/covers/1-large.jpg", "type": "image/jpeg", "width": 1200},
                {"href": "/covers/1-thumb.jpg", "type": "image/jpeg", "width": 200}
              ]
            }
          ]
        }
    """.trimIndent()

    /** An OpenSearch description document, the indirection a correct catalog uses for search. */
    val openSearch = """
        <?xml version="1.0" encoding="UTF-8"?>
        <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
          <ShortName>Search</ShortName>
          <Url type="text/html" template="https://example.test/search?q={searchTerms}"/>
          <Url type="application/atom+xml;profile=opds-catalog"
               template="https://example.test/opds/search?q={searchTerms}&amp;start={startIndex?}"/>
        </OpenSearchDescription>
    """.trimIndent()
}
