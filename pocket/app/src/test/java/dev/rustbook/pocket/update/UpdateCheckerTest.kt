package dev.rustbook.pocket.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `detects newer releases`() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("pocket-v0.3.0", "0.2.9"))
    }

    @Test
    fun `ignores equal or older releases`() {
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.0.9", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("pocket-v0.1", "0.1.0"))
    }

    @Test
    fun `tolerates pre-release suffixes`() {
        assertTrue(UpdateChecker.isNewer("v0.2.0-rc.1", "0.1.5"))
        assertFalse(UpdateChecker.isNewer("v0.1.0-beta", "0.1.0"))
    }
}
