package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesInstallSupportTest {

    @Test
    fun parsesProjectVersionFromRealCheckoutShape() {
        val pyproject = """
            [project]
            name = "hermes-agent"
            version = "0.0.0"
            description = "The self-improving AI agent"
            requires-python = ">=3.11,<3.15"

            [tool.uv]
            override-dependencies = ["Pillow==13.0.0"]

            [tool.some-tool]
            version = "9.9.9"
        """.trimIndent()
        assertEquals("0.0.0", parseHermesProjectVersion(pyproject))
    }

    @Test
    fun parsesSingleQuotedAndCommentedVersion() {
        val pyproject = """
            [project]
            name = "hermes-agent"
            version = '1.2.3' # pinned by the release script
        """.trimIndent()
        assertEquals("1.2.3", parseHermesProjectVersion(pyproject))
    }

    @Test
    fun ignoresVersionsOutsideTheProjectSection() {
        val pyproject = """
            [build-system]
            version = "4.5.6"

            [project]
            name = "hermes-agent"
        """.trimIndent()
        assertNull(parseHermesProjectVersion(pyproject))
    }

    @Test
    fun returnsNullWithoutProjectSection() {
        assertNull(parseHermesProjectVersion(""))
        assertNull(parseHermesProjectVersion("version = \"1.0.0\"\n"))
    }

    @Test
    fun acceptsPlainReleaseVersions() {
        assertTrue(isHermesReleaseVersion("0.19.0"))
        assertTrue(isHermesReleaseVersion("0.20.0rc1"))
        assertTrue(isHermesReleaseVersion("1.0.0+src"))
    }

    @Test
    fun rejectsNonReleaseVersions() {
        assertFalse(isHermesReleaseVersion(""))
        assertFalse(isHermesReleaseVersion("0.19"))
        assertFalse(isHermesReleaseVersion("latest"))
        assertFalse(isHermesReleaseVersion("0.19.0\"; rm -rf /; echo \""))
        assertFalse(isHermesReleaseVersion("0.19.0 && reboot"))
    }
}
