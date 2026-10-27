package com.memevault.app

import android.content.Context
import android.net.Uri
import java.util.concurrent.Executors

/**
 * Process-scoped owner for imports received from Android's Sharesheet.
 *
 * Activity recreation must never cancel a copy or close Room under a running import. URI grants
 * are still temporary platform grants, so the visible receiver Activity stays in the task until
 * this bounded job completes; process death remains a visible retry instead of a false success.
 */
object ShareImportJobCoordinator {
    data class Outcome(
        val saved: Int = 0,
        val duplicates: Int = 0,
        val errors: Int = 0,
        val errorMessage: String? = null,
    )

    data class Snapshot(
        val jobId: Long,
        val running: Boolean,
        val outcome: Outcome? = null,
    )

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MemeVault-share-import").apply { isDaemon = true }
    }
    private val snapshots = LinkedHashMap<Long, Snapshot>()
    private val listeners = mutableMapOf<Long, LinkedHashSet<(Snapshot) -> Unit>>()
    private var nextJobId = 0L

    fun start(context: Context, uris: List<Uri>): Long {
        require(uris.isNotEmpty()) { "Không có media để nhập" }
        val jobId = synchronized(lock) {
            (++nextJobId).also { id -> snapshots[id] = Snapshot(id, running = true) }
        }
        val appContext = context.applicationContext
        val immutableUris = uris.toList()
        executor.execute {
            var repository: AssetRepository? = null
            val outcome = try {
                repository = AssetRepository(appContext)
                val results = repository.importUris(immutableUris)
                Outcome(
                    saved = results.count { it.asset != null && !it.duplicate },
                    duplicates = results.count { it.duplicate },
                    errors = results.count { it.error != null },
                )
            } catch (error: Throwable) {
                Outcome(
                    errors = immutableUris.size,
                    errorMessage = error.message?.takeIf(String::isNotBlank)
                        ?: "Không thể nhập media được chia sẻ",
                )
            } finally {
                runCatching { repository?.close() }
            }
            val completed = Snapshot(jobId, running = false, outcome = outcome)
            synchronized(lock) {
                snapshots[jobId] = completed
                pruneLocked()
            }
            notifyListeners(completed)
        }
        notifyListeners(Snapshot(jobId, running = true))
        return jobId
    }

    fun snapshot(jobId: Long): Snapshot? = synchronized(lock) { snapshots[jobId] }

    fun observe(jobId: Long, listener: (Snapshot) -> Unit): () -> Unit {
        val current = synchronized(lock) {
            listeners.getOrPut(jobId) { LinkedHashSet() } += listener
            snapshots[jobId]
        }
        current?.let { runCatching { listener(it) } }
        return {
            synchronized(lock) {
                listeners[jobId]?.let { callbacks ->
                    callbacks -= listener
                    if (callbacks.isEmpty()) listeners.remove(jobId)
                }
            }
        }
    }

    private fun notifyListeners(snapshot: Snapshot) {
        val current = synchronized(lock) { listeners[snapshot.jobId].orEmpty().toList() }
        current.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun pruneLocked() {
        if (snapshots.size <= MAX_SNAPSHOTS) return
        val removable = snapshots.values
            .filterNot { it.running }
            .filter { listeners[it.jobId].isNullOrEmpty() }
            .map { it.jobId }
        removable.take(snapshots.size - MAX_SNAPSHOTS).forEach(snapshots::remove)
    }

    private const val MAX_SNAPSHOTS = 8
}
