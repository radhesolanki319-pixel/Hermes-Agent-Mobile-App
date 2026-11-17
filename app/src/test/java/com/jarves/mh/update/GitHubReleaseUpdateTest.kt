package com.jarves.mh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the GitHub-Releases update channel: release tags are
 * `v<versionCode>`, and an update exists only when the release is strictly
 * newer than the installed build.
 */
class GitHubReleaseUpdateTest {

    @Test
    fun parsesVersionCodeFromReleaseTags() {
        assertEquals(12L, GitHubReleaseUpdate.parseVersionCode("v12"))
        assertEquals(12L, GitHubReleaseUpdate.parseVersionCode("12"))
        assertEquals(103L, GitHubReleaseUpdate.parseVersionCode("v103"))
        assertEquals(12L, GitHubReleaseUpdate.parseVersionCode(" v12 "))
    }

    @Test
    fun rejectsTagsThatAreNotVersionCodes() {
        assertNull(GitHubReleaseUpdate.parseVersionCode(null))
        assertNull(GitHubReleaseUpdate.parseVersionCode(""))
        assertNull(GitHubReleaseUpdate.parseVersionCode("v"))
        assertNull(GitHubReleaseUpdate.parseVersionCode("release-12"))
        // Tags carry the versionCode, never the versionName.
        assertNull(GitHubReleaseUpdate.parseVersionCode("v1.0.11"))
    }

    @Test
    fun updateRequiresStrictlyNewerRelease() {
        assertTrue(GitHubReleaseUpdate.isUpdateAvailable(installedVersionCode = 11, remoteVersionCode = 12))
        assertFalse(GitHubReleaseUpdate.isUpdateAvailable(installedVersionCode = 12, remoteVersionCode = 12))
        assertFalse(GitHubReleaseUpdate.isUpdateAvailable(installedVersionCode = 13, remoteVersionCode = 12))
    }
}
