package com.citation.core.speech

/**
 * Every voice this reader can have: the ones Citation offers, plus the ones they added.
 *
 * [VoiceCatalog] answers "what does this build know how to fetch", which is a property of the build
 * and the same on every phone. Once readers can add their own, three questions stop having that
 * answer — what is on this device, what does an id resolve to, and which ids are already spoken for
 * — and every one of them is asked from somewhere that must not care where a voice came from. The
 * store lists installed voices, the picker resolves the reader's saved [SpeechSettings.voiceId], and
 * adding a voice needs a filename nothing else is using. Answering those in one place is what keeps
 * a reader's own narrator from being a second-class one that the store can see but the picker
 * cannot, or vice versa.
 *
 * The catalogue wins an id clash. It cannot happen through [CustomVoice.idFor], which is given every
 * taken id and counts past them, but a manifest is a file on a disk and files can be edited: a
 * shadowed catalogue voice would be one whose files the reader can delete but whose entry keeps
 * coming back, and there is no version of that a reader would enjoy.
 */
object VoiceLibrary {

    /** The catalogue, then [added] in the order the reader added them. */
    fun known(added: List<VoiceModel>): List<VoiceModel> {
        if (added.isEmpty()) return VoiceCatalog.VOICES
        val catalogue = VoiceCatalog.VOICES.map { it.id }.toSet()
        return VoiceCatalog.VOICES + added.filter { it.id !in catalogue }
    }

    /** What [id] names, whether Citation offered it or the reader added it; `null` for neither. */
    fun modelFor(id: String, added: List<VoiceModel>): VoiceModel? =
        VoiceCatalog.modelFor(id) ?: added.firstOrNull { it.id == id }

    /** Every id already in use, which is what a new voice has to be given a name around. */
    fun takenIds(added: List<VoiceModel>): Set<String> =
        (VoiceCatalog.VOICES.map { it.id } + added.map { it.id }).toSet()

    /** The reader's own voices, newest last — the "Your voices" list. */
    fun mine(added: List<VoiceModel>): List<VoiceModel> =
        added.filter { it.origin == VoiceOrigin.USER }
}
