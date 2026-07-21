package com.lifeops.app.ui.screens.wellness

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeops.app.data.repository.WellnessRepository

/**
 * App-root host for the wellness pop-ups. It re-evaluates what's due on every ON_RESUME (i.e. each
 * time the app is opened or foregrounded) and shows at most one dialog — the morning sleep report
 * or a daytime check-in. Rendered once, above the nav content, so a prompt can appear on any screen.
 */
@Composable
fun WellnessPromptHost(repo: WellnessRepository, enabled: Boolean = true) {
    val vm: WellnessPromptViewModel = viewModel(factory = WellnessPromptViewModelFactory(repo))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, enabled) {
        // Gated on [enabled] so a wellness prompt never stacks over the first-launch welcome dialog.
        val observer = LifecycleEventObserver { _, event ->
            if (enabled && event == Lifecycle.Event.ON_RESUME) vm.evaluate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (state.kind) {
        WellnessPromptKind.CHECKIN -> CheckInDialog(
            onSubmit = { e, s, w -> vm.submitCheckin(e, s, w) },
            onDismiss = { vm.dismiss() }
        )
        WellnessPromptKind.SLEEP -> SleepCheckInDialog(
            estimatedMinutes = state.sleepEstimateMinutes,
            hasUsageAccess = state.hasUsageAccess,
            onGrantAccess = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onSubmit = { e, t, m, w -> vm.submitSleep(e, t, m, w) },
            onDismiss = { vm.dismiss() }
        )
        WellnessPromptKind.NONE -> Unit
    }
}
