# Go3 Air 0.5.3 validation

Checked on 2026-11-14 on the signed-in TCL Smart TV Pro / G10. Version code **57**, package `ee.local.go3tvplus.debug`, non-debuggable. The APK retains the existing signing certificate SHA-256 `227cdf1d6ac5449cbba8ffef687846f307db3db21b9d97dc7fcfae8c05d1aba1`.

Final APK SHA-256:

```text
cc497bb1e8266fe383dbef1dd8f4e0f5cf63c3d37e43696d211a336b481e125a
```

- **97 JVM tests passed** and `lintDebug` completed without errors.
- A new **native Compose regression test passed on TCL**. It renders the real seek panel with an active movie, an episode, missing optional metadata, stale catch-up selection and a retained TV channel/EPG. It checks displayed titles/descriptions, episode number, media position/duration, absence of TV/live labels and EPG boundary ticks, then verifies TV and catch-up metadata return when catalogue playback ends. This test uses synthetic UI state and creates no video session or account progress.
- The original fault was reproduced on an already-playing subscribed episode: the media session described the catalogue video while the OK/pause panel showed the retained TV channel's current programme and its EPG boundary tick.
- The minified preview resumed that same episode from Continue watching. A real OK press showed the episode title/number and video duration with no TV programme information or EPG ticks. Pause, +10 seconds, -10 seconds and OK resume passed; the seek test restored the original playback position.
- Final-build BACK checks used real remote-key input: an open panel closed without ending the playing episode; the same check preserved a paused episode and its exact position. A further BACK returned to the series detail view and restored the TV background.
- Production APK signature, package, version and non-debuggable manifest were verified. Installation uses `adb install -r` without clearing account data. The final 0.5.3 / 57 build was installed on TCL, resumed the same series and showed the corrected panel while playback continued. The instrumentation helper was removed.

The change makes the active catalogue item part of the observable TV/player UI state; the player controller's existing VOD getter/setter uses that same state. Existing cleanup on return to TV, profile change and playback failure therefore also clears the panel metadata. The retained TV channel remains available for background playback/restoration. Playback routing, buffering, DRM and synchronization are unchanged.

Sony's previous ADB address did not respond and no Sony device was advertised over mDNS, so this patch has no new Sony check. No new long playback soak was performed for this UI metadata patch.
