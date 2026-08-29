package com.logistics.app.net

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.data.store.RecipeShotStore
import com.logistics.app.logic.RecipeTextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads recipes out of **screenshots**. The counterpart to [RecipeFetcher]: that one goes to a page
 * for schema.org data, this one goes to a picture for whatever text is printed on it.
 *
 * Plenty of the recipes people actually cook never had a web page to link — a photograph of a
 * handwritten card, a story slide, a page of a cookbook, a text from a relative. Screenshotting one
 * is the gesture people already make; this turns that screenshot into a recipe with ingredients you
 * can shop and a method you can cook from, and the picture is kept beside it
 * ([RecipeShotStore]) so the parse can always be checked against the original.
 *
 * ### On-device, offline, no account
 *
 * Recognition is ML Kit's **bundled** Latin text recogniser: the model ships inside the app, so it
 * needs no network, no Play Services download, and nothing about the picture leaves the phone —
 * which is the only version of this feature that belongs in an offline-first suite. (Logistics
 * already declares INTERNET for the recipe-link import; this path never uses it.)
 *
 * The layout reading — title, servings, ingredients, method — is the framework-free, unit-tested
 * [RecipeTextParser]. This class is only the shim that gets pixels to it, exactly as
 * [PdfTextExtractor] is for the Walmart order PDF.
 */
object RecipeScreenshotReader {

    class ReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * OCR each picture in order and parse the lot as one recipe — a recipe screenshotted in two or
     * three shots (ingredients, then method) is the normal case, and reading them as one page is
     * what makes that work. [fallbackName] names the recipe when the pictures carry no title.
     */
    suspend fun read(
        context: Context,
        sources: List<Uri>,
        fallbackName: String = "Screenshot recipe"
    ): ParsedRecipe {
        if (sources.isEmpty()) throw ReadException("Pick a screenshot first.")
        val store = RecipeShotStore(context)
        val text = StringBuilder()
        var readAny = false
        for (source in sources) {
            val bitmap = withContext(Dispatchers.IO) { store.decode(source) }
                ?: continue
            val recognised = runCatching { recognise(bitmap) }
                .also { bitmap.recycle() }
                .getOrElse { throw ReadException("Couldn't read text from that screenshot.", it) }
            readAny = true
            if (recognised.isNotBlank()) text.append(recognised).append('\n')
        }
        if (!readAny) throw ReadException("Couldn't open that picture.")
        if (text.isBlank()) {
            throw ReadException("No text found in that screenshot — you can still type the recipe in by hand.")
        }
        return RecipeTextParser.parse(text.toString(), fallbackName)
    }

    /**
     * One bitmap → its text, line by line in reading order.
     *
     * ML Kit hands back blocks of lines rather than a page of text, and the line is the unit that
     * matters here: an ingredient is a line, and a block boundary in the middle of a list must not
     * fuse two ingredients into one.
     */
    private suspend fun recognise(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { block -> block.lines }
                    .joinToString("\n") { it.text }
                recognizer.close()
                cont.resume(lines)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                cont.resumeWithException(error)
            }
    }
}
