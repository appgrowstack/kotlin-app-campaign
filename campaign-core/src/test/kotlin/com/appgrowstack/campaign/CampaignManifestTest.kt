package com.appgrowstack.campaign

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignManifestTest {
    private val text = File(SharedCasesTest.directory, "inapp/manifests/gamification-app.json").readText()

    @Test
    fun readsTheManifest() {
        val registry = CampaignManifest.registry(text)
        assertEquals(CampaignPlacementKind.SYSTEM, registry.placements.getValue("review_ask").kind)
        assertEquals(CampaignFactSource.SERVER, registry.fact("trial_eligible")?.source)
        assertEquals(CampaignFactType.INT, registry.fact("session_seconds")?.type)
        assertTrue("icon_reading_memory" in registry.assets)
    }

    @Test
    fun verifyFindsWhatDiffers() {
        val registry = CampaignManifest.registry(text)
        assertEquals(emptyList(), CampaignManifest.verify(registry, text))
        val missing = registry.copy(placements = registry.placements - "banner", assets = registry.assets + "extra")
        assertEquals(
            listOf("placement banner is in the manifest but not registered", "asset extra is registered but not in the manifest"),
            CampaignManifest.verify(missing, text),
        )
    }

    @Test
    fun surveyIsTheManifestsDefault() {
        val manifest = File(SharedCasesTest.directory, "inapp/manifests/reading-memory.json").readText()
        val registry = CampaignManifest.registry(manifest)
        assertEquals(CampaignSurvey.placement.spec, registry.placements.getValue(CampaignSurvey.NAME))
        assertEquals(CampaignPlacementKind.SHEET, registry.placements.getValue(CampaignSurvey.NAME).kind)
        assertEquals(CampaignAction.submitAnswer().arguments, registry.actions.getValue(CampaignAction.SUBMIT_ANSWER))
    }

    @Test
    fun versions() {
        assertEquals(CampaignVersion(3, 10, 0), CampaignVersion.parse("3.10"))
        assertEquals(null, CampaignVersion.parseAppVersion("3"))
        assertEquals(null, CampaignVersion.parseAppVersion("3.3.0.1"))
        assertEquals(null, CampaignVersion.parseAppVersion("3.x"))
        val range = ResolvedVersionRange(CampaignVersion(3, 3), listOf(CampaignVersion.parseAppVersion("4.0")!!))
        assertTrue(CampaignVersion.parse("3.3")!! in range)
        assertTrue(CampaignVersion.parse("4.0.0")!! !in range)
        assertTrue(CampaignVersion.parse("4.0.1")!! in range)
        assertTrue(CampaignVersion.parse("3.2.9")!! !in range)
    }
}
