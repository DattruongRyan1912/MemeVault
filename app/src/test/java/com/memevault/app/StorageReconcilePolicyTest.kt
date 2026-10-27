package com.memevault.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageReconcilePolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val segment = "media/originals"
    private lateinit var root: File

    /** Where the rows were written before the app-data directory moved; never exists. */
    private val oldRoot = "/data/user/10/com.memevault.app/files/media/originals"

    @Before
    fun setUp() {
        root = File(temp.root.canonicalFile, "files/media/originals").apply { mkdirs() }
    }

    private fun file(name: String): File = File(root, name).apply { writeText(name) }

    @Test
    fun presentAndMissingAreJudgedInsideTheCurrentRoot() {
        val kept = file("a.png")
        assertEquals(StoredPathCheck.Present, StorageReconcilePolicy.check(kept.absolutePath, root, segment))
        assertEquals(
            StoredPathCheck.Missing,
            StorageReconcilePolicy.check(File(root, "gone.png").absolutePath, root, segment),
        )
    }

    @Test
    fun wholeLibraryDriftIsRebasedAndNothingIsRemoved() {
        val paths = (1..30).associate { index ->
            val name = "id$index.gif"
            file(name)
            "id$index" to "$oldRoot/$name"
        }
        val plan = StorageReconcilePolicy.plan(paths, root, segment)
        assertEquals(30, plan.rebased.size)
        assertEquals(File(root, "id7.gif").absolutePath, plan.rebased["id7"])
        assertTrue(plan.removable.isEmpty())
        assertTrue(plan.sweepAllowed)
    }

    @Test
    fun foreignPathWithoutAMatchingFileIsUnresolvedAndBlocksCleanup() {
        val paths = mapOf(
            "lost" to "$oldRoot/lost.png",
            "missing" to File(root, "missing.png").absolutePath,
        ) + (1..10).associate { "ok$it" to file("ok$it.png").absolutePath }
        val plan = StorageReconcilePolicy.plan(paths, root, segment)
        assertEquals(
            StoredPathCheck.Unresolved,
            StorageReconcilePolicy.check("$oldRoot/lost.png", root, segment),
        )
        assertTrue(plan.removable.isEmpty())
        assertFalse(plan.sweepAllowed)
    }

    @Test
    fun aFewMissingOriginalsAreStillCleanedUp() {
        val paths = (1..10).associate { "ok$it" to file("ok$it.png").absolutePath } +
            ("gone" to File(root, "gone.png").absolutePath)
        val plan = StorageReconcilePolicy.plan(paths, root, segment)
        assertEquals(listOf("gone"), plan.removable)
        assertTrue(plan.sweepAllowed)
    }

    @Test
    fun brakeKeepsRowsWhenMostOfTheLibraryLooksMissing() {
        root.deleteRecursively()
        root.mkdirs()
        val paths = (1..40).associate { "id$it" to File(root, "id$it.png").absolutePath }
        val plan = StorageReconcilePolicy.plan(paths, root, segment)
        assertTrue(plan.removable.isEmpty())
        assertFalse(plan.sweepAllowed)
    }

    @Test
    fun brakeCapsAbsoluteRemovalsEvenInLargeLibraries() {
        val paths = (1..100).associate { "ok$it" to file("ok$it.png").absolutePath } +
            (1..11).associate { "gone$it" to File(root, "gone$it.png").absolutePath }
        val plan = StorageReconcilePolicy.plan(paths, root, segment)
        assertTrue(plan.removable.isEmpty())
        assertFalse(plan.sweepAllowed)
    }

    @Test
    fun suffixCannotEscapeTheRoot() {
        File(root.parentFile, "outside.png").writeText("x")
        assertEquals(
            StoredPathCheck.Unresolved,
            StorageReconcilePolicy.check("$oldRoot/../outside.png", root, segment),
        )
    }

    @Test
    fun relativeSuffixUsesTheLastSegment() {
        assertEquals(
            "a.png",
            StorageReconcilePolicy.relativeSuffix("/x/media/originals/y/files/media/originals/a.png", segment),
        )
        assertNull(StorageReconcilePolicy.relativeSuffix("/somewhere/else/a.png", segment))
        assertNull(StorageReconcilePolicy.relativeSuffix("$oldRoot/", segment))
    }

    @Test
    fun allowsRemovalOnlyForASmallMinority() {
        assertTrue(StorageReconcilePolicy.allowsRemoval(0, 0))
        assertFalse(StorageReconcilePolicy.allowsRemoval(1, 1))
        assertFalse(StorageReconcilePolicy.allowsRemoval(1, 2))
        assertTrue(StorageReconcilePolicy.allowsRemoval(1, 3))
        assertTrue(StorageReconcilePolicy.allowsRemoval(10, 100))
        assertFalse(StorageReconcilePolicy.allowsRemoval(11, 100))
    }
}
