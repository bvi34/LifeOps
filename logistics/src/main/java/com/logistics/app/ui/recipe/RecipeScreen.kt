package com.logistics.app.ui.recipe

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.Recipe
import com.logistics.app.data.model.IngredientRow
import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.data.model.RecipeNote
import com.logistics.app.data.model.RecipeShot
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.data.repository.RecipeNoteRepository
import com.logistics.app.data.repository.RecipeShotRepository
import com.logistics.app.logic.RecipeNoteSummaries
import com.logistics.app.net.RecipeFetcher
import com.logistics.app.net.RecipeScreenshotReader
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed interface RecipeImportState {
    object Idle : RecipeImportState
    object Working : RecipeImportState

    /**
     * A parse the user can correct before it becomes a recipe. [shots] are the screenshots it was
     * read out of (empty for a link import) — they're saved with the recipe when it's kept, so the
     * original is always there to check the parse against.
     */
    data class Preview(val recipe: ParsedRecipe, val shots: List<Uri> = emptyList()) : RecipeImportState
    data class Done(val name: String) : RecipeImportState
    data class Error(val message: String) : RecipeImportState
}

/** What an expanded recipe row shows: the calorie floor and the lines it's made of. */
data class RecipeDetail(
    val recipeId: String,
    val perServing: NutritionTotals? = null,
    val ingredients: List<IngredientRow> = emptyList()
)

class RecipeViewModel(
    private val catalog: LifeOpsCatalog,
    private val shotRepo: RecipeShotRepository,
    private val noteRepo: RecipeNoteRepository,
    private val context: Context
) : ViewModel() {

    val recipes: StateFlow<List<Recipe>> =
        catalog.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every recipe's screenshots, so a row can show what it was read out of without a query each. */
    val shots: StateFlow<Map<String, List<RecipeShot>>> =
        shotRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Every recipe's notes, grouped by recipe. They ride alongside the recipes rather than inside
     * them: the recipe is LifeOps' — shared with the meal plan, the diary, the shopping list — and
     * what somebody thought of it after cooking it is Logistics' own, editable without ever
     * rewriting the method.
     */
    val notes: StateFlow<Map<String, List<RecipeNote>>> =
        noteRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _import = MutableStateFlow<RecipeImportState>(RecipeImportState.Idle)
    val importState: StateFlow<RecipeImportState> = _import.asStateFlow()

    private val _expanded = MutableStateFlow<RecipeDetail?>(null)
    val expanded: StateFlow<RecipeDetail?> = _expanded.asStateFlow()

    var status by mutableStateOf<String?>(null)
        private set

    fun clearStatus() { status = null }

    fun reset() { _import.value = RecipeImportState.Idle }

    fun fetch(url: String) = viewModelScope.launch {
        if (url.isBlank()) { _import.value = RecipeImportState.Error("Enter a recipe link first."); return@launch }
        _import.value = RecipeImportState.Working
        runCatching { RecipeFetcher.fetch(url) }
            .onSuccess { _import.value = RecipeImportState.Preview(it) }
            .onFailure { _import.value = RecipeImportState.Error(it.message ?: "Couldn't import that link.") }
    }

    /** OCR the picked screenshots and offer the parse for correction. */
    fun readScreenshots(sources: List<Uri>) = viewModelScope.launch {
        if (sources.isEmpty()) return@launch
        _import.value = RecipeImportState.Working
        runCatching { RecipeScreenshotReader.read(context, sources) }
            .onSuccess { _import.value = RecipeImportState.Preview(it, sources) }
            .onFailure { _import.value = RecipeImportState.Error(it.message ?: "Couldn't read that screenshot.") }
    }

    /** Keeps the (possibly corrected) parse as a LifeOps recipe, with its screenshots attached. */
    fun save(parsed: ParsedRecipe, sources: List<Uri>) = viewModelScope.launch {
        _import.value = RecipeImportState.Working
        runCatching {
            val recipe = catalog.createImportedRecipe(parsed)
            if (sources.isNotEmpty()) shotRepo.attach(recipe.id, sources)
            recipe
        }
            .onSuccess { _import.value = RecipeImportState.Done(it.name) }
            .onFailure { _import.value = RecipeImportState.Error(it.message ?: "Couldn't save the recipe.") }
    }

    /** Attaches screenshots to a recipe that already exists — a picture of the card you cook from. */
    fun attachTo(recipe: Recipe, sources: List<Uri>) = viewModelScope.launch {
        val saved = shotRepo.attach(recipe.id, sources)
        status = if (saved > 0) "Added $saved screenshot(s) to \"${recipe.name}\"."
        else "Couldn't read that picture."
    }

    fun removeShot(shotId: String) = viewModelScope.launch { shotRepo.remove(shotId) }

    /**
     * Keeps a note against a recipe — a new one, or a rewrite of [editing]. Nothing is written to
     * the recipe itself. A note with neither words nor stars is refused by the repository rather
     * than stored empty, and that refusal is said out loud instead of swallowed.
     */
    fun saveNote(recipe: Recipe, text: String, rating: Int?, editing: RecipeNote? = null) = viewModelScope.launch {
        val saved =
            if (editing == null) noteRepo.add(recipe.id, text, rating)
            else noteRepo.update(editing.id, text, rating)
        status = when {
            saved == null -> "Write something, or leave a rating."
            editing == null -> "Note added to \"${recipe.name}\"."
            else -> "Note updated."
        }
    }

    fun removeNote(note: RecipeNote) = viewModelScope.launch {
        noteRepo.remove(note.id)
        status = "Note deleted."
    }

    fun toggleExpanded(recipe: Recipe) = viewModelScope.launch {
        if (_expanded.value?.recipeId == recipe.id) {
            _expanded.value = null
            return@launch
        }
        _expanded.value = RecipeDetail(recipe.id)
        _expanded.value = RecipeDetail(
            recipeId = recipe.id,
            perServing = catalog.getRecipeNutrition(recipe.id)?.perServing,
            ingredients = catalog.ingredientRows(recipe.id)
        )
    }

    /** Decoding happens off the main thread; a thumbnail is a file read, not a field. */
    suspend fun bitmap(shot: RecipeShot): Bitmap? = withContext(Dispatchers.IO) { shotRepo.bitmapFor(shot) }

    class Factory(
        private val catalog: LifeOpsCatalog,
        private val shotRepo: RecipeShotRepository,
        private val noteRepo: RecipeNoteRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecipeViewModel(catalog, shotRepo, noteRepo, context) as T
    }
}

@Composable
fun RecipeScreen(
    vm: RecipeViewModel,
    initialUrl: String? = null,
    initialImages: List<Uri> = emptyList()
) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val shots by vm.shots.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var viewing by remember { mutableStateOf<RecipeShot?>(null) }
    var editingNote by remember { mutableStateOf<NoteEditorTarget?>(null) }
    var deletingNote by remember { mutableStateOf<RecipeNote?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // A recipe shared into Logistics — a link, or screenshots — is imported the moment it arrives.
    LaunchedEffect(initialUrl) { if (!initialUrl.isNullOrBlank()) vm.fetch(initialUrl) }
    LaunchedEffect(initialImages) { if (initialImages.isNotEmpty()) vm.readScreenshots(initialImages) }

    LaunchedEffect(vm.status) {
        vm.status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    val pickForImport = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SHOTS)
    ) { uris -> if (uris.isNotEmpty()) vm.readScreenshots(uris) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Recipes", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Grab a recipe from a link — or from screenshots of one. Either way it lands in LifeOps' " +
                        "recipe book, ingredients and all, and everything else in the suite can use it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Recipe URL") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                when (val s = importState) {
                    is RecipeImportState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is RecipeImportState.Error -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImportButtons(
                            onLink = { vm.fetch(url) },
                            linkEnabled = url.isNotBlank(),
                            onScreenshots = { pickForImport.launch(imageRequest()) }
                        )
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    is RecipeImportState.Done -> ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Saved \"${s.name}\" to LifeOps", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { url = ""; vm.reset() }) { Text("Import another") }
                        }
                    }
                    is RecipeImportState.Preview -> RecipePreviewCard(
                        parsed = s.recipe,
                        shots = s.shots,
                        onSave = { edited -> vm.save(edited, s.shots) },
                        onCancel = { vm.reset() }
                    )
                    RecipeImportState.Idle -> ImportButtons(
                        onLink = { vm.fetch(url) },
                        linkEnabled = url.isNotBlank(),
                        onScreenshots = { pickForImport.launch(imageRequest()) }
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item { Text("In your LifeOps recipe book (${recipes.size})", style = MaterialTheme.typography.titleSmall) }

            if (recipes.isEmpty()) {
                item { Text("No recipes yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(recipes, key = { it.id }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        shots = shots[recipe.id].orEmpty(),
                        notes = notes[recipe.id].orEmpty(),
                        detail = expanded?.takeIf { it.recipeId == recipe.id },
                        loadBitmap = { vm.bitmap(it) },
                        onToggle = { vm.toggleExpanded(recipe) },
                        onAttach = { uris -> vm.attachTo(recipe, uris) },
                        onOpenShot = { viewing = it },
                        onRemoveShot = { vm.removeShot(it.id) },
                        onAddNote = { editingNote = NoteEditorTarget(recipe) },
                        onEditNote = { editingNote = NoteEditorTarget(recipe, it) },
                        onRemoveNote = { deletingNote = it }
                    )
                }
            }
        }
    }

    viewing?.let { shot ->
        ShotViewer(shot = shot, loadBitmap = { vm.bitmap(it) }, onDismiss = { viewing = null })
    }

    editingNote?.let { target ->
        RecipeNoteDialog(
            recipeName = target.recipe.name,
            existing = target.note,
            onDismiss = { editingNote = null },
            onSave = { text, rating ->
                vm.saveNote(target.recipe, text, rating, target.note)
                editingNote = null
            }
        )
    }

    // A note is typed, not picked — deleting one by a mistap costs the words, so it asks first.
    // (A screenshot doesn't: the picture is still in the gallery it came from.)
    deletingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            title = { Text("Delete this note?") },
            text = { Text("The recipe itself stays exactly as it is.") },
            confirmButton = {
                TextButton(onClick = { vm.removeNote(note); deletingNote = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingNote = null }) { Text("Keep") } }
        )
    }
}

/** Which recipe the note editor is open for, and the note it is rewriting (null for a new one). */
private data class NoteEditorTarget(val recipe: Recipe, val note: RecipeNote? = null)

@Composable
private fun ImportButtons(onLink: () -> Unit, linkEnabled: Boolean, onScreenshots: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLink, enabled = linkEnabled, modifier = Modifier.weight(1f)) {
            Text("Import from link")
        }
        OutlinedButton(onClick = onScreenshots, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("From screenshots")
        }
    }
}

private fun imageRequest() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

/**
 * The parse, before it becomes a recipe — and editable, because OCR is a reading of a picture and
 * not a fact. Ingredients and steps are one per line, which is how they were read and how they're
 * saved; correcting a mis-read line is a keystroke rather than a re-import.
 */
@Composable
private fun RecipePreviewCard(
    parsed: ParsedRecipe,
    shots: List<Uri>,
    onSave: (ParsedRecipe) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(parsed) { mutableStateOf(parsed.name) }
    var servings by remember(parsed) { mutableStateOf(parsed.servings?.let { formatQty(it) } ?: "1") }
    var ingredients by remember(parsed) { mutableStateOf(parsed.ingredients.joinToString("\n")) }
    var steps by remember(parsed) { mutableStateOf(parsed.steps.joinToString("\n")) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check the import", style = MaterialTheme.typography.titleMedium)
            if (shots.isNotEmpty()) {
                Text(
                    "Read from ${shots.size} screenshot${if (shots.size == 1) "" else "s"} — they'll be kept with the recipe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = servings,
                onValueChange = { servings = it },
                label = { Text("Servings") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ingredients,
                onValueChange = { ingredients = it },
                label = { Text("Ingredients (one per line)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
            )
            OutlinedTextField(
                value = steps,
                onValueChange = { steps = it },
                label = { Text("Method (one step per line)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(
                            parsed.copy(
                                name = name.trim().ifBlank { parsed.name },
                                servings = servings.toDoubleOrNull() ?: parsed.servings,
                                ingredients = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        )
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Save to LifeOps") }
            }
        }
    }
}

@Composable
private fun RecipeCard(
    recipe: Recipe,
    shots: List<RecipeShot>,
    notes: List<RecipeNote>,
    detail: RecipeDetail?,
    loadBitmap: suspend (RecipeShot) -> Bitmap?,
    onToggle: () -> Unit,
    onAttach: (List<Uri>) -> Unit,
    onOpenShot: (RecipeShot) -> Unit,
    onRemoveShot: (RecipeShot) -> Unit,
    onAddNote: () -> Unit,
    onEditNote: (RecipeNote) -> Unit,
    onRemoveNote: (RecipeNote) -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SHOTS)
    ) { uris -> if (uris.isNotEmpty()) onAttach(uris) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append("${formatQty(recipe.servings)} serving(s)")
                    if (shots.isNotEmpty()) append(" · ${shots.size} screenshot${if (shots.size == 1) "" else "s"}")
                    RecipeNoteSummaries.label(RecipeNoteSummaries.summarize(notes))?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (detail != null) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                detail.perServing?.let { per ->
                    val missing = detail.ingredients.count { it.gapLabel != null }
                    Column {
                        Text(
                            "${per.calories.toInt()} kcal per serving · C ${per.carbsG.toInt()}g · " +
                                "P ${per.proteinG.toInt()}g · F ${per.fatG.toInt()}g",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (missing > 0) {
                            // The printed total is a floor, not a sum — say so rather than let a
                            // web- or screenshot-imported recipe read as "312 kcal" of fiction.
                            Text(
                                "At least — $missing of ${detail.ingredients.size} ingredients have no macros recorded.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (detail.ingredients.isNotEmpty()) {
                    Column {
                        detail.ingredients.forEach { row ->
                            Text(
                                "• ${formatQty(row.quantity)} ${row.unit} ${row.foodName}" +
                                    (row.gapLabel?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                recipe.instructions?.takeIf { it.isNotBlank() }?.let { method ->
                    Text(method, style = MaterialTheme.typography.bodySmall)
                }
                if (shots.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        shots.forEach { shot ->
                            ShotThumb(
                                shot = shot,
                                loadBitmap = loadBitmap,
                                onClick = { onOpenShot(shot) },
                                onRemove = { onRemoveShot(shot) }
                            )
                        }
                    }
                }
                TextButton(onClick = { picker.launch(imageRequest()) }) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (shots.isEmpty()) "Add a screenshot" else "Add another screenshot")
                }
                NotesSection(
                    notes = notes,
                    onAdd = onAddNote,
                    onEdit = onEditNote,
                    onRemove = onRemoveNote
                )
            }
        }
    }
}

@Composable
private fun ShotThumb(
    shot: RecipeShot,
    loadBitmap: suspend (RecipeShot) -> Bitmap?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, shot.id) { value = loadBitmap(shot) }
    Box {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Recipe screenshot",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 96.dp, height = 128.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClick() }
            )
        } else {
            Box(
                Modifier
                    .size(width = 96.dp, height = 128.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("…", style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Default.Close, contentDescription = "Remove screenshot", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ShotViewer(shot: RecipeShot, loadBitmap: suspend (RecipeShot) -> Bitmap?, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, shot.id) { value = loadBitmap(shot) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Recipe screenshot",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    )
                } ?: Text("That screenshot is missing from storage.", Modifier.padding(24.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

/**
 * What somebody thought of the recipe, kept **next to** it rather than in it.
 *
 * A recipe is LifeOps' and shared by the whole suite; "cut the sugar, it was cloying" is a reading
 * of one cook's evening, not a step of the method — so it reads as its own section, and editing a
 * note never touches the ingredients or the instructions above it.
 */
@Composable
private fun NotesSection(
    notes: List<RecipeNote>,
    onAdd: () -> Unit,
    onEdit: (RecipeNote) -> Unit,
    onRemove: (RecipeNote) -> Unit
) {
    val summary = RecipeNoteSummaries.summarize(notes)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notes & reviews", style = MaterialTheme.typography.labelLarge)
            summary.averageRating?.let { average ->
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    // Say what the average is *of*: nine notes and one rating is a well-documented
                    // recipe with a single opinion, and printing a bare "4" would hide that.
                    "${RecipeNoteSummaries.formatRating(average)} from ${summary.ratedCount} " +
                        "rating${if (summary.ratedCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (notes.isEmpty()) {
            Text(
                "Nothing yet — how did it turn out?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            notes.forEach { note ->
                NoteRow(note = note, onEdit = { onEdit(note) }, onRemove = { onRemove(note) })
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.RateReview, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (notes.isEmpty()) "Add a note" else "Add another note")
        }
    }
}

@Composable
private fun NoteRow(note: RecipeNote, onEdit: () -> Unit, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                note.rating?.let { StarRow(rating = it, size = 14.dp) }
                if (note.text.isNotBlank()) {
                    Text(note.text, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    // An edited note says so: a cooking log read months later shouldn't imply the
                    // words are the ones written on the night.
                    friendlyDate(note.createdAt) + (if (note.updatedAt != note.createdAt) " · edited" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit note", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Delete note", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Five stars, filled up to [rating]. Read-only unless [onRate] is given, in which case tapping the
 * star that is already the rating clears it — a verdict you can set but never take back is worse
 * than none, and a note with no rating is a perfectly good note.
 */
@Composable
private fun StarRow(rating: Int?, size: Dp, onRate: ((Int?) -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (RecipeNoteSummaries.MIN_STARS..RecipeNoteSummaries.MAX_STARS).forEach { star ->
            val filled = rating != null && star <= rating
            val icon = if (filled) Icons.Default.Star else Icons.Default.StarBorder
            val description = "$star star${if (star == 1) "" else "s"}"
            if (onRate == null) {
                Icon(
                    icon,
                    contentDescription = if (star == rating) description else null,
                    modifier = Modifier.size(size),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                IconButton(onClick = { onRate(if (rating == star) null else star) }) {
                    Icon(
                        icon,
                        contentDescription = description,
                        modifier = Modifier.size(size),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Writing or rewriting one note. Either half is optional on its own — stars with no words is a
 * verdict, words with no stars is most cooking notes — but both empty saves nothing, so the button
 * stays off until there is something to keep.
 */
@Composable
private fun RecipeNoteDialog(
    recipeName: String,
    existing: RecipeNote?,
    onDismiss: () -> Unit,
    onSave: (String, Int?) -> Unit
) {
    var text by remember(existing) { mutableStateOf(existing?.text.orEmpty()) }
    var rating by remember(existing) { mutableStateOf(existing?.rating) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Note on \"$recipeName\"" else "Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Kept with the recipe, not in it — the ingredients and method stay as imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarRow(rating = rating, size = 24.dp, onRate = { rating = it })
                    if (rating != null) {
                        TextButton(onClick = { rating = null }) { Text("Clear") }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("How did it go?") },
                    placeholder = { Text("Halve the salt, and it needed 10 more minutes.") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 200.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text, rating) },
                enabled = text.isNotBlank() || rating != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val noteDateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** An ISO-8601 stamp as a local date; the raw string if it can't be parsed, which beats blank. */
private fun friendlyDate(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(noteDateFmt) }.getOrDefault(iso)

/** Enough for a recipe shot across several screens; more than that is a cookbook, not a recipe. */
private const val MAX_SHOTS = 6
