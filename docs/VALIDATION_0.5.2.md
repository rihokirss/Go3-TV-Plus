# Go3 Air 0.5.2 validation

Checked on 2026-11-13 on the existing signed-in TCL Smart TV Pro / G10. Final version code **55**, package `ee.local.go3tvplus.debug`, non-debuggable. The final APK retains the production signing certificate SHA-256 `227cdf1d6ac5449cbba8ffef687846f307db3db21b9d97dc7fcfae8c05d1aba1`.

Final APK SHA-256:

```text
cb7bfa1149e10ec7d6cee428d2c05a5a0da71e7e25c2723704b7f206b6c69996
```

- **97 JVM tests passed**, including direct server-result metadata, search/page isolation and unknown entitlements.
- **7 TCL instrumentation tests passed**: five signed-in focus/search/image tests, plus isolated offline/profile-search and database migration tests. The test build used the final application source; the production build changes the version metadata and enables minification.
- Actual D-pad input checked eight menu/BACK/RIGHT round trips without intermediate filter focus, restoration of a selected filter, See all opening the grid, and BACK returning to the same row button.
- Detail/BACK checked a horizontally scrolled film row and a vertically scrolled grid. Assertions include the actual focused Compose node, stable card identity and exact saved scroll position. The existing player object and TV channel were retained.
- Coil checks verified successful decoding, a memory-cache hit, then a disk-cache hit after removing that memory entry.
- Search typed one character at a time without an IME submit action, retained input focus, updated visible result counts, cancelled superseded edits, handled no-match queries and cleared immediately.
- The final TCL run measured local FTS median **15.67 ms**, maximum **40.77 ms** across twelve prefix queries. A broad one-character query took **45.80 ms**. The previous JOIN query measured a **3.85 s** prefix median and **40 s** for the broad query on the same TV. The signed-in server/cache merge completed in **372 ms** on the final run, excluding the retained **300 ms** typing debounce. Network times vary.
- Isolated tests verified uncached server titles, unknown versus known rights, cached fallback during server failure, cancellation/profile separation, no playback-ticket requests, and preservation of TV settings/progress through migration.
- `lintDebug` completed with **zero errors** and 19 warnings. The final minified APK built successfully and passed APK signature/manifest verification.

The production APK was installed over the debug test build without clearing data. It reports version **0.5.2 / 55** and has no DEBUGGABLE flag. ETV resumed using the existing account. System media-session/resource-manager inspection found one Go3 playback session and one active secure video decoder. The production movie catalogue and its selected white poster were visually checked; live TV was restored afterwards. The instrumentation helper was removed.

No purchases or artificial viewing progress were created. This patch changes search, images and focus restoration; playback synchronization remains unchanged. No new Sony/Chromecast or 30-minute playback soak was performed for this patch.
