# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Campaign variants (R10 P5): a campaign's optional `variants` (two, each `id`, `values`, optional
  `text_key`) and the set's optional `allocations` (`weights`, `policy_version`). A variant whose values
  do not fit the placement rejects the campaign; an unreadable allocation is ignored (equal weights).
  On the shown side the variant is chosen with `hash32("<project>:<person>:<campaign>:variant")` and
  the weights; the owner's device gets the first variant that is not stopped.
  `CampaignSelection.variant`, `CampaignDecision.Eligible.variant`, `CampaignPresentation.variant`,
  `CampaignSet.allocations` and `Experiment.variantIndex`.
- `campaign_eligible`, `campaign_shown`, `campaign_tapped`, `campaign_dismissed` and
  `campaign_action_completed` carry `variant` when the campaign has variants. `campaign_eligible`'s
  `propensity` is then the chance of the variant and `policy_version` the allocation's.
- Shared cases `variant-*` in the tests.

## [0.5.0] - 2026-11-22

### Changed

- **Breaking.** `CampaignDecision.heldOut` is replaced by `CampaignDecision.eligible`
  (`Eligible(campaign, trigger, side)` for every campaign that passed the holdout step), the same as
  the Swift SDK. `CampaignDecisionInput(sides = …)` takes the sides already decided on the device.

### Fixed

- A holdout side, once decided on this device, is kept in `CampaignStoredState.sides` and does not
  change when the set's holdout share changes (it was hashed again on every decision, so changing the
  share moved Android people between sides). The owner's device never keeps a side.
  `CampaignEngine.sides` shows them. `Experiment.Side` is stored as `treatment` / `holdout`, as the
  Swift SDK stores it.
- A tap, a survey answer and a dismissal of the same presentation that arrive together (two threads,
  a double tap) record and run only the first. The others are ignored.

## [0.4.0] - 2026-11-22

### Added

- The `survey` type: `CampaignSurvey` (placement kind `SHEET`), `CampaignAction.submitAnswer()`, and
  `CampaignEngine.answer` / `CampaignOffer.answer`, which record `survey_answered`. A survey is not
  shown in a launch where `paywall_closed` was heard. `survey_*` events never trigger.
- `CampaignContext(firstSeenAt = …)` (epoch milliseconds): the app's own first-launch date, from
  before it used this SDK. `days_since_first_seen` counts from the earlier of it and the date the
  engine stored.

### Fixed

- A cancelled coroutine (`CancellationException`) is thrown again. It was recorded as an unavailable
  fact or a failed action.

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
