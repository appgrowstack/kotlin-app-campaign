# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- The `survey` type: `CampaignSurvey` (placement kind `SHEET`), `CampaignAction.submitAnswer()`, and
  `CampaignEngine.answer` / `CampaignOffer.answer`, which record `survey_answered`. A survey is not
  shown in a launch where `paywall_closed` was heard. `survey_*` events never trigger.
- `CampaignContext(firstSeenAt = …)` (epoch milliseconds): the app's own first-launch date, from
  before it used this SDK. `days_since_first_seen` counts from the earlier of it and the date the
  engine stored.

## [0.3.0] - 2026-11-21

### Changed

- **Breaking.** `CampaignEngine(fetcher = …)` is required and the engine no longer takes a project
  key.

### Added

- `BundledCampaignSetFetcher`: the set shipped in the app, with a stable ETag so it is decoded once.
- `CampaignSetFetchFailure(status)` for fetchers that got neither a set nor "not modified".
- `CampaignSdk.NAME` and `CampaignSdk.VERSION`, for a connector to report when it fetches sets.

### Removed

- `HttpCampaignSetFetcher`. Fetching from a server is a fetcher in a separate connector module.

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
