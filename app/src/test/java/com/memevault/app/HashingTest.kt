package com.memevault.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    @Test
    fun computesStableSha256ForImportedBytes() {
        val file = File.createTempFile("memevault-hash", ".bin")
        try {
            file.writeText("MemeVault", Charsets.UTF_8)
            assertEquals(
                "2734892a63863e4988d21ffa1f7d7fe0e15844b2869241a3b06fae116bed009a",
                Hashing.sha256(file),
            )
        } finally {
            file.delete()
        }
    }
}
