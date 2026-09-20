package com.utilities.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.utilities.app.data.LookStore
import com.utilities.app.keyboard.KeyboardScreen
import com.utilities.app.look.rememberLookFontFamily
import com.utilities.app.look.rememberPalette
import com.utilities.app.messages.MessagesRole
import com.utilities.app.messages.logic.ChatPalettes
import com.utilities.app.messages.ui.ChatLookScreen
import com.utilities.app.messages.ui.ConversationsScreen
import com.utilities.app.messages.ui.MessagesViewModel
import com.utilities.app.messages.ui.ThreadScreen
import com.utilities.app.shelf.PhoneFacts
import com.utilities.app.shelf.ShelfScreen
import com.utilities.app.shelf.TakeoverState
import com.utilities.app.shelf.TakeoverStatus
import com.utilities.app.shelf.Takeovers
import com.utilities.app.shelf.Utility
import com.utilities.app.ui.theme.UtilitiesTheme

/**
 * Utilities' entry point: the shelf, and the screen behind each thing on it.
 *
 * ## Why the state is re-read on every return
 *
 * Every one of this app's takeovers is granted *somewhere else* — Android's input-method settings,
 * the role picker, the permission dialog — and the household comes back here afterwards. There is
 * no callback for "the keyboard was switched on in system settings", so the shelf re-reads itself
 * on `ON_START`. That is also what makes the app correct after somebody turns a takeover **off**
 * from the system side, which nothing else would notice.
 *
 * ## Not exported
 *
 * Like every hosted app's activity. The sandbox opens it in-process, and the one thing that has to
 * be reachable from outside — an `sms:` link, which the messaging role requires an app to answer —
 * goes through `messages/SendToActivity`, which has no UI and hands straight back here.
 */
class MainActivity : ComponentActivity() {

    private var requested by mutableStateOf<String?>(null)
    private var requestedThread by mutableStateOf(-1L)
    private var requestedAddress by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UtilitiesApp.install(application)
        consume(intent)
        setContent {
            UtilitiesTheme {
                UtilitiesShell(
                    openRequest = requested,
                    threadRequest = requestedThread,
                    addressRequest = requestedAddress,
                    onRequestConsumed = {
                        requested = null
                        requestedThread = -1L
                        requestedAddress = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        requested = intent?.getStringExtra(EXTRA_OPEN)
        requestedThread = intent?.getLongExtra(EXTRA_THREAD, -1L) ?: -1L
        requestedAddress = intent?.getStringExtra(EXTRA_ADDRESS)
    }

    companion object {
        /** Which screen to land on. Read by the notification and by the `sms:` link. */
        const val EXTRA_OPEN = "com.utilities.app.OPEN"
        const val EXTRA_THREAD = "com.utilities.app.THREAD"
        const val EXTRA_ADDRESS = "com.utilities.app.ADDRESS"

        const val OPEN_MESSAGES = "messages"
        const val OPEN_KEYBOARD = "keyboard"
    }
}

/**
 * How many pictures may go in one message.
 *
 * Not a technical limit — the budget arithmetic would refuse long before this — but a practical
 * one: past about four, each one has been squeezed so hard that sending two messages would look
 * better, and the picker is the right place to say so.
 */
private const val MAX_ATTACHMENTS = 4

private const val ROUTE_SHELF = "shelf"
private const val ROUTE_KEYBOARD = "keyboard"
private const val ROUTE_MESSAGES = "messages"
private const val ROUTE_CHAT_LOOK = "messages-look"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UtilitiesShell(
    openRequest: String?,
    threadRequest: Long,
    addressRequest: String?,
    onRequestConsumed: () -> Unit
) {
    val context = LocalContext.current
    val messages: MessagesViewModel = viewModel(factory = MessagesViewModel.Factory(context))

    var route by rememberSaveable { mutableStateOf(ROUTE_SHELF) }
    var statuses by remember { mutableStateOf(PhoneFacts.all(context)) }
    var confirmDefault by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    fun resync() {
        statuses = PhoneFacts.all(context)
        if (MessagesRole.canRead(context)) messages.refresh()
    }

    // Everything this app switches on is switched on somewhere else. See the class note.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) resync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askForMessages = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resync() }

    val askForContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { resync() }

    val askForRole = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resync() }

    // A notification or an `sms:` link asked for a screen.
    LaunchedEffect(openRequest, threadRequest, addressRequest) {
        when (openRequest) {
            MainActivity.OPEN_MESSAGES -> {
                route = ROUTE_MESSAGES
                if (threadRequest >= 0) messages.openThread(threadRequest)
                if (addressRequest != null) messages.openWith(addressRequest)
            }

            MainActivity.OPEN_KEYBOARD -> route = ROUTE_KEYBOARD
            else -> Unit
        }
        if (openRequest != null) onRequestConsumed()
    }

    val status by messages.status.collectAsState()
    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            messages.clearStatus()
        }
    }

    val openThread by messages.open.collectAsState()

    // Back goes one step at a time: out of a thread, then out of the screen, then to the shelf.
    BackHandler(enabled = route != ROUTE_SHELF || openThread != null) {
        when {
            openThread != null -> messages.closeThread()
            route == ROUTE_CHAT_LOOK -> route = ROUTE_MESSAGES
            else -> route = ROUTE_SHELF
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            openThread != null -> openThread?.thread?.title.orEmpty()
                            route == ROUTE_KEYBOARD -> "Keyboard"
                            route == ROUTE_MESSAGES -> "Messages"
                            route == ROUTE_CHAT_LOOK -> "How threads look"
                            else -> "Utilities"
                        }
                    )
                },
                navigationIcon = {
                    if (route != ROUTE_SHELF || openThread != null) {
                        IconButton(onClick = {
                            when {
                                openThread != null -> messages.closeThread()
                                route == ROUTE_CHAT_LOOK -> route = ROUTE_MESSAGES
                                else -> route = ROUTE_SHELF
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (route == ROUTE_MESSAGES) {
                        IconButton(onClick = { route = ROUTE_CHAT_LOOK }) {
                            Icon(Icons.Filled.Palette, contentDescription = "How threads look")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            route == ROUTE_KEYBOARD -> KeyboardScreen(modifier)

            route == ROUTE_CHAT_LOOK -> ChatLookScreen(modifier)

            route == ROUTE_MESSAGES -> MessagesRoute(
                messages = messages,
                modifier = modifier,
                canRead = MessagesRole.canRead(context)
            )

            else -> ShelfScreen(
                statuses = statuses,
                onOpen = { utility ->
                    route = when (utility) {
                        Utility.KEYBOARD -> ROUTE_KEYBOARD
                        Utility.MESSAGES -> ROUTE_MESSAGES
                    }
                },
                onNextStep = { utility ->
                    when (utility) {
                        Utility.KEYBOARD -> stepKeyboard(context, statuses) { intent ->
                            context.startActivity(intent)
                        }

                        Utility.MESSAGES -> stepMessages(
                            context = context,
                            statuses = statuses,
                            askPermissions = { askForMessages.launch(MessagesRole.readingPermissions) },
                            askContacts = { askForContacts.launch(android.Manifest.permission.READ_CONTACTS) },
                            askRole = { confirmDefault = true }
                        )
                    }
                },
                modifier = modifier
            )
        }
    }

    // The one dialog in this app. Taking over somebody's messaging is the most consequential thing
    // it does, and the sentence is a tested constant rather than a string here — see
    // Takeovers.defaultAppNote.
    if (confirmDefault) {
        AlertDialog(
            onDismissRequest = { confirmDefault = false },
            title = { Text("Make Utilities your messaging app?") },
            text = { Text(Takeovers.defaultAppNote) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDefault = false
                    MessagesRole.requestDefault(context)?.let { askForRole.launch(it) }
                }) { Text("Go on") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDefault = false }) { Text("Not yet") }
            }
        )
    }
}

/** The conversation list, or the thread that is open on top of it. */
@Composable
private fun MessagesRoute(
    messages: MessagesViewModel,
    modifier: Modifier,
    canRead: Boolean
) {
    val context = LocalContext.current
    val looks = remember { LookStore.get(context) }
    val chat by looks.chat.collectAsState()
    val base = rememberPalette(chat.look)
    val palette = remember(chat, base) { ChatPalettes.resolve(chat, base.surface, base.text, base.accent) }
    val font = rememberLookFontFamily(chat.look)

    val threads by messages.threads.collectAsState()
    val open by messages.open.collectAsState()
    val staged by messages.staged.collectAsState()

    // The system photo picker rather than a storage permission. It hands back exactly the pictures
    // somebody chose, needs nothing granted, and is the only way to attach one here — this app
    // never asks to read the gallery.
    val pickPictures = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)
    ) { picked -> picked.forEach { messages.attach(it) } }

    val view = open
    if (view != null) {
        ThreadScreen(
            view = view,
            look = chat,
            palette = palette,
            onSend = { messages.send(it) },
            modifier = modifier,
            fontFamily = font,
            onDownload = { messages.download(it) },
            staged = staged,
            onAttach = {
                pickPictures.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onUnattach = { messages.unattach(it) }
        )
    } else {
        ConversationsScreen(
            threads = threads,
            look = chat,
            palette = palette,
            onOpen = { messages.openThread(it) },
            modifier = modifier,
            empty = if (canRead) "No conversations yet." else
                "Utilities cannot read your texts yet. Allow it on the shelf and they appear here."
        )
    }
}

/**
 * The keyboard's next step: the platform's own screens, because there are no others.
 *
 * An app may not switch itself on as a keyboard and may not select itself. It can open the settings
 * screen and it can raise the picker, and that is the whole of what is possible — which is the
 * operating system being right, and is why the shelf says "switch it on" rather than offering a
 * switch that would be a lie.
 */
private fun stepKeyboard(
    context: android.content.Context,
    statuses: List<TakeoverStatus>,
    start: (Intent) -> Unit
) {
    val enabled = statuses.firstOrNull { it.utility == Utility.KEYBOARD }?.state != TakeoverState.OFF
    if (enabled) PhoneFacts.showKeyboardPicker(context) else start(PhoneFacts.keyboardSettings())
}

/** The messaging takeover's next step, in the order the rungs go. */
private fun stepMessages(
    context: android.content.Context,
    statuses: List<TakeoverStatus>,
    askPermissions: () -> Unit,
    askContacts: () -> Unit,
    askRole: () -> Unit
) {
    // Nothing to step through for a takeover the shelf has no row for — a device with no radio.
    if (statuses.none { it.utility == Utility.MESSAGES && it.state != TakeoverState.UNAVAILABLE }) return
    when {
        !MessagesRole.canRead(context) || !MessagesRole.canSend(context) -> askPermissions()
        !MessagesRole.isDefault(context) -> askRole()
        !MessagesRole.canReadContacts(context) -> askContacts()
        else -> Unit
    }
}
