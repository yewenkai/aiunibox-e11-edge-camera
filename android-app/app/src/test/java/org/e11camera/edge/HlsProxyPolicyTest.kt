package org.e11camera.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsProxyPolicyTest {
    @Test
    fun `camera player and segments stay under the camera path`() {
        assertEquals("/cam", HlsProxyPolicy.upstreamPath("/media/cam"))
        assertEquals("/cam/", HlsProxyPolicy.upstreamPath("/media/cam/"))
        assertEquals(
            "/cam/index.m3u8",
            HlsProxyPolicy.upstreamPath("/media/cam/index.m3u8")
        )
        assertEquals(
            "http://127.0.0.1:8888/cam/index.m3u8?probe=1",
            HlsProxyPolicy.upstreamUrl("/media/cam/index.m3u8", "probe=1")
        )
    }

    @Test
    fun `unrelated or escaping paths are rejected`() {
        assertNull(HlsProxyPolicy.upstreamPath("/media/other"))
        assertNull(HlsProxyPolicy.upstreamPath("/media/camera"))
        assertNull(HlsProxyPolicy.upstreamPath("/media/cam/../other"))
        assertNull(HlsProxyPolicy.upstreamPath("/media/cam/%2e%2e/other"))
        assertNull(HlsProxyPolicy.upstreamPath("/media/cam\\other"))
    }

    @Test
    fun `mediamtx redirects are rewritten to the public same origin path`() {
        assertEquals("/media/cam/", HlsProxyPolicy.rewriteLocation("/cam/"))
        assertEquals(
            "/media/cam/",
            HlsProxyPolicy.rewriteLocation("http://127.0.0.1:8888/cam/")
        )
        assertEquals(
            "https://example.test/cam/",
            HlsProxyPolicy.rewriteLocation("https://example.test/cam/")
        )
    }
}
