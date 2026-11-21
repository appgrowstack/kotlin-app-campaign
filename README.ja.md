# kotlin-app-campaign

Androidのアプリを出し直さずに、合う人に合う物をアプリの中で出す。出すかどうかは端末が決める。

[English](./README.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)
![JVM](https://img.shields.io/badge/JVM-17-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

キャンペーンには、アプリ自身の画面（プレースメント）の名前・そこに渡す値・きっかけの出来事・相手の条件・回数・比べるために出さない人（holdout）を書きます。
[swift-app-campaign](https://github.com/appgrowstack/swift-app-campaign) のKotlinの側です。どちらも同じ組を読み、同じ判定の表を通るので、同じ人はiOSでもAndroidでも同じ物を見て、holdoutの側も同じになります。

## 機能

- **出すのはアプリの画面だけ。** 出せるのはアプリが登録したプレースメントだけです。HTML・後から取る画面・SDKの見た目はありません
- **理由付きの純粋な判定。** `CampaignRules.decide` は時計・保存先・通信を読まず、出すキャンペーンか、キャンペーンごとの出さない理由を返します。順番は、古い組・使えない・期間外・きっかけ無し・回数・端末の条件・サーバーの条件・holdout・優先・文と画像です。全画面が出ていて待った後は、`CampaignRules.recheck` が組と回数を読み直します
- **出来事は型のまま。** 値は型を落としません（`CampaignValue`）。`3` と `3.0` は同じ数で、文字列の `"3"` は別の物です。比べると `type_mismatch` を記録します
- **サーバーの値は最後に聞く。** 端末の条件が全部通った後にだけ、アプリのサーバーに聞きます
- **どのキャンペーンにもholdout。** `hash32("<project>:<person>:<id>:holdout")` で、SwiftのSDKと同じ側になります。どちらの側も `campaign_eligible` を記録するので、2つの集まりを比べられます
- **出さない側に倒れる。** 通信できないときは手元の組を `cache_hours` まで使い、その後は何も出しません。読めない組が届いたら前の組を使います。知らないプレースメント・動き・値・演算子・紹介先や型のずれは、そのキャンペーンだけを飛ばして `campaign_rejected` を1回記録します
- **単体で動く。** `BundledCampaignSetFetcher` がアプリに同梱した組を読みます。ほかから取るときは、`CampaignSetFetcher` を満たす別の物に差し替えます
- **中身はAndroidに依存しない。** `campaign-core` はKotlin/JVM（coroutinesとkotlinx.serialization）なので、headlessのテストからJVMで動かせます。テスト用の物は `com.appgrowstack.campaign.testing` にあります

## はじめに

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

起動したときと前面に戻ったときに組を読み直し、アプリの計測が送っている出来事を渡し、プレースメントを自分のComposeの画面で出します。

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

アプリのテストでは、登録した物を `campaigns/manifest.json` と比べて確かめます。

```kotlin
assertEquals(emptyList(), CampaignManifest.verify(campaigns.registry, manifestText))
```

### サーバーにつなぐ

このパッケージは単体で使えます。サーバーにつなぐときは、このパッケージで送り先を書く代わりに、コネクターのパッケージ [kotlin-appgrowstack](https://github.com/appgrowstack/kotlin-appgrowstack) の対応するモジュールを足します。

## ドキュメント

APIの説明は `campaign-core/src/main` のKDocです。変更は [CHANGELOG.md](./CHANGELOG.md) にあります。

## 入れ方

モジュールは [JitPack](https://jitpack.io/#appgrowstack/kotlin-app-campaign) で公開しています。`settings.gradle.kts` にリポジトリを足します。

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

アプリの `build.gradle.kts` に足します。

```kotlin
dependencies {
    implementation("com.github.appgrowstack:kotlin-app-campaign:0.5.0")
}
```

モジュールが `campaign-core` 1つの間は、JitPackはリポジトリの名前で公開します。

### モジュール

| モジュール | 中身 | 依存 |
|---|---|---|
| `campaign-core` | 組の読み込み・判定・holdoutと実験の計算・エンジン・プレースメントと値と動きの登録・manifestとの比較・保存と取得のインターフェース・テスト用の物 | kotlinx-coroutines・kotlinx-serialization-json |

## 動く環境

- JDK 17+
- Android: `java.time` のためにminSdk 26、またはcore library desugaring
- Kotlin 2.4+

## ライセンス

MIT。[LICENSE](./LICENSE) を見てください。
