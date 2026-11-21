package com.appgrowstack.campaign

/**
 * The campaign images fetched ahead in this launch. A campaign whose image is not here is not shown
 * (`image_not_ready`), so a placement never opens with a blank image.
 */
internal class CampaignImageCache(private val images: CampaignImagePrefetcher) {
    private val ready = mutableSetOf<String>()

    /** The image URLs that were fetched in this launch, as a copy. */
    fun snapshot(): Set<String> = ready.toSet()

    /** Fetches every `url` image of the set that is not ready yet, one at a time. */
    suspend fun prefetch(set: CampaignSet) {
        for (campaign in set.campaigns) {
            for (value in campaign.values.values) {
                val image = CampaignImage.fromJson(value) as? CampaignImage.Url ?: continue
                if (image.url in ready) continue
                if (images.prefetch(image.url)) ready.add(image.url)
            }
        }
    }
}
