# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Fetching the set sends `AppGrowStack-Client: <platform>/<app_version>/kotlin-app-campaign/<version>`
  (`android/2.1.0/kotlin-app-campaign/0.1.0`), built from `CampaignContext` by the engine. It carries
  nothing that identifies a person. `HttpCampaignSetFetcher(projectKey, client, …)` now takes the
  header value; build it with `CampaignSdk.client(platform, appVersion)`.

### Added

- `CampaignSdk.NAME` and `CampaignSdk.VERSION`.

### Fixed

- The JitPack coordinate in the README: `com.github.appgrowstack:kotlin-app-campaign`.

## [0.1.0] - 2026-11-21

### Added

- `campaign-core`: the set loader (`CampaignSetLoader`, with the set's `targets` and
  `rejected:unknown_target`), the pure decision (`CampaignRules.decide`) and the recheck after
  waiting (`CampaignRules.recheck`, `withdrawn`), typed values (`CampaignValue`), limits including
  `max_per_app_version`, `campaign_eligible` on both holdout sides, text keys for required texts,
  `system` placements, the experiment math (`Experiment.hash32`, `assign`, `reach`, `compare`),
  `CampaignEngine` (fetch with ETag, storage, `cache_hours`, waiting while the app is busy, taps,
  dismissals, `app_updated`), placement / fact / action registration, `CampaignManifest.registry`
  and `verify`, and fakes for tests in `com.appgrowstack.campaign.testing`.
- Passes every table in `schema/cases/inapp` and `schema/cases/experiment`.
