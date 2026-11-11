package io.buildthisplease.compose

import io.buildthisplease.core.BuildThisPleaseClientProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One board's unread reads, called on the UI thread and owned by its composition scope. */
internal class UnreadCountController(
    private val client: BuildThisPleaseClientProtocol,
    private val scope: CoroutineScope,
) {
    private val mutableCount = MutableStateFlow(0)
    val count = mutableCount.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null

    fun refresh() {
        invalidate()
        val requestGeneration = generation
        refreshJob = scope.launch {
            try {
                val unread = client.notifications().unreadCount
                ensureActive()
                if (generation == requestGeneration) mutableCount.value = unread
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Keep the last known badge when a refresh fails.
            }
        }
    }

    fun invalidate() {
        generation++
        refreshJob?.cancel()
        refreshJob = null
    }
}
