# Go3 Air 0.5.1 validation

Checked on 2026-11-13, TCL Smart TV Pro / G10, using the existing signed-in installation. Version code **53**, production package `ee.local.go3tvplus.debug`, non-debuggable. SHA-256 of the final APK:

```text
e96d25eb05490cb5c734d1d1e9a81718bc313ac9411426c0ddb9a6ffbd9685d7
```

- **96 JVM tests passed**, including unopened-film genre rows, package/kind/year filtering, authoritative popularity order, duplicate/missing IDs and empty rankings.
- **9 TCL instrumentation tests passed**. They cover schema 4/5/6 upgrades to schema 7, preservation of viewing progress/watchlists/offline history, signing information, interrupted metadata refresh, profile isolation, bulk genre hydration with zero detail/ticket requests, cached ranking preservation during a network failure, and real Go3 account browsing.
- The signed-in Go3 ranking contract returned **20 movies**. The application preserves the server order, filters it by entitlement and keeps the order in the “Vaata kõiki” grid.
- The real browsing test verified multiple populated genres before opening any title, preservation of a selected genre when opening “Vaata kõiki”, grid/BACK navigation, **104 consecutive D-pad movements with focus retained**, the same player object and the same live TV channel.
- The settings test checked entry from the main menu, the viewing-preferences submenu, BACK restoring the selected settings row, and LEFT returning to main navigation without replacing the player or tuning TV.
- `lintDebug` completed with **zero errors** (19 warnings).

The final minified APK was installed over the test build without clearing data. Production settings, the compact movie home, Go3 popularity row and movie detail were visually checked on TCL. The movie home and detail screenshots in README were refreshed from this APK. The test helper was removed and live TV restored to ETV. System media-session/resource-manager checks showed one Go3 playback session and one active secure video decoder; Spotify and Bluetooth entries were not playing video.

No purchases or artificial viewing progress were created. The playback synchronization configuration is unchanged. This patch was not subjected to a new 30-minute playback soak or new Sony/Chromecast tests; the changes concern metadata, navigation and layout.
