package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.citation.core.note.HighlightColor
import org.junit.Test

/**
 * The parts of "display settings" that are actually decisions rather than plumbing: what warmth
 * does to a colour, what true black is for, and which volume key goes forward.
 */
class ReaderSettingsTest {

    private fun rgb(argb: Int) = Triple((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    /** The page and its ink, warmed — the pair most of these assertions are about. */
    private fun pageAndInk(settings: ReaderSettings): Pair<Int, Int>? =
        ReaderPalette.colors(settings)?.let { it.page to it.text }

    /** Settings with some of the page's colours taken over from the theme, as the sheet does it. */
    private fun ReaderSettings.taking(vararg colours: Pair<ReaderColorRole, Int>): ReaderSettings =
        colours.fold(this) { settings, (role, colour) -> settings.withCustom(role, colour) }

    @Test
    fun `defaults are what a book should open as`() {
        val s = ReaderSettings()
        assertEquals(ReaderTheme.SYSTEM, s.theme)
        assertEquals(ReaderTypeface.SERIF, s.typeface)
        assertEquals(ParagraphSpacing.INDENT, s.paragraphs)
        assertTrue("paged is the reading mode, scroll is the alternative", s.paged)
        assertTrue("a reader should not dim mid-paragraph", s.keepAwake)
        assertTrue(s.followsSystemBrightness)
        assertFalse("justification without care opens rivers on a phone column", s.justify)
        assertTrue(s.hyphenate)
        assertTrue("nothing is taken over from the theme until a reader says so", s.customRoles.isEmpty())
        assertFalse(s.useCustomColors)
    }

    // --- Paragraphs --------------------------------------------------------------------------

    @Test
    fun `the three paragraph settings are the three combinations worth having`() {
        assertTrue(ParagraphSpacing.INDENT.indents)
        assertFalse(ParagraphSpacing.INDENT.spaces)
        assertFalse(ParagraphSpacing.SPACED.indents)
        assertTrue(ParagraphSpacing.SPACED.spaces)
        // The one readers ask for: the indent says where a paragraph begins, the blank line gives
        // the eye somewhere to rest, and at a tight line spacing the indent alone gives it neither.
        assertTrue(ParagraphSpacing.BOTH.indents)
        assertTrue(ParagraphSpacing.BOTH.spaces)
        // Nothing at all is not an option: it would make a paragraph break invisible.
        assertTrue(ParagraphSpacing.entries.all { it.indents || it.spaces })
    }

    @Test
    fun `values are clamped to what can actually be rendered`() {
        val wild = ReaderSettings(
            fontSize = 900f, lineSpacing = 0.1f, marginDp = -40f,
            letterSpacing = 12f, warmth = 5f, brightness = 4f
        ).sanitized()
        assertEquals(36f, wild.fontSize)
        assertEquals(1.0f, wild.lineSpacing)
        assertEquals(0f, wild.marginDp)
        assertEquals(0.4f, wild.letterSpacing)
        assertEquals(1f, wild.warmth)
        assertEquals(1f, wild.brightness)
    }

    @Test
    fun `a negative brightness stays the follow-the-system sentinel rather than being clamped to a value`() {
        val s = ReaderSettings(brightness = -0.5f).sanitized()
        assertTrue(s.followsSystemBrightness)
        assertEquals(ReaderSettings.SYSTEM_BRIGHTNESS, s.brightness)
    }

    // --- Warmth ------------------------------------------------------------------------------

    @Test
    fun `no warmth leaves a colour exactly alone`() {
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.warm(ReaderPalette.PAPER_BG, 0f))
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.warm(ReaderPalette.PAPER_BG, -1f))
    }

    @Test
    fun `warmth cuts blue hardest, green a little, red not at all`() {
        val warmed = ReaderPalette.warm(0xFF808080.toInt(), 1f)
        val (r, g, b) = rgb(warmed)
        assertEquals("red is untouched", 0x80, r)
        assertTrue("green drops a little", g in 0x72..0x74)
        assertTrue("blue drops most", b in 0x46..0x48)
        assertTrue("blue must fall further than green", b < g)
    }

    @Test
    fun `warmth preserves the alpha channel`() {
        assertEquals(0xFF, (ReaderPalette.warm(0xFF3366CC.toInt(), 0.6f) ushr 24) and 0xFF)
        assertEquals(0x80, (ReaderPalette.warm(0x803366CC.toInt(), 0.6f) ushr 24) and 0xFF)
    }

    @Test
    fun `warming is monotonic in the amount asked for`() {
        val base = 0xFFFFFFFF.toInt()
        val blues = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { ReaderPalette.warm(base, it) and 0xFF }
        assertEquals(blues.sortedDescending(), blues)
    }

    @Test
    fun `both of a theme's colours warm together`() {
        val (bg, fg) = pageAndInk(ReaderSettings(theme = ReaderTheme.PAPER, warmth = 0.8f))!!
        assertNotEquals(ReaderPalette.PAPER_BG, bg)
        assertNotEquals(ReaderPalette.PAPER_FG, fg)
        // Warming must not invert the page: paper stays lighter than its ink.
        assertTrue((bg and 0xFF) > (fg and 0xFF))
    }

    // --- Themes ------------------------------------------------------------------------------

    @Test
    fun `the system theme defers rather than inventing colours`() {
        assertNull(ReaderPalette.background(ReaderTheme.SYSTEM, trueBlack = false))
        assertNull(ReaderPalette.foreground(ReaderTheme.SYSTEM, trueBlack = false))
        assertNull(ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.SYSTEM)))
    }

    @Test
    fun `true black is pure black, and only in night`() {
        assertEquals(ReaderPalette.BLACK, ReaderPalette.background(ReaderTheme.NIGHT, trueBlack = true))
        assertEquals(ReaderPalette.NIGHT_BG, ReaderPalette.background(ReaderTheme.NIGHT, trueBlack = false))
        // True black is an OLED trick for the dark theme; it must not blacken a paper page.
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.background(ReaderTheme.PAPER, trueBlack = true))
    }

    @Test
    fun `true black softens the text rather than maximising contrast`() {
        val onBlack = ReaderPalette.foreground(ReaderTheme.NIGHT, trueBlack = true)!! and 0xFF
        val onNear = ReaderPalette.foreground(ReaderTheme.NIGHT, trueBlack = false)!! and 0xFF
        assertTrue("full-strength text on pure black is a harsh edge in a dark room", onBlack < onNear)
    }

    // --- Colours the reader chose --------------------------------------------------------------

    @Test
    fun `a colour the reader took over is the one the page is drawn in`() {
        val s = ReaderSettings().taking(
            ReaderColorRole.PAGE to 0xFF102030.toInt(),
            ReaderColorRole.TEXT to 0xFFEEDDCC.toInt()
        )
        assertEquals(0xFF102030.toInt(), ReaderPalette.background(s.theme, s.trueBlack, s.custom(ReaderColorRole.PAGE)))
        assertEquals(0xFFEEDDCC.toInt(), ReaderPalette.foreground(s.theme, s.trueBlack, s.custom(ReaderColorRole.TEXT)))
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(0xFF102030.toInt(), bg)
        assertEquals(0xFFEEDDCC.toInt(), fg)
    }

    @Test
    fun `the page can follow the theme while the prose is the reader's own`() {
        // The arrangement the old single "Custom" theme could not express, and the reason for the
        // split: keeping Sepia's page while setting the prose in a colour of your own.
        val s = ReaderSettings(theme = ReaderTheme.SEPIA)
            .taking(ReaderColorRole.TEXT to 0xFF1A3A5C.toInt())
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.SEPIA_BG, bg)
        assertEquals(0xFF1A3A5C.toInt(), fg)
    }

    @Test
    fun `a prose colour of the reader's own survives the page following the system`() {
        // The other half of it: under System the page is the app's, and used to be unreachable
        // without giving up the system page entirely.
        val s = ReaderSettings(theme = ReaderTheme.SYSTEM)
            .taking(ReaderColorRole.TEXT to 0xFF5B4636.toInt())
        // Nothing to say to a web reader whose page Citation has not set...
        assertNull(ReaderPalette.colors(s))
        // ...but on Citation's own page the colour is the reader's and the page is still the app's.
        val c = ReaderPalette.colors(s, fallbackPage = 0xFF111827.toInt(), fallbackText = 0xFFF9FAFB.toInt())
        assertEquals(0xFF111827.toInt(), c.page)
        assertEquals(0xFF5B4636.toInt(), c.text)
    }

    @Test
    fun `colours the reader chose are kept but ignored once handed back to the theme`() {
        // Held across a switch so comparing against Sepia and coming back does not lose the work.
        val chosen = ReaderSettings(theme = ReaderTheme.SEPIA).taking(
            ReaderColorRole.PAGE to 0xFF102030.toInt(),
            ReaderColorRole.TEXT to 0xFFEEDDCC.toInt()
        )
        val s = chosen.withoutCustom(ReaderColorRole.PAGE).withoutCustom(ReaderColorRole.TEXT)
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.SEPIA_BG, bg)
        assertEquals(ReaderPalette.SEPIA_FG, fg)
        assertEquals(0xFF102030.toInt(), s.held(ReaderColorRole.PAGE))
        assertNull("a colour handed back is not in force", s.custom(ReaderColorRole.PAGE))
    }

    @Test
    fun `a role turned on with no colour behind it still leaves a readable page`() {
        val s = ReaderSettings(
            theme = ReaderTheme.PAPER,
            useCustomColors = true,
            customRoles = ReaderColorRole.entries.toSet()
        )
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.PAPER_BG, bg)
        assertEquals(ReaderPalette.PAPER_FG, fg)
    }

    @Test
    fun `a part-transparent choice is made opaque rather than letting the app show through`() {
        val s = ReaderSettings(customBackground = 0x40FF0000, customText = 0x00112233).sanitized()
        assertEquals(0xFFFF0000.toInt(), s.customBackground)
        assertEquals(0xFF112233.toInt(), s.customText)
    }

    @Test
    fun `a part-transparent heading or link choice is made opaque too`() {
        val s = ReaderSettings(customHeading = 0x20AABBCC, customLink = 0x00445566).sanitized()
        assertEquals(0xFFAABBCC.toInt(), s.customHeading)
        assertEquals(0xFF445566.toInt(), s.customLink)
    }

    // --- The rest of the page's colours ---------------------------------------------------------

    @Test
    fun `headings follow the prose until the reader says otherwise`() {
        val paper = ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.PAPER))!!
        assertEquals(paper.text, paper.heading)
        assertNull(ReaderPalette.heading())
    }

    @Test
    fun `a heading colour of the reader's own sits on any theme`() {
        val chosen = 0xFF8B0000.toInt()
        val sepia = ReaderPalette.colors(
            ReaderSettings(theme = ReaderTheme.SEPIA).taking(ReaderColorRole.HEADING to chosen)
        )!!
        assertEquals(chosen, sepia.heading)
        // Held across a switch, like the page and text colours, but not applied once handed back.
        val handedBack = ReaderPalette.colors(
            ReaderSettings(theme = ReaderTheme.SEPIA)
                .taking(ReaderColorRole.HEADING to chosen)
                .withoutCustom(ReaderColorRole.HEADING)
        )!!
        assertEquals(ReaderPalette.SEPIA_FG, handedBack.heading)
    }

    @Test
    fun `every derived colour is legible on the page it was derived for`() {
        // The failure this rules out is the one that made the whole feature necessary: a colour
        // chosen against some other page — the app's accent — landing on this one.
        val pages = listOf(
            ReaderSettings(theme = ReaderTheme.PAPER),
            ReaderSettings(theme = ReaderTheme.SEPIA),
            ReaderSettings(theme = ReaderTheme.NIGHT),
            ReaderSettings(theme = ReaderTheme.NIGHT, trueBlack = true),
            ReaderSettings().taking(
                ReaderColorRole.PAGE to 0xFF102030.toInt(),
                ReaderColorRole.TEXT to 0xFFEEDDCC.toInt()
            ),
            ReaderSettings().taking(
                ReaderColorRole.PAGE to 0xFFFFF3C4.toInt(),
                ReaderColorRole.TEXT to 0xFF1A3A5C.toInt()
            ),
            ReaderSettings(theme = ReaderTheme.PAPER, warmth = 1f),
            ReaderSettings(theme = ReaderTheme.NIGHT, warmth = 1f)
        )
        pages.forEach { settings ->
            val c = ReaderPalette.colors(settings)!!
            assertTrue("link unreadable on ${'$'}{settings.theme}", ReaderPalette.isLegible(c.link, c.page))
            assertTrue("heading unreadable on ${'$'}{settings.theme}", ReaderPalette.isLegible(c.heading, c.page))
        }
    }

    @Test
    fun `a link keeps its own colour where the page can carry it`() {
        val onPaper = ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.PAPER))!!
        assertEquals(ReaderPalette.LINK_TINT, onPaper.link)
        // A dark page cannot: the same blue would be a smudge, so it is pulled toward the text.
        val onNight = ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.NIGHT))!!
        assertNotEquals(ReaderPalette.LINK_TINT, onNight.link)
        assertNotEquals(onNight.text, onNight.link)
    }

    @Test
    fun `a link colour the reader typed is used as typed`() {
        val chosen = 0xFF00695C.toInt()
        val c = ReaderPalette.colors(
            ReaderSettings(theme = ReaderTheme.PAPER).taking(ReaderColorRole.LINK to chosen)
        )!!
        assertEquals(chosen, c.link)
    }

    @Test
    fun `a tint that cannot be rescued gives way to the text colour`() {
        // A reader is entitled to a low-contrast page of their own — grey on grey, for a reason
        // that is theirs. Where even the prose colour does not clear the bar, there is no blend of
        // a link tint that will, so the link is simply set in the prose colour: invisible as a
        // link, readable as words, which is the right way round.
        val page = 0xFF808080.toInt()
        val text = 0xFF8A8A8A.toInt()
        assertFalse(ReaderPalette.isLegible(text, page))
        assertEquals(text, ReaderPalette.readable(0xFF7F7F7F.toInt(), page, text))
    }

    @Test
    fun `captions are quieter than the prose without leaving the page`() {
        val c = ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.PAPER))!!
        assertNotEquals(c.text, c.secondary)
        assertTrue(ReaderPalette.contrast(c.secondary, c.page) < ReaderPalette.contrast(c.text, c.page))
        assertTrue("a caption is still prose", ReaderPalette.contrast(c.secondary, c.page) > 3.0)
    }

    @Test
    fun `every colour warms with the page`() {
        val warm = ReaderSettings(theme = ReaderTheme.PAPER, warmth = 1f)
            .taking(ReaderColorRole.HEADING to 0xFF3366FF.toInt())
        val cold = warm.copy(warmth = 0f)
        val heated = ReaderPalette.colors(warm)!!
        val plain = ReaderPalette.colors(cold)!!
        // Blue is the channel warmth cuts; a heading that ignored it would sit cold on a warm page.
        assertTrue((heated.heading and 0xFF) < (plain.heading and 0xFF))
        assertTrue((heated.link and 0xFF) < 0xFF)
    }

    @Test
    fun `the system theme still yields a full palette once the app's colours are supplied`() {
        assertNull(ReaderPalette.colors(ReaderSettings(theme = ReaderTheme.SYSTEM)))
        val c = ReaderPalette.colors(
            ReaderSettings(theme = ReaderTheme.SYSTEM),
            fallbackPage = 0xFF111827.toInt(),
            fallbackText = 0xFFF9FAFB.toInt()
        )
        assertEquals(0xFF111827.toInt(), c.page)
        assertEquals(0xFFF9FAFB.toInt(), c.text)
        assertEquals(c.text, c.heading)
        assertTrue(ReaderPalette.isLegible(c.link, c.page))
    }

    // --- The whole palette, on and off ----------------------------------------------------------

    @Test
    fun `one switch takes every colour off the page and puts it back`() {
        // The A/B the per-role choices cost until this existed: four roles handed back one at a
        // time and taken again is not alternating between two pages, it is bookkeeping.
        val mine = ReaderSettings(theme = ReaderTheme.SEPIA).taking(
            ReaderColorRole.PAGE to 0xFF102030.toInt(),
            ReaderColorRole.TEXT to 0xFFEEDDCC.toInt(),
            ReaderColorRole.LINK to 0xFF00695C.toInt()
        )
        val off = mine.usingCustomColors(false, page = 0, text = 0)
        val (bg, fg) = pageAndInk(off)!!
        assertEquals("the page is the theme's again, entirely", ReaderPalette.SEPIA_BG, bg)
        assertEquals(ReaderPalette.SEPIA_FG, fg)

        // Nothing was thrown away on the way out, so coming back needs no retyping.
        val back = off.usingCustomColors(true, page = 0, text = 0)
        assertEquals(mine.customRoles, back.customRoles)
        assertEquals(0xFF102030.toInt(), pageAndInk(back)!!.first)
        assertEquals(0xFF00695C.toInt(), ReaderPalette.colors(back)!!.link)
    }

    @Test
    fun `switched off, a role that is the reader's own still follows the theme`() {
        val s = ReaderSettings(theme = ReaderTheme.PAPER)
            .taking(ReaderColorRole.TEXT to 0xFF1A3A5C.toInt())
            .usingCustomColors(false, page = 0, text = 0)
        assertFalse(s.customizes(ReaderColorRole.TEXT))
        assertNull(s.custom(ReaderColorRole.TEXT))
        assertTrue("and the colour is still there", s.hasOwnColors)
        assertEquals(0xFF1A3A5C.toInt(), s.held(ReaderColorRole.TEXT))
        assertEquals(ReaderPalette.PAPER_FG, pageAndInk(s)!!.second)
    }

    @Test
    fun `switching on for the first time starts from the page the reader is looking at`() {
        // What the old "Custom" theme did, and the reason it read as an adjustment to the page you
        // nearly liked rather than a fresh problem to solve.
        val s = ReaderSettings().usingCustomColors(
            on = true,
            page = ReaderPalette.SEPIA_BG,
            text = ReaderPalette.SEPIA_FG
        )
        assertEquals(setOf(ReaderColorRole.PAGE, ReaderColorRole.TEXT), s.customRoles)
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.SEPIA_BG, bg)
        assertEquals(ReaderPalette.SEPIA_FG, fg)
    }

    @Test
    fun `switching on again never overwrites colours already chosen`() {
        val mine = ReaderSettings().taking(ReaderColorRole.PAGE to 0xFF102030.toInt())
        val back = mine.usingCustomColors(false, page = 0, text = 0)
            .usingCustomColors(true, page = 0xFFFFFFFF.toInt(), text = ReaderPalette.BLACK)
        assertEquals(0xFF102030.toInt(), back.held(ReaderColorRole.PAGE))
        assertFalse("and a role never taken is still the theme's", back.customizes(ReaderColorRole.TEXT))
    }

    @Test
    fun `choosing a colour puts it on the page without a second switch`() {
        val s = ReaderSettings().withCustom(ReaderColorRole.TEXT, 0xFF1A3A5C.toInt())
        assertTrue(s.useCustomColors)
        assertTrue(s.customizes(ReaderColorRole.TEXT))
    }

    // --- Rows written before the page and the prose were separate -------------------------------

    @Test
    fun `an old custom theme comes back as the page and the prose taken over`() {
        val stored = ReaderSettings(
            customBackground = 0xFF102030.toInt(),
            customText = 0xFFEEDDCC.toInt()
        )
        val s = ReaderColorMigration.upgrade(stored, ReaderColorMigration.LEGACY_CUSTOM_THEME)
        assertTrue("a reader who had chosen colours had them on the page", s.useCustomColors)
        assertEquals(setOf(ReaderColorRole.PAGE, ReaderColorRole.TEXT), s.customRoles)
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(0xFF102030.toInt(), bg)
        assertEquals(0xFFEEDDCC.toInt(), fg)
    }

    @Test
    fun `an old custom theme with nothing chosen comes back as the page it was drawing`() {
        val s = ReaderColorMigration.upgrade(ReaderSettings(), ReaderColorMigration.LEGACY_CUSTOM_THEME)
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.CUSTOM_BG, bg)
        assertEquals(ReaderPalette.CUSTOM_FG, fg)
        assertTrue("the starting point must be legible", ReaderPalette.isLegible(fg, bg))
    }

    @Test
    fun `an old custom theme keeps the heading and link colours it had, and invents none`() {
        val withHeading = ReaderColorMigration.upgrade(
            ReaderSettings(customHeading = 0xFF8B0000.toInt()),
            ReaderColorMigration.LEGACY_CUSTOM_THEME
        )
        assertTrue(withHeading.customizes(ReaderColorRole.HEADING))
        assertFalse(withHeading.customizes(ReaderColorRole.LINK))
    }

    @Test
    fun `an old preset keeps its page, and the colours it was holding stay held`() {
        // The failure this rules out: a reader who tried a deep green page once, went back to
        // Sepia, and opens their book months later in a colour they cannot account for.
        val stored = ReaderSettings(
            theme = ReaderTheme.SEPIA,
            customBackground = 0xFF102030.toInt(),
            customText = 0xFFEEDDCC.toInt(),
            customHeading = 0xFF8B0000.toInt()
        )
        val s = ReaderColorMigration.upgrade(stored, "SEPIA")
        assertFalse(s.useCustomColors)
        assertTrue(s.customRoles.isEmpty())
        val (bg, fg) = pageAndInk(s)!!
        assertEquals(ReaderPalette.SEPIA_BG, bg)
        assertEquals(ReaderPalette.SEPIA_FG, fg)
        assertEquals(0xFF102030.toInt(), s.held(ReaderColorRole.PAGE))
    }

    @Test
    fun `a theme that is missing or unreadable takes nothing over`() {
        listOf(null, "", "MIDNIGHT").forEach { stored ->
            assertTrue(ReaderColorMigration.upgrade(ReaderSettings(), stored).customRoles.isEmpty())
        }
    }

    @Test
    fun `not having chosen a colour survives sanitizing`() {
        val s = ReaderSettings().sanitized()
        assertNull(s.customBackground)
        assertNull(s.customText)
    }

    @Test
    fun `the reader's colours warm with everything else`() {
        val s = ReaderSettings(warmth = 1f).taking(
            ReaderColorRole.PAGE to 0xFFFFFFFF.toInt(),
            ReaderColorRole.TEXT to 0xFF3366CC.toInt()
        )
        val (bg, fg) = pageAndInk(s)!!
        assertTrue("a warmed page loses blue like any other", (bg and 0xFF) < 0xFF)
        assertTrue((fg and 0xFF) < 0xCC)
    }

    // --- Hex codes ---------------------------------------------------------------------------

    @Test
    fun `hex codes are read in the forms people actually type`() {
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("#AABBCC"))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("aabbcc"))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("  #ABC  "))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("0xFFAABBCC"))
    }

    @Test
    fun `a hex code that is not one is refused rather than guessed at`() {
        assertNull(ReaderPalette.parseHex(""))
        assertNull(ReaderPalette.parseHex("#"))
        assertNull(ReaderPalette.parseHex("#AABB"))
        assertNull(ReaderPalette.parseHex("#GGHHII"))
        assertNull(ReaderPalette.parseHex("cornflower"))
    }

    @Test
    fun `an alpha-less code still comes back opaque`() {
        assertEquals(0xFF, (ReaderPalette.parseHex("#00AABBCC")!! ushr 24) and 0xFF)
        assertEquals(0xFF, (ReaderPalette.parseHex("000000")!! ushr 24) and 0xFF)
    }

    @Test
    fun `a colour round-trips through its hex code`() {
        listOf(ReaderPalette.PAPER_BG, ReaderPalette.SEPIA_FG, ReaderPalette.BLACK, 0xFFFFFFFF.toInt())
            .forEach { assertEquals(it, ReaderPalette.parseHex(ReaderPalette.hex(it))) }
    }

    // --- Contrast ----------------------------------------------------------------------------

    @Test
    fun `contrast runs from one for a colour on itself to twenty-one for black on white`() {
        assertEquals(1.0, ReaderPalette.contrast(ReaderPalette.PAPER_BG, ReaderPalette.PAPER_BG), 0.001)
        assertEquals(21.0, ReaderPalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `contrast does not care which colour is named first`() {
        val a = 0xFF2B2B2B.toInt()
        val b = 0xFFFBF7EF.toInt()
        assertEquals(ReaderPalette.contrast(a, b), ReaderPalette.contrast(b, a), 0.0001)
    }

    @Test
    fun `every shipped theme is legible, and a pair that is not gets called out`() {
        listOf(ReaderTheme.PAPER, ReaderTheme.SEPIA, ReaderTheme.NIGHT).forEach { theme ->
            listOf(true, false).forEach { trueBlack ->
                val bg = ReaderPalette.background(theme, trueBlack)!!
                val fg = ReaderPalette.foreground(theme, trueBlack)!!
                assertTrue("$theme (trueBlack=$trueBlack) must be readable", ReaderPalette.isLegible(fg, bg))
            }
        }
        // Grey on cream is the mistake the warning exists for.
        assertFalse(ReaderPalette.isLegible(0xFFBBBBBB.toInt(), ReaderPalette.PAPER_BG))
        assertFalse(ReaderPalette.isLegible(ReaderPalette.BLACK, ReaderPalette.BLACK))
    }

    @Test
    fun `luminance follows brightness rather than raw channel values`() {
        // Green reads far brighter than blue at the same channel value; a naive average would not
        // notice, and would wave through blue text on a green page.
        assertTrue(ReaderPalette.luminance(0xFF00FF00.toInt()) > ReaderPalette.luminance(0xFF0000FF.toInt()))
        assertTrue(ReaderPalette.luminance(0xFFFFFFFF.toInt()) > ReaderPalette.luminance(0xFF808080.toInt()))
        assertTrue(ReaderPalette.luminance(0xFF808080.toInt()) > ReaderPalette.luminance(ReaderPalette.BLACK))
    }

    // --- Highlights ---------------------------------------------------------------------------

    @Test
    fun `mixing runs from all base to all tint`() {
        val tint = 0xFFFF0000.toInt()
        val base = 0xFF0000FF.toInt()
        assertEquals(base, ReaderPalette.mix(tint, base, 0f))
        assertEquals(tint, ReaderPalette.mix(tint, base, 1f))
        val half = ReaderPalette.mix(tint, base, 0.5f)
        assertTrue((half ushr 16 and 0xFF) in 0x7E..0x80)
        assertTrue((half and 0xFF) in 0x7E..0x80)
    }

    @Test
    fun `a mixed colour is always opaque`() {
        assertEquals(0xFF, (ReaderPalette.mix(0x00FF0000, 0x000000FF, 0.5f) ushr 24) and 0xFF)
    }

    @Test
    fun `a highlight is visible on a light page and on a dark one`() {
        val tint = HighlightColor.YELLOW.tint
        val onPaper = ReaderPalette.highlight(tint, ReaderPalette.PAPER_BG, ReaderPalette.PAPER_FG)
        val onNight = ReaderPalette.highlight(tint, ReaderPalette.NIGHT_BG, ReaderPalette.NIGHT_FG)
        // "Visible" means it is not the page it is drawn on. A fixed colour cannot manage both.
        assertNotEquals(ReaderPalette.PAPER_BG, onPaper)
        assertNotEquals(ReaderPalette.NIGHT_BG, onNight)
        assertNotEquals("the same tint cannot look the same on both pages", onPaper, onNight)
    }

    @Test
    fun `a highlight never makes its own sentence unreadable`() {
        // The failure this exists to prevent: a mark so strong the words under it are gone, which
        // reads as a broken book rather than as a colour choice.
        val pages = listOf(
            ReaderPalette.PAPER_BG to ReaderPalette.PAPER_FG,
            ReaderPalette.SEPIA_BG to ReaderPalette.SEPIA_FG,
            ReaderPalette.NIGHT_BG to ReaderPalette.NIGHT_FG,
            ReaderPalette.BLACK to ReaderPalette.TRUE_BLACK_FG,
            0xFF102030.toInt() to 0xFFEEDDCC.toInt()
        )
        pages.forEach { (page, text) ->
            HighlightColor.entries.forEach { colour ->
                val shade = ReaderPalette.highlight(colour.tint, page, text)
                assertTrue(
                    "${colour.label} on ${ReaderPalette.hex(page)} left text at " +
                        "${ReaderPalette.contrast(text, shade)}",
                    ReaderPalette.isLegible(text, shade)
                )
            }
        }
    }

    @Test
    fun `a highlight is as strong as the text on top allows`() {
        // Black text on white leaves plenty of room, so the mark comes out stronger than it does
        // where the text is already close to the page.
        val roomy = ReaderPalette.highlight(
            HighlightColor.BLUE.tint, 0xFFFFFFFF.toInt(), ReaderPalette.BLACK
        )
        val tight = ReaderPalette.highlight(
            HighlightColor.BLUE.tint, 0xFFFFFFFF.toInt(), 0xFF767676.toInt()
        )
        assertTrue(
            "a page with contrast to spare should carry a stronger mark",
            ReaderPalette.contrast(roomy, 0xFFFFFFFF.toInt()) >
                ReaderPalette.contrast(tight, 0xFFFFFFFF.toInt())
        )
    }

    @Test
    fun `an impossible page still gets a visible mark rather than none`() {
        // Mid-grey text on mid-grey: nothing keeps it legible, and a highlight nobody can see is
        // worse than a faint one.
        val page = 0xFF808080.toInt()
        val shade = ReaderPalette.highlight(HighlightColor.PINK.tint, page, 0xFF8A8A8A.toInt())
        assertNotEquals(page, shade)
    }

    @Test
    fun `the five highlight colours are actually distinguishable on a page`() {
        val shades = HighlightColor.entries.map {
            ReaderPalette.highlight(it.tint, ReaderPalette.PAPER_BG, ReaderPalette.PAPER_FG)
        }
        assertEquals("a colour that means something has to be told apart", shades.size, shades.toSet().size)
    }

    @Test
    fun `an unknown stored colour is a plain highlight rather than a crash`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.from(null))
        assertEquals(HighlightColor.YELLOW, HighlightColor.from("CHARTREUSE"))
        assertEquals(HighlightColor.BLUE, HighlightColor.from("BLUE"))
    }

    // --- Volume keys -------------------------------------------------------------------------

    @Test
    fun `volume keys do nothing until they are turned on`() {
        val off = ReaderSettings(volumeKeyTurns = false)
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = true, off))
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = false, off))
    }

    @Test
    fun `down is forward`() {
        val on = ReaderSettings(volumeKeyTurns = true)
        assertEquals(VolumeKeys.Action.NEXT_PAGE, VolumeKeys.action(volumeUp = false, on))
        assertEquals(VolumeKeys.Action.PREVIOUS_PAGE, VolumeKeys.action(volumeUp = true, on))
    }

    @Test
    fun `reversing swaps them and nothing else`() {
        val flipped = ReaderSettings(volumeKeyTurns = true, volumeKeysReversed = true)
        assertEquals(VolumeKeys.Action.PREVIOUS_PAGE, VolumeKeys.action(volumeUp = false, flipped))
        assertEquals(VolumeKeys.Action.NEXT_PAGE, VolumeKeys.action(volumeUp = true, flipped))
    }

    @Test
    fun `reversing while disabled still does nothing`() {
        val s = ReaderSettings(volumeKeyTurns = false, volumeKeysReversed = true)
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = true, s))
    }
}
