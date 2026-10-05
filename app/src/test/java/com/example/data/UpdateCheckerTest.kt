package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions that decide whether a user is offered the right APK: is the tag
 * newer than what they have, and which file do they get. Both are pure functions so
 * they can be pinned down without a device or a network.
 */
class UpdateCheckerTest {

    @Test
    fun `tag becomes the version code the release workflow writes`() {
        assertEquals(1_002_003, versionCodeOf("v1.2.3"))
        assertEquals(1_002_003, versionCodeOf("1.2.3"))
        assertEquals(1, versionCodeOf("v0.0.1"))
        assertEquals(2_000_005, versionCodeOf("v2.0.5"))
        assertEquals(1_002_300, versionCodeOf("v1.2.300"))
        // The two tags that used to collide under the old mapping.
        assertNotEquals(versionCodeOf("v1.2.300"), versionCodeOf("v1.5.0"))
    }

    @Test
    fun `anything not shaped like a version answers null`() {
        assertNull(versionCodeOf("v1.2"))
        assertNull(versionCodeOf("1.0"))
        assertNull(versionCodeOf(""))
        assertNull(versionCodeOf("v1.2.beta"))
        assertNull(versionCodeOf("v-1.2.3"))
        assertNull(versionCodeOf("v1000.0.0"))
    }

    @Test
    fun `the device abi wins over the universal apk`() {
        val assets = listOf(
            ReleaseAsset("IsfahanBus-armeabi-v7a-release.apk", "url-v7a"),
            ReleaseAsset("IsfahanBus-universal-release.apk", "url-universal"),
            ReleaseAsset("IsfahanBus-arm64-v8a-release.apk", "url-arm64")
        )
        assertEquals("url-arm64", chooseApkUrl(assets, "arm64-v8a"))
        assertEquals("url-v7a", chooseApkUrl(assets, "armeabi-v7a"))
    }

    @Test
    fun `unknown abi falls back to the universal apk`() {
        val assets = listOf(
            ReleaseAsset("IsfahanBus-universal-release.apk", "url-universal"),
            ReleaseAsset("IsfahanBus-x86-release.apk", "url-x86")
        )
        assertEquals("url-universal", chooseApkUrl(assets, "riscv64"))
        assertEquals("url-universal", chooseApkUrl(assets, null))
    }

    @Test
    fun `no usable asset means no direct link`() {
        assertNull(chooseApkUrl(listOf(ReleaseAsset("notes.txt", "url")), "arm64-v8a"))
        assertNull(chooseApkUrl(emptyList(), "arm64-v8a"))
    }
}
