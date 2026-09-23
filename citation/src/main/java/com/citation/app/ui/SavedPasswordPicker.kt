package com.citation.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.launch

/**
 * "Use a saved password": opens Android's own sign-in picker and hands back the one chosen.
 *
 * Whatever answers is the phone's credential provider — Secrets, when the household has set it as
 * one, which is the point: the catalogue login or the library card typed into Secrets once is
 * picked here rather than typed again. Citation never sees the list, only the login picked from it,
 * and saving it still goes through Citation's own store as before.
 *
 * Returns the action to run from a button. Backing out of the picker does nothing; finding nothing
 * saved says so.
 */
@Composable
fun rememberSavedPasswordPicker(onPicked: (id: String, password: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { CredentialManager.create(context) }
    return {
        scope.launch {
            runCatching {
                manager.getCredential(context, GetCredentialRequest(listOf(GetPasswordOption())))
            }.onSuccess { response ->
                (response.credential as? PasswordCredential)?.let { onPicked(it.id, it.password) }
            }.onFailure { failure ->
                when (failure) {
                    is GetCredentialCancellationException -> Unit
                    is NoCredentialException -> toast(context, "No saved passwords to choose from")
                    else -> toast(context, "Couldn't open saved passwords")
                }
            }
        }
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
