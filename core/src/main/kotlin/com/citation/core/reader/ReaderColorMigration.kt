package com.citation.core.reader

/**
 * Reads the colour half of a settings row written before the page and the prose became separate
 * choices.
 *
 * Until now the reader's own colours were reached through a fifth theme called `CUSTOM`: choosing it
 * meant "the page and the text are both mine", and every other theme held those colours without
 * using them. Colours are now taken over one role at a time ([ReaderColorRole]) on top of whichever
 * theme is selected, which is a strictly larger set of arrangements — but it means an old row says
 * *which* colours are in force only through a theme name that no longer exists.
 *
 * This is where that is translated, in `:core` rather than in the codec, because it is the one piece
 * of the upgrade that can get a reader's page wrong. Getting it wrong in the safe direction is not
 * enough either: a row that said Sepia and *held* a deep green page must come back as Sepia, or a
 * reader who tried a colour once opens their book in it months later and cannot say why.
 */
object ReaderColorMigration {

    /** The theme name the old settings used for "the reader's own colours". */
    const val LEGACY_CUSTOM_THEME = "CUSTOM"

    /**
     * Put [settings] — decoded field by field from an old row — back into the state that row
     * described, given the `theme` it stored.
     *
     * The old `CUSTOM` theme becomes the page and the text taken over, in the colours it held, with
     * its heading and link colours carried across where it had them; the page it falls back to when
     * it held none is the same one it drew, so nothing changes on screen. Any other theme is left
     * exactly as it was, with its held colours held and none of them in force.
     */
    fun upgrade(settings: ReaderSettings, storedTheme: String?): ReaderSettings {
        if (storedTheme?.trim() != LEGACY_CUSTOM_THEME) return settings.copy(customRoles = emptySet())
        val roles = mutableSetOf(ReaderColorRole.PAGE, ReaderColorRole.TEXT)
        if (settings.customHeading != null) roles += ReaderColorRole.HEADING
        if (settings.customLink != null) roles += ReaderColorRole.LINK
        return settings.copy(
            // The theme a `CUSTOM` row was on is not recorded anywhere — it was overwritten the
            // moment the reader chose their own colours — so what is left underneath is the app's
            // own, which is what a reader handing a role back should land on.
            theme = ReaderTheme.SYSTEM,
            customRoles = roles,
            customBackground = settings.customBackground ?: ReaderPalette.CUSTOM_BG,
            customText = settings.customText ?: ReaderPalette.CUSTOM_FG
        )
    }
}
