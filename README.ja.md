[English](./README.md) | 日本語

# kotlin-app-campaign

Android のアプリの中で、新しいビルドを出さずに、合う人に合う物を出す。キャンペーンの件はアプリ自身の画面（プレースメント）・入れる値・きっかけの出来事・誰に・何回まで・比べるために出さない人（holdout）を書き、端末が決める。

[swift-app-campaign](https://github.com/no-problem-dev/swift-app-campaign) の Kotlin の側。どちらも同じ組を読み、同じ判定の表を通すので、同じ人は iOS でも Android でも同じ物を見て、holdout の側も同じになる。

> 開発は非公開のモノリポでしていて、ここへは片方向にコピーしている。Issue は受ける。PR は受けない。

## できること

- **アプリの画面だけ。** 出せるのはアプリが登録したプレースメントだけ。HTML・取ってきた UI・SDK の見た目は無い
- **理由つきの純粋な判定。** `CampaignRules.decide` は時計も保存も通信も読まず、出す件か、件ごとの出さない理由を返す（古い組 → 読めない → 期間外 → きっかけ無し → 回数 → 端末の条件 → サーバーの条件 → holdout → 優先 → 文と画像）。全画面が開いていて待った後は `CampaignRules.recheck` が組と回数を見直す
- **型付きの出来事。** 値は型のまま（`CampaignValue`）。`3` と `3.0` は同じ数で、文字列の `"3"` は違う。比べると `type_mismatch` を記録する
- **サーバーの値は最後に聞く。** 端末の条件が全部通った後にだけ、アプリのサーバーに聞く
- **どの件にも holdout。** `hash32("<project>:<person>:<id>:holdout")` でサーバーと Swift の SDK と同じ側になる。両方の側が `campaign_eligible` を記録するので、比べられる
- **読めなければ出さない。** オフラインでは手元の組を `cache_hours` まで使い、その後は何も出さない。読めない組が届いたら前の組を使い続ける。知らないプレースメント・動き・値・演算子・紹介先や型の違いは、その件だけ飛ばして `campaign_rejected` を1回記録する
- **中身は Android に依存しない。** `campaign-core` は Kotlin/JVM（coroutines と kotlinx.serialization）。headless のテストで `./gradlew test` から動かせる

使い方は [README.md](./README.md) の Quick Start。入れ方は JitPack の `com.github.no-problem-dev.kotlin-app-campaign:campaign-core:<版>`。

## ライセンス

MIT（[LICENSE](LICENSE)）。
