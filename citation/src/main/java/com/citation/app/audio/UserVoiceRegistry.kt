package com.citation.app.audio

import com.citation.core.speech.CustomVoice
import com.citation.core.speech.VoiceModel
import com.citation.core.speech.VoiceOrigin
import com.citation.core.speech.VoiceQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the reader knows about the voices they added, written down beside the voices themselves.
 *
 * A catalogue voice needs no record: the build states its name, language, quality and sample rate,
 * and two files on disk are enough to know it is installed. A voice the reader added has none of
 * that anywhere — a stray `ada.onnx` in the store has no name to show, no language to sort by and
 * nothing to say about what it cost. That is exactly why [VoiceStore] refuses to list files it does
 * not recognise, and this manifest is what makes a reader's own voice recognisable: the same handful
 * of facts the catalogue states in code, stated in a file instead.
 *
 * It lives *in the voices directory* rather than beside the speech settings, because it describes
 * that directory and nothing else. A voice's definition and a voice's files go together: forgetting
 * one while keeping the other leaves either a name for a voice that cannot speak or a file nothing
 * can name, and keeping them in one place makes "remove this voice" a single, obvious operation.
 *
 * Decoding is total and suspicious in equal measure. Total, like every other settings codec here: a
 * manifest written by an older build, or half-written by a process that died, must cost the reader
 * the entries it cannot read and not the ones it can. Suspicious because this is the one file in the
 * store whose *contents* decide which paths get opened — so an id is re-checked against
 * [CustomVoice.isSafeId] on the way in, and an entry that fails is dropped rather than trusted
 * because it was already on the disk.
 */
class UserVoiceRegistry(private val file: File) {

    @Volatile
    private var cached: List<VoiceModel>? = null

    /** Every voice the reader has added, in the order they added them. */
    fun voices(): List<VoiceModel> = cached ?: read().also { cached = it }

    /** Record [model] as one of the reader's own. Returns it, saved or not — see [save]. */
    fun add(model: VoiceModel): VoiceModel {
        save(voices().filterNot { it.id == model.id } + model)
        return model
    }

    /** Forget [id], which is what deleting a voice the reader added has to do as well as unlink. */
    fun forget(id: String): Boolean {
        val remaining = voices().filterNot { it.id == id }
        if (remaining.size == voices().size) return false
        save(remaining)
        return true
    }

    /**
     * Record what [id] actually turned out to weigh.
     *
     * A reader types a name and a link, not a byte count, so an added voice is defined with no size
     * and the picker has nothing to show for it. The download knows, once it has landed, and this is
     * where that becomes the "61 MB" beside the voice's name and part of the storage total.
     */
    fun resize(id: String, bytes: Long) {
        if (bytes <= 0) return
        val voices = voices()
        if (voices.none { it.id == id }) return
        save(voices.map { if (it.id == id) it.copy(sizeBytes = bytes) else it })
    }

    /**
     * Write the manifest, best effort.
     *
     * Best effort in the same sense [SpeechSettingsStore] means it — a disk that will not take a
     * kilobyte of JSON must not take the reader's book down with it — but with the in-memory copy
     * updated either way, so the voice they just added is usable for this run even if it will not be
     * remembered for the next one.
     */
    private fun save(voices: List<VoiceModel>) {
        cached = voices
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONArray().apply { voices.forEach { put(encode(it)) } }.toString()
            )
        }
    }

    private fun read(): List<VoiceModel> {
        val array = runCatching {
            if (file.isFile) JSONArray(file.readText()) else null
        }.getOrNull() ?: return emptyList()
        val voices = mutableListOf<VoiceModel>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            decode(entry)?.let { voices += it }
        }
        return voices
    }

    private fun encode(model: VoiceModel) = JSONObject().apply {
        put("id", model.id)
        put("name", model.name)
        put("language", model.language)
        put("quality", model.quality.name)
        put("sampleRate", model.sampleRate)
        put("sizeBytes", model.sizeBytes)
        put("modelUrl", model.modelUrl)
        put("tokensUrl", model.tokensUrl)
        put("speakers", model.speakers)
        put("speaker", model.speaker)
        model.notes?.let { put("notes", it) }
    }

    private fun decode(json: JSONObject): VoiceModel? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        // The one check that is not about tolerating an old file: an id is a path inside the voice
        // store, and this file is editable by anything that can reach the app's data.
        if (!CustomVoice.isSafeId(id)) return null
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: id
        val quality = runCatching { VoiceQuality.valueOf(json.optString("quality")) }
            .getOrDefault(VoiceQuality.MEDIUM)
        return VoiceModel(
            id = id,
            name = name,
            language = json.optString("language").takeIf { it.isNotBlank() } ?: "en-US",
            quality = quality,
            sampleRate = json.optInt("sampleRate", CustomVoice.DEFAULT_SAMPLE_RATE),
            sizeBytes = json.optLong("sizeBytes", 0L),
            modelUrl = json.optString("modelUrl"),
            tokensUrl = json.optString("tokensUrl"),
            speakers = json.optInt("speakers", 1),
            speaker = json.optInt("speaker", 0),
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            origin = VoiceOrigin.USER
        )
    }
}
