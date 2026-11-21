package com.appgrowstack.campaign

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CampaignSdkTest {
    @Test
    fun versionMatchesGradleProperties() {
        val properties = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle.properties") }
            .first { it.isFile && it.readText().contains(Regex("(?m)^version=")) }
        val version = Regex("(?m)^version=(.+)$").find(properties.readText())!!.groupValues[1].trim()
        assertEquals(version, CampaignSdk.VERSION)
        assertEquals("kotlin-app-campaign", CampaignSdk.NAME)
    }

    @Test
    fun bundledSetIsGivenOnceThenNotModified() = runTest {
        val json = """{"version":1,"campaigns":[]}"""
        var reads = 0
        val bundled = BundledCampaignSetFetcher { reads += 1; json }
        val etag = bundled.etag
        assertEquals(BundledCampaignSetFetcher { json }.etag, etag)
        assertNotEquals(BundledCampaignSetFetcher { "{}" }.etag, etag)
        assertEquals(CampaignFetchResult.Fetched(json, etag), bundled.fetch(null))
        assertEquals(CampaignFetchResult.Fetched(json, etag), bundled.fetch("\"v3\""))
        assertEquals(CampaignFetchResult.NotModified, bundled.fetch(etag))
        assertEquals(1, reads)
    }
}
