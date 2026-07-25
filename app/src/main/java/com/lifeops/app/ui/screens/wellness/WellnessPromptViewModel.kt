package com.lifeops.app.ui.screens.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.repository.WellnessRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ScreenTimeEstimator
import com.lifeops.app.util.SleepInferenceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WellnessPromptKind { NONE, CHECKIN, SLEEP }

data class WellnessPromptState(
    val kind: WellnessPromptKind = WellnessPromptKind.NONE,
    val sleepEstimateMinutes: Int? = null,
    val hasUsageAccess: Boolean = true,
    /** Present when last night was reconstructed from tracked events (the accurate path). */
    val reconstruction: SleepInferenceService.SleepReconstruction? = null
)

/**
 * Decides which wellness pop-up (if any) is due when the app is opened / resumed, and records the
 * answer. Sleep takes priority — the first open at/after 5am with no sleep report yet shows the
 * morning prompt; otherwise, if a daytime slot has come due and hasn't been answered, the check-in
 * prompt shows. One prompt per open: dismiss or save clears it, and the next open re-evaluates.
 */
class WellnessPromptViewModel(
    private val repo: WellnessRepository
) : ViewModel() {

    private val wellnessService = com.lifeops.app.connection.service.WellnessService(repo)

    private val _state = MutableStateFlow(WellnessPromptState())
    val state: StateFlow<WellnessPromptState> = _state.asStateFlow()

    /** Re-check what's due. Safe to call on every ON_RESUME; a shown prompt is not re-shown. */
    fun evaluate() {
        // Don't stomp a prompt the user is currently looking at.
        if (_state.value.kind != WellnessPromptKind.NONE) return
        if (!repo.remindersEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (DateUtil.localHour(now) >= repo.sleepPromptFromHour && !repo.hasSleepForToday(now)) {
                // Accurate path first: reconstruct from tracked screen/charging events. Only fall back
                // to the coarse screen-time estimate when there isn't enough captured to be confident.
                val reconstruction = repo.reconstructLastNight(now)
                if (reconstruction != null) {
                    _state.value = WellnessPromptState(
                        kind = WellnessPromptKind.SLEEP,
                        sleepEstimateMinutes = reconstruction.totalSleepMinutes,
                        hasUsageAccess = true,
                        reconstruction = reconstruction
                    )
                    return@launch
                }
                val est = repo.estimateSleep(now)
                val mins = est.lastUseMillis?.let { ScreenTimeEstimator.sleepMinutes(it, now) }
                _state.value = WellnessPromptState(WellnessPromptKind.SLEEP, mins, est.hasAccess)
                return@launch
            }
            val passed = repo.passedSlotCount(now)
            val answered = repo.checkinCountForToday(now)
            _state.value = if (passed > answered) {
                WellnessPromptState(WellnessPromptKind.CHECKIN)
            } else {
                WellnessPromptState(WellnessPromptKind.NONE)
            }
        }
    }

    fun submitCheckin(energy: Int, sensory: Int, why: String) {
        viewModelScope.launch {
            wellnessService.checkin(energy, sensory, why)
            _state.value = WellnessPromptState(WellnessPromptKind.NONE)
        }
    }

    fun submitSleep(energy: Int, tired: Int, sleepMinutes: Int?, why: String) {
        // Keep the reconstruction with the report only when the user didn't override the total — an
        // edited duration invalidates the derived bedtime/wake/interruptions, so drop them.
        val reconstruction = _state.value.reconstruction?.takeIf { it.totalSleepMinutes == sleepMinutes }
        viewModelScope.launch {
            wellnessService.sleep(energy, tired, sleepMinutes, why, reconstruction)
            _state.value = WellnessPromptState(WellnessPromptKind.NONE)
        }
    }

    /** Dismiss without recording; won't re-prompt until the next app open re-evaluates. */
    fun dismiss() {
        _state.value = WellnessPromptState(WellnessPromptKind.NONE)
    }
}

class WellnessPromptViewModelFactory(
    private val repo: WellnessRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WellnessPromptViewModel(repo) as T
}
