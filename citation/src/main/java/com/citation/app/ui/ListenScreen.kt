package com.citation.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.core.speech.EnginePreference
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SkipGranularity
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SleepTimer
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.VoiceModel

/**
 * The Listen tab: the player, the voice, and everything about how a book is read aloud.
 *
 * A tab rather than a sheet inside the reader, for two reasons. Listening is not a property of the
 * page you happen to have open — it keeps going with the app closed, and the reader who wants to
 * change the speed or set a sleep timer is usually not looking at the book at that moment. And the
 * voice picker is a downloads screen: sizes, progress, deletions, a thing you come back to.
 *
 * Everything here reads and writes through [ReaderViewModel], which forwards to the narrator; none
 * of the decisions live in this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTab(vm: ReaderViewModel) {
    val narration by vm.narration.collectAsStateWithLifecycle()
    val settings by vm.speechSettings.collectAsStateWithLifecycle()
    val progress by vm.voiceProgress.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Listen") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            NowPlaying(vm, narration.status, narration.chapterOrdinal, narration.sleepRemainingMillis)
            PlaybackNotificationPrompt()
            Divider()
            VoiceSection(vm, settings, progress)
            Divider()
            PlaybackSection(vm, settings)
            Divider()
            ContentSection(vm, settings)
            status?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(16.dp).clickable { vm.clearStatus() },
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// --- Now playing -------------------------------------------------------------------------------

@Composable
private fun NowPlaying(
    vm: ReaderViewModel,
    status: NarrationStatus,
    chapterOrdinal: Int,
    sleepRemainingMillis: Long?
) {
    val title = vm.nowPlayingBook
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Now playing", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        if (title == null) {
            Text(
                "Nothing is being read aloud. Open a book and press play at the bottom of the " +
                    "page — the voice starts at the top of the page you are looking at.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            vm.speechFailure?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            return@Column
        }

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                vm.nowPlayingAuthor?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Text(
                    vm.nowPlayingChapter(chapterOrdinal) ?: "Chapter ${chapterOrdinal + 1}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.skipAloud(SkipGranularity.PARAGRAPH, forward = false) }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous paragraph")
                    }
                    IconButton(onClick = { vm.toggleAloud() }) {
                        Icon(
                            if (status == NarrationStatus.SPEAKING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (status == NarrationStatus.SPEAKING) "Pause" else "Play"
                        )
                    }
                    IconButton(onClick = { vm.skipAloud(SkipGranularity.PARAGRAPH, forward = true) }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next paragraph")
                    }
                    IconButton(onClick = { vm.stopAloud() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                    }
                    if (status == NarrationStatus.PREPARING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                SleepTimer.label(sleepRemainingMillis)?.let {
                    Text(
                        "Sleeping in $it",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Text("Sleep timer", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SleepMode.entries.forEach { mode ->
                AssistChip(onClick = { vm.setSleepTimer(mode) }, label = { Text(mode.label) })
            }
        }
        Text(
            "The voice fades out over the last twenty seconds rather than stopping mid-word.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Ask for notification permission, but only where it is actually needed and only while it is
 * actually missing.
 *
 * On Android 13+ the playback notification — which is also the lock-screen player and the thing
 * that makes the whole foreground service legible — is invisible without this. The book still reads
 * aloud, so this is a prompt rather than a gate: it says what is missing and what it costs, and
 * disappears once granted.
 */
@Composable
private fun PlaybackNotificationPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    if (granted) return

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "Playback controls are hidden",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Text(
            "Without notification permission there are no controls on the lock screen or in the " +
                "shade. Books still read aloud either way.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 2.dp)
        )
        OutlinedButton(
            onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) },
            modifier = Modifier.padding(top = 8.dp)
        ) { Text("Show playback controls") }
    }
}

// --- Voice -------------------------------------------------------------------------------------

@Composable
private fun VoiceSection(
    vm: ReaderViewModel,
    settings: SpeechSettings,
    downloading: Pair<String, Float>?
) {
    val installed by vm.installedVoices.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Voice", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            if (vm.neuralVoicesSupported) {
                "Downloaded voices run entirely on this device: no account, no per-sentence cost, " +
                    "and nothing about what you read leaves the phone."
            } else {
                "This build has no neural voice runtime, so your device's own speech engine is " +
                    "used. It is the only engine that reports word boundaries, so it is also the " +
                    "one that can highlight word by word."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnginePreference.entries.forEach { preference ->
                FilterChip(
                    selected = settings.engine == preference,
                    onClick = { vm.updateSpeech { it.copy(engine = preference) } },
                    label = { Text(preference.label) }
                )
            }
        }

        if (installed.isNotEmpty()) {
            Text(
                "Installed",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            installed.forEach { voice ->
                VoiceRow(
                    model = voice.model,
                    installed = true,
                    selected = settings.voiceId == voice.model.id,
                    downloadProgress = null,
                    onSelect = { vm.setVoice(voice.model.id) },
                    onInstall = {},
                    onDelete = { vm.deleteVoice(voice.model) }
                )
            }
            Text(
                "Using ${VoiceModel.megabytes(vm.voiceStorageBytes())}.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        val installedIds = installed.map { it.model.id }.toSet()
        val available = vm.voiceCatalog().filter { it.id !in installedIds }
        if (available.isNotEmpty()) {
            Text(
                "Available to download",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            available.forEach { model ->
                VoiceRow(
                    model = model,
                    installed = false,
                    selected = false,
                    downloadProgress = downloading?.takeIf { it.first == model.id }?.second,
                    onSelect = {},
                    onInstall = { vm.installVoice(model) },
                    onDelete = {}
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    model: VoiceModel,
    installed: Boolean,
    selected: Boolean,
    downloadProgress: Float?,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(enabled = installed) { onSelect() }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.name,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(model.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                // Stated before the megabytes are spent, not after.
                model.notes?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            when {
                downloadProgress != null ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                installed -> IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${model.name}")
                }
                else -> IconButton(onClick = onInstall) {
                    Icon(Icons.Filled.Download, contentDescription = "Download ${model.name}")
                }
            }
        }
        downloadProgress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

// --- Playback ----------------------------------------------------------------------------------

@Composable
private fun PlaybackSection(vm: ReaderViewModel, settings: SpeechSettings) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Playback", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

        Text("Speed", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpeechSettings.RATE_STEPS.forEach { step ->
                FilterChip(
                    selected = kotlin.math.abs(settings.rate - step) < 0.01f,
                    onClick = { vm.setSpeechRate(step) },
                    label = { Text(settings.copy(rate = step).rateLabel) }
                )
            }
        }

        SettingSwitch(
            title = "Continue in the background",
            subtitle = "Keep reading with the app closed and the screen off, with controls on the " +
                "lock screen. Turn this off to make the voice a feature of the page: it stops when " +
                "you leave the reader and picks up where it stopped.",
            checked = settings.continueInBackground,
            onChange = { vm.updateSpeech { s -> s.copy(continueInBackground = it) } }
        )
        SettingSwitch(
            title = "Roll on to the next chapter",
            subtitle = "Otherwise the voice stops at the end of the chapter it is in.",
            checked = settings.autoAdvanceChapter,
            onChange = { vm.updateSpeech { s -> s.copy(autoAdvanceChapter = it) } }
        )
        SettingSwitch(
            title = "Announce chapter titles",
            subtitle = "A listener has no page to glance at; without this, chapters run together.",
            checked = settings.announceChapterTitle,
            onChange = { vm.updateSpeech { s -> s.copy(announceChapterTitle = it) } }
        )
        SettingSwitch(
            title = "Count listening toward your reading pace",
            subtitle = "Off by default. The “time left” estimates are learned from how fast you " +
                "actually read; time spent listening measures a voice's speaking rate instead, and " +
                "would drag every estimate in the app toward a number about nobody.",
            checked = settings.bankListeningTowardPace,
            onChange = { vm.updateSpeech { s -> s.copy(bankListeningTowardPace = it) } }
        )
    }
}

// --- What gets read ----------------------------------------------------------------------------

@Composable
private fun ContentSection(vm: ReaderViewModel, settings: SpeechSettings) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("What gets read", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            "Changing these re-plans the open chapter; the voice keeps its place.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )
        SettingSwitch(
            title = "Headings",
            subtitle = "What a listener navigates by.",
            checked = settings.content.speakHeadings,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakHeadings = on)) } }
        )
        SettingSwitch(
            title = "Captions",
            subtitle = "A figure or table caption is usually a sentence about the book.",
            checked = settings.content.speakCaptions,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakCaptions = on)) } }
        )
        SettingSwitch(
            title = "Illustration descriptions",
            subtitle = "Off by default: most alt text in an ebook is a filename or an ornament, " +
                "and hearing “image” every few pages is worse than silence.",
            checked = settings.content.speakImageAlt,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakImageAlt = on)) } }
        )
        SettingSwitch(
            title = "Footnote markers",
            subtitle = "Off by default: a superscript 12 becomes “twelve” in the middle of a clause.",
            checked = settings.content.speakFootnoteMarkers,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakFootnoteMarkers = on)) } }
        )
        SettingSwitch(
            title = "Code blocks",
            subtitle = "Off by default. Worth it for a book of short snippets.",
            checked = settings.content.speakCode,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakCode = on)) } }
        )
        SettingSwitch(
            title = "Tables",
            subtitle = "Read a row at a time, with the cells separated in the ear.",
            checked = settings.content.speakTables,
            onChange = { on -> vm.updateSpeech { it.copy(content = it.content.copy(speakTables = on)) } }
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** How each engine preference reads in a chip. */
private val EnginePreference.label: String
    get() = when (this) {
        EnginePreference.NEURAL_ELSE_SYSTEM -> "Best available"
        EnginePreference.NEURAL_ONLY -> "Downloaded only"
        EnginePreference.SYSTEM -> "Device engine"
    }
