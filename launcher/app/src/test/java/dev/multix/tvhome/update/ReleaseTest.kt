package dev.multix.tvhome.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseTest {

    private fun v(s: String) = Version.parse(s)!!

    @Test
    fun versionsCompareLikeReleases() {
        assertTrue(v("v1.0.1") > v("1.0.0"))
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0") > v("1.99.99"))
        assertEquals(0, v("v1.2").compareTo(v("1.2.0")))
        assertTrue(v("1.2.0") > v("1.2.0-beta.2"))
        assertTrue(v("1.2.0-rc.1") > v("1.2.0-beta.2"))
        assertEquals("1.4.2", v("V1.4.2+build.7").toString())
        for (bad in listOf("", "latest", "v", "1..2", "release-1", "v1.2.3.4.5")) assertNull(bad, Version.parse(bad))
    }

    /** The real response of a published release (github.com/MultiX0/ip-nas), assets with digests. */
    @Test
    fun readsARealGitHubRelease() {
        val json = javaClass.getResource("/github_release.json")!!.readText()
        val r = Releases.parse(json, "Sukoon")
        assertEquals("v1.0.0", r.tag)
        assertEquals("1.0.0", r.version.toString())
        assertEquals("nur-android.apk", r.apkName)
        assertTrue(r.apkUrl.startsWith("https://github.com/MultiX0/ip-nas/releases/download/"))
        assertEquals(101974841L, r.apkSize)
        assertEquals(64, r.sha256!!.length)
    }

    private fun release(tag: String, assets: String, extra: String = "") =
        """{"tag_name":"$tag","html_url":"https://github.com/MultiX0/sukoon-tv/releases/tag/$tag","body":"## What's new\r\n- **Faster** lyrics\r\n- See [the notes](https://x.y)\r\n<!-- hidden -->","assets":[$assets]$extra}"""

    private fun asset(name: String, state: String = "uploaded", digest: String? = null) =
        """{"name":"$name","size":1234,"state":"$state","browser_download_url":"https://github.com/MultiX0/sukoon-tv/releases/download/v1/$name"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    @Test
    fun prefersTheLauncherApkAndKeepsTheDigest() {
        val sha = "a".repeat(64)
        val r = Releases.parse(release("v1.3.0", asset("other.apk") + "," + asset("sukoon-launcher-1.3.0.apk", digest = "sha256:$sha") + "," + asset("notes.txt")), "Sukoon")
        assertEquals("sukoon-launcher-1.3.0.apk", r.apkName)
        assertEquals(sha, r.sha256)
        assertEquals("What's new\n• Faster lyrics\n• See the notes", r.notes)
    }

    @Test
    fun refusesWhatCannotBeInstalled() {
        fun reason(json: String) = try {
            Releases.parse(json, "Sukoon")
            fail("parsed: $json")
            null
        } catch (e: ReleaseError) {
            e.reason
        }
        assertEquals(Updater.Reason.NO_APK, reason(release("v1.1.0", asset("source.zip"))))
        assertEquals(Updater.Reason.NO_APK, reason(release("v1.1.0", asset("sukoon.apk", state = "starter"))))
        assertEquals(Updater.Reason.NO_RELEASES, reason(release("v1.1.0", asset("sukoon.apk"), ",\"draft\":true")))
        assertEquals(Updater.Reason.NO_RELEASES, reason(release("v1.1.0", asset("sukoon.apk"), ",\"prerelease\":true")))
        assertEquals(Updater.Reason.BAD_RESPONSE, reason(release("nightly", asset("sukoon.apk"))))
        assertEquals(Updater.Reason.BAD_RESPONSE, reason("<html>rate limited</html>"))
    }

    @Test
    fun notesStayShort() {
        val long = (1..20).joinToString("\n") { "- item $it" }
        assertEquals(8, Releases.cleanNotes(long).lines().size)
        assertEquals("", Releases.cleanNotes(null))
    }
}
