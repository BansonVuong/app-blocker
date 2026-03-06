package com.appblocker

class LocalSessionTracker(
    private val storage: Storage
) {
    data class State(
        val sessionStartTimeMs: Long,
        val initialRemainingSeconds: Int,
        val currentWindowEndMs: Long
    ) {
        companion object {
            val EMPTY = State(
                sessionStartTimeMs = 0L,
                initialRemainingSeconds = 0,
                currentWindowEndMs = 0L
            )
        }
    }

    fun start(packageName: String, blockSet: BlockSet, nowMs: Long): State {
        if (AppTargets.isVirtualPackage(packageName)) {
            storage.startVirtualSession(packageName, nowMs)
        }
        return State(
            sessionStartTimeMs = nowMs,
            initialRemainingSeconds = storage.getRemainingSeconds(blockSet),
            currentWindowEndMs = storage.getWindowEndMillis(blockSet, nowMs)
        )
    }

    fun updateForWindowBoundary(
        packageName: String,
        blockSet: BlockSet,
        nowMs: Long,
        state: State
    ): State {
        if (AppTargets.isVirtualPackage(packageName)) {
            storage.updateVirtualSessionHeartbeat(packageName, nowMs)
        }

        if (state.currentWindowEndMs > 0 && nowMs >= state.currentWindowEndMs) {
            return State(
                sessionStartTimeMs = nowMs,
                initialRemainingSeconds = storage.getRemainingSeconds(blockSet),
                currentWindowEndMs = storage.getWindowEndMillis(blockSet, nowMs)
            )
        }

        return state
    }

    fun localRemainingSeconds(state: State, nowMs: Long): Int {
        val elapsedSeconds = ((nowMs - state.sessionStartTimeMs) / 1000).toInt()
        return maxOf(0, state.initialRemainingSeconds - elapsedSeconds)
    }

    fun stop(trackedPackage: String?) {
        val packageName = trackedPackage ?: return
        if (AppTargets.isVirtualPackage(packageName)) {
            storage.endVirtualSession(packageName)
        }
    }
}
