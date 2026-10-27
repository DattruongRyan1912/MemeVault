package com.memevault.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEscapingTest {
    @Test
    fun escapesSqlLikeWildcardsAndBackslashes() {
        assertEquals("""100\%\_\\\_""", SqlLikeEscaper.escape("100%_\\_"))
    }
}
