# kotlin-app-campaign

Show the right thing to the right person inside your Android app without shipping a new build, with the decision made on the device.

[Japanese](./README.ja.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

A campaign names one of your app's own screens (a placement), the values to fill it with, the event
that triggers it, who it is for, how often, and who is held out for comparison. It is the Kotlin
side of [swift-app-campaign](https://github.com/appgrowstack/swift-app-campaign): both read the same
sets and pass the same decision tables, so a person sees the same thing on iOS and Android and lands
on the same holdout side.

## Features

- **Your screens only.** A campaign can show only placements your app registered. No HTML, no
  downloaded UI, no visual design in the SDK
- **A pure decision with reasons.** `CampaignRules.decide` reads no clock, storage or network and
  returns the campaign to show, or each campaign's reason not to: stale set, unusable, inactive,
  no trigger, limits, device conditions, server conditions, holdout, priority, text and images.
  After waiting for a busy screen, `CampaignRules.recheck` looks at the set and the counts again
- **Typed events.** Props keep their types (`CampaignValue`): `3` and `3.0` are the same number, the
  string `"3"` is not, and comparing it is reported as `type_mismatch`
- **Server facts asked last.** A fact from your own server is requested only after every device
  condition has passed
- **A holdout for every campaign.** `hash32("<project>:<person>:<id>:holdout")`, the same as the
  Swift SDK. Both sides record `campaign_eligible`, so the two groups are comparable
- **Fails closed.** Offline, the set on hand is used for `cache_hours`, then nothing is shown. An
  unreadable set keeps the previous one. An unknown placement, action, fact, operator, target or a
  wrong type skips that campaign and is reported once as `campaign_rejected`
- **Works on its own.** `BundledCampaignSetFetcher` reads the set shipped in the app. Any
  `CampaignSetFetcher` can fetch it from elsewhere instead
- **No Android dependency in the core.** `campaign-core` is Kotlin/JVM (coroutines and
  kotlinx.serialization), so headless tests run it on the JVM. Test doubles are in
  `com.appgrowstack.campaign.testing`

## Quick Start

```kotlin
@Serializable
data class TrialOfferValues(
    val headline: String,
    val body: String,
    @SerialName("primary_label") val primaryLabel: String,
    @SerialName("secondary_label") val secondaryLabel: String,
    val image: CampaignImage? = null,
)

val trialOffer = CampaignPlacement.of(
    "trial_offer", TrialOfferValues.serializer(),
    mapOf(
        "headline" to CampaignField.text(maxChars = 24), "body" to CampaignField.text(maxChars = 120),
        "primary_label" to CampaignField.text(maxChars = 24), "secondary_label" to CampaignField.text(maxChars = 12),
        "image" to CampaignField.image(),
    ),
)

val campaigns = CampaignEngine(
    context = CampaignContext(appVersion, build, channel = "play", lang, region),
    person = { personId },
    placements = listOf(trialOffer, CampaignPlacement.system("review_ask")),
    facts = listOf(
        CampaignFact.device("is_premium", CampaignFactType.BOOL) { CampaignValue.of(premium.isPremium()) },
        CampaignFact.server("trial_eligible", CampaignFactType.BOOL) { CampaignValue.of(trial.serverEligible()) },
    ),
    actions = listOf(
        CampaignAction("start_trial") { request -> trialFlow.start(request.trigger) }, // returns a CampaignActionResult
        CampaignAction("request_review") { reviews.launch(); CampaignActionResult.COMPLETED },
    ),
    storage = FileCampaignStorage(context.filesDir),
    record = { event -> analytics.track(event.name, event.params) },
    // the set shipped in the app's assets
    fetcher = BundledCampaignSetFetcher { assets.open("campaigns.json").bufferedReader().use { it.readText() } },
    texts = { key -> strings[key] },
    isBusy = { router.hasFullScreen },
)
```

Refresh at launch and on return to the foreground, feed it the events your analytics already sends,
and present the placement with your own Compose UI:

```kotlin
lifecycleScope.launch { campaigns.refresh() }
campaigns.observe("paywall_closed", mapOf("result" to CampaignValue.of("continue_free")))

@Composable
fun CampaignHost(campaigns: CampaignEngine) {
    val presenting by campaigns.presenting.collectAsState()   // recomposes when a campaign is ready
    if (presenting == null) return
    campaigns.offer(trialOffer)?.let { offer ->
        TrialOfferSheet(offer.values, onPrimary = offer.tap, onClose = { offer.dismiss(CampaignDismissal.SECONDARY) })
    }
}
```

In your app's tests, check the registration against `campaigns/manifest.json`:

```kotlin
assertEquals(emptyList(), CampaignManifest.verify(campaigns.registry, manifestText))
```

### Connecting to a server

The package works on its own. To connect it to a server, add the matching module of the connector package [kotlin-appgrowstack](https://github.com/appgrowstack/kotlin-appgrowstack) instead of wiring a server here.

## Documentation

The API reference is the KDoc in `campaign-core/src/main`. Changes are listed in
[CHANGELOG.md](./CHANGELOG.md).

## Installation

The module is published through [JitPack](https://jitpack.io/#appgrowstack/kotlin-app-campaign).
Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add it in the app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.appgrowstack:kotlin-app-campaign:0.3.0")
}
```

While `campaign-core` is the only module, JitPack names the artifact after the repository.

### Modules

| Module | What it has | Depends on |
|---|---|---|
| `campaign-core` | Loader, decision, holdout and experiment math, engine, placement / fact / action registration, manifest check, storage and fetch ports, test doubles | kotlinx-coroutines, kotlinx-serialization-json |

## Requirements

- JDK 17+
- Android: minSdk 26 for `java.time`, or core library desugaring
- Kotlin 2.4+

## License

MIT. See [LICENSE](./LICENSE).
