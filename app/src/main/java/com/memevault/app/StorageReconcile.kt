package com.memevault.app

import java.io.File

/** How one absolute media path stored in Room relates to the current storage root. */
internal sealed class StoredPathCheck {
    /** Inside the current root and the file exists. */
    object Present : StoredPathCheck()

    /** Written under an old app-data prefix; the same file exists under the current root. */
    data class Rebased(val path: String) : StoredPathCheck()

    /** Inside the current root but the file is gone. The only state cleanup may remove. */
    object Missing : StoredPathCheck()

    /** Foreign path with no matching file, or the check itself failed. Never removed. */
    object Unresolved : StoredPathCheck()
}

/** What reconcile may do with the stored original paths: [removable] is empty when braked. */
internal data class ReconcilePlan(
    val rebased: Map<String, String>,
    val removable: List<String>,
    val sweepAllowed: Boolean,
)

/**
 * Reconcile policy for the absolute paths stored in Room (audit H1). The app-data directory can
 * move (restore into another user or work profile, device transfer), so a path outside the
 * current root is matched by its `<segment>/<name>` suffix instead of being counted as lost, and
 * destructive cleanup is skipped whenever the snapshot looks wrong.
 */
internal object StorageReconcilePolicy {
    /** Auto-cleanup never removes more than this many rows or files in one pass. */
    const val MAX_AUTO_REMOVALS = 10

    fun plan(pathsById: Map<String, String>, root: File, segment: String): ReconcilePlan {
        val checks = pathsById.mapValues { (_, path) -> check(path, root, segment) }
        val rebased = checks.mapNotNull { (id, check) ->
            (check as? StoredPathCheck.Rebased)?.let { id to it.path }
        }.toMap()
        val missing = checks.filterValues { it == StoredPathCheck.Missing }.keys.toList()
        val unresolved = checks.values.count { it == StoredPathCheck.Unresolved }
        val sane = unresolved == 0 && allowsRemoval(missing.size, pathsById.size)
        return ReconcilePlan(rebased, if (sane) missing else emptyList(), sweepAllowed = sane)
    }

    fun check(storedPath: String, root: File, segment: String): StoredPathCheck = runCatching {
        val rootPath = root.canonicalPath
        val current = File(storedPath).canonicalPath
        if (isUnder(current, rootPath)) {
            return@runCatching if (File(current).isFile) StoredPathCheck.Present else StoredPathCheck.Missing
        }
        val candidate = relativeSuffix(storedPath, segment)?.let { File(root, it) }
            ?: return@runCatching StoredPathCheck.Unresolved
        if (isUnder(candidate.canonicalPath, rootPath) && candidate.isFile) {
            StoredPathCheck.Rebased(candidate.absolutePath)
        } else {
            StoredPathCheck.Unresolved
        }
    }.getOrDefault(StoredPathCheck.Unresolved)

    /** The part of [storedPath] after the last `/<segment>/`, e.g. `<id>.gif` for originals. */
    fun relativeSuffix(storedPath: String, segment: String): String? {
        val marker = "/$segment/"
        val index = storedPath.lastIndexOf(marker)
        if (index < 0) return null
        return storedPath.substring(index + marker.length).takeIf { it.isNotEmpty() }
    }

    /**
     * Cleanup is allowed only for a small minority. A drifted, reset or half-restored database
     * shows up as many removals at once, and keeping a few dead rows or stray files is always
     * cheaper than losing the library.
     */
    fun allowsRemoval(removals: Int, total: Int): Boolean =
        removals == 0 || (removals <= MAX_AUTO_REMOVALS && removals * 2 < total)

    private fun isUnder(path: String, rootPath: String): Boolean = path.startsWith(rootPath + File.separator)
}
