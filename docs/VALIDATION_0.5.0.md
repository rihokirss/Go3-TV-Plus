# Go3 Air 0.5.0 validation — 2026-11-12

Build: version code 52, minified non-debuggable sideload package `ee.local.go3tvplus.debug`, Android 9+.

## Automated checks

- `:app:testDebugUnitTest`: 94 tests, no failures, errors or skips.
- `:app:lintDebug`: no errors. Warnings include existing platform/resource/API notices and Compose/KTX style suggestions.
- `:app:packageSideloadApk`: successful; package identity and the existing signing certificate verified with Android build tools.
- `git diff --check`: clean.

## Sony device acceptance

Sony BRAVIA 4K GB ATV3, Android 9/API 28, 2 GB RAM, 192 MB Java heap. The user selected Sony for the device checks; TCL and Chromecast HD were not tested for this release.

Ten native instrumentation tests cover:

1. Signed-in movie and multi-season series: local search supersession and accent folding; locked content; stale rights rejected by the real backend and TV restored; play/pause/seek/progress/resume; same VOD after backgrounding; second-season episodes; autoplay off at actual episode end; ten-second next-episode countdown, BACK cancellation and OK continuation; local watchlist round trip; 100 consecutive D-pad moves; poster/detail/BACK focus restoration; channel and number key isolation; same shared player and TV channel on return.
2. Real SQLite schema 4→6 migration, preserved TV data, removed watch-now table, FTS replacement, and profile-isolated search/watchlist/progress.
3. Sony Android 9 archive-signature regression: the combined signing flags return the matching signing certificate where the signing-certificates-only archive query does not.
4. Interrupted catalogue refresh preserves usable cache; rights/network failure remains visible; missing poster; profile changes cancel obsolete requests; metadata operations request no video ticket; unknown entitlement remains distinct from locked.

5. Schema 5→6 migration preserves progress and queues existing records for upload across database reopen.
6. Remote history import, offline outbox recovery after repository restart, newer rewinds, concurrent upload acknowledgment, profile isolation and remote removals.
7. A partially imported episode history must fetch a complete ordered season once; subsequent lookup uses cache, and season refresh invalidates completeness.
8. Real Go3 profile history round trip, second-client import, intentional rewind and isolated test-record cleanup.
9. Cached catalogue selection restores the same card from the menu without rebuilding the view, keeps native focus during movement, and restores the card after detail/BACK.
10. Search IME BACK hides the actual keyboard without closing the catalogue; settings/BACK restores menu focus without reopening the keyboard.

Real service contract probes additionally verified normal movie/episode configuration, playlist and Widevine licences, and a rejected locked movie. Verification sessions were closed. No purchases were performed. An isolated real-account test verified app uploads, second-client changes and intentional rewinds, then removed its single temporary bookmark while preserving existing account records.

Functional playback tests suspend debug account sync; their artificial playback positions are removed from local progress and outbox before production installation.

## Production APK soak

The final APK (SHA-256 `2d0f46a9c148051a62a742fd4d107bb7078d1330d6df50d5bdb77a750e041df6`) ran for **1,801.5 seconds** with TV behind catalogue navigation, including **310 D-pad moves** and repeated Movies/Series changes. Go3 history sync remained enabled. The same application process survived throughout; one Go3 media session was present and decoder counters never reset. No application crash/ANR was observed.

| Measurement | Observed |
| --- | --- |
| Playback samples | 120; all READY, speed 1.0, 50 fps format |
| Video buffer | 12.50–32.15 seconds |
| Java heap in playback samples | 67–143 MB; the 143 MB peak returned to the usual range |
| Process PSS, 28 samples | 134.4–171.8 MB |
| Decoder counters during the sampled window | 88,757 rendered; 958 dropped (1.068%) |
| Playback state transition | One BUFFERING→READY transition lasting 93 ms, with 17.568 seconds still buffered |
| Recorded card-focus callbacks | 230; median 4.01 ms, p95 148.77 ms, maximum 207.90 ms |
| Same-section selection, three initial warm measurements | 201.6–323.0 ms |
| Other section/search entries during the run | 618–2,022 ms |

**Timing targets are not universally met on this older Sony.** Cold startup/cache decoding and a newly composed section can take seconds, and some focus-restoration callbacks exceeded 100 ms. Focus measurements are application callbacks, not a complete display-latency benchmark. Native D-pad/BACK tests, production focus logs and screenshots verified restoration; Sony accessibility sometimes exposed the Compose root bounds instead of the focused virtual card bounds. Navigation was visually checked and the same-process soak continued without restarting the app.

Production media PLAY/PAUSE keys were separately verified against the active Go3 media-session state while the catalogue was open. The earlier Sony installer regression was checked using the existing signing certificate, not by relaxing signature verification.

## Reproduce

Build debug/test APKs and run JVM tests first, then `:app:packageSideloadApk`, then `:app:lintDebug` in separate Gradle invocations. Lint source discovery races KSP regeneration if the sideload variant is regenerated at the same time.

Native workflow tests require a signed-in, authorized test TV. Install debug + androidTest APKs, then run the five `ee.local.go3tvplus.catalog` instrumentation classes (account check first) with `androidx.test.runner.AndroidJUnitRunner`. For the movie/episode boundary test, pass `-e suspendHistorySync true` before the Activity starts; the test refuses to write artificial playback positions otherwise. Use a dedicated test profile and restore its local progress/outbox before installing production: production intentionally ignores the debug suspension flag. The Sony checks described here explicitly removed the three controlled playback-test rows and queue entries before the final production install. The functional suite uses native key injection rather than a Compose virtual test clock. Test accounts should have an available film, a multi-season series and at least one locked film.

Production performance is measured on the minified APK, separately from instrumentation overhead. Local diagnostics record only buffer duration, playback state/speed/frame counters, heap size and focus latency; no stream URLs, tokens or account identifiers.
