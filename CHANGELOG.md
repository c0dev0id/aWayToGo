# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- MapLibre Native Android SDK for map rendering
- `MAPTILER_KEY` injected at build time via `BuildConfig` from environment variable
- `MapScreen` composable with full lifecycle handling (`onStart`, `onResume`, `onPause`, `onStop`, `onDestroy`)
- INTERNET permission in `AndroidManifest.xml`
- `MAPTILER_KEY` env var passed to build steps in CI workflows (build and release)
- Development journal at `.github/development-journal.md`

### Changed
- Root `build.gradle.kts`: replaced duplicate `kotlin.plugin.compose` entry with `org.jetbrains.kotlin.android`
- App `build.gradle.kts`: added `org.jetbrains.kotlin.android` plugin and enabled `buildConfig`
- `MainActivity`: replaced placeholder `Greeting` composable with full-screen `MapScreen`

### Removed
- `scripts/` directory (leftover from project template)
