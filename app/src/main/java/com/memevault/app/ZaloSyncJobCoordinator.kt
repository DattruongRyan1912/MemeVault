package com.memevault.app

import java.util.concurrent.Executors

/**
 * Process-scoped owner for the experimental Zalo sync job.
 *
 * An Activity can be recreated while a SAF operation is still running. Keeping the executor and
 * the manager here prevents a second Activity instance from starting a concurrent import/export,
 * while observers let the new UI render the running/completed state immediately.
 */
object ZaloSyncJobCoordinator {
    data class Outcome(
        val summary: ZaloSyncManager.SyncSummary? = null,
        val errorMessage: String? = null,
    )

    data class Snapshot(
        val jobId: Long? = null,
        val running: Boolean,
        val outcome: Outcome? = null,
    )

    private data class RunningJob(
        val id: Long,
        val manager: ZaloSyncManager,
        var ownerAttached: Boolean = true,
    )

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemeVault-zalo-sync").apply { isDaemon = true }
    }
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()
    private val completedManagers = LinkedHashSet<ZaloSyncManager>()
    private var nextJobId = 0L
    private var runningJob: RunningJob? = null
    private var lastJobId: Long? = null
    private var lastOutcome: Outcome? = null

    fun snapshot(): Snapshot = synchronized(lock) { snapshotLocked() }

    /** Registers a listener and immediately emits the current process-level state. */
    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        val current = synchronized(lock) {
            listeners += listener
            snapshotLocked()
        }
        runCatching { listener(current) }
        return {
            synchronized(lock) { listeners -= listener }
        }
    }

    /** Starts at most one sync per process. Returns null when another Activity owns the job. */
    fun start(manager: ZaloSyncManager): Long? {
        val job = synchronized(lock) {
            if (runningJob != null) {
                null
            } else {
                // The Activity that started the previous job may reuse the same manager for a
                // second sync. Keep it open until that Activity is destroyed.
                completedManagers -= manager
                RunningJob(++nextJobId, manager).also {
                    runningJob = it
                    lastJobId = it.id
                    lastOutcome = null
                }
            }
        } ?: return null

        notifyListeners(snapshot())
        executor.execute {
            val outcome = runCatching { job.manager.sync() }
                .fold(
                    onSuccess = { Outcome(summary = it) },
                    onFailure = { error ->
                        Outcome(
                            errorMessage = error.message?.takeIf(String::isNotBlank)
                                ?: "Không thể đồng bộ Zalo",
                        )
                    },
                )
            var managerToClose: ZaloSyncManager? = null
            synchronized(lock) {
                if (runningJob?.id == job.id) {
                    runningJob = null
                    lastJobId = job.id
                    lastOutcome = outcome
                    if (job.ownerAttached) {
                        completedManagers += job.manager
                    } else {
                        managerToClose = job.manager
                    }
                }
            }
            // A destroyed Activity calls releaseManager() while the worker is still running. In
            // that case no UI can reuse the manager after completion, so close it here.
            runCatching { managerToClose?.close() }
            notifyListeners(snapshot())
        }
        return job.id
    }

    /**
     * Detaches an Activity from its manager during onDestroy. The worker keeps using a detached
     * manager until it finishes, then closes it; an already-completed manager is closed at once.
     */
    fun releaseManager(manager: ZaloSyncManager) {
        var managerToClose: ZaloSyncManager? = null
        synchronized(lock) {
            val job = runningJob
            if (job?.manager === manager) {
                job.ownerAttached = false
            } else if (completedManagers.remove(manager)) {
                managerToClose = manager
            }
        }
        closeManagerAsync(managerToClose)
    }

    private fun snapshotLocked(): Snapshot = runningJob?.let { job ->
        Snapshot(jobId = job.id, running = true)
    } ?: Snapshot(
        jobId = lastJobId,
        running = false,
        outcome = lastOutcome,
    )

    private fun notifyListeners(snapshot: Snapshot) {
        val currentListeners = synchronized(lock) { listeners.toList() }
        currentListeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun closeManagerAsync(manager: ZaloSyncManager?) {
        if (manager == null) return
        runCatching {
            executor.execute { runCatching { manager.close() } }
        }
    }
}
