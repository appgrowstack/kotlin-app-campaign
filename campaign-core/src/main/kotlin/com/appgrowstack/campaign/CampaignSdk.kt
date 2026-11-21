package com.appgrowstack.campaign

/** This package's name and version, sent to appgrowstack in the `AppGrowStack-Client` header. */
public object CampaignSdk {
    /** The package name (`kotlin-app-campaign`). */
    public const val NAME: String = "kotlin-app-campaign"

    /** The released version. Must equal `version` in gradle.properties (CampaignSdkTest checks it). */
    public const val VERSION: String = "0.1.0"

    /** The `AppGrowStack-Client` header name. */
    public const val CLIENT_HEADER: String = "AppGrowStack-Client"

    /**
     * `<platform>/<app_version>/<sdk name>/<sdk version>` (`android/2.1.0/kotlin-app-campaign/0.1.0`).
     * Carries nothing that identifies a person; the API only counts which versions fetch the set.
     */
    public fun client(platform: String, appVersion: String): String = "$platform/$appVersion/$NAME/$VERSION"
}
