# TCL Prime Video and Sony Netflix button redirect

This note describes the device-specific workarounds that make the TCL Prime
Video and Sony Netflix remote buttons launch Go3 TV+. The helper is
intentionally separate from the TV app: branded global keys are intercepted by
Android before a normal activity can receive them.

## What the remote sends

On the tested TCL Google TV and its `BT_RC833A_B5 Consumer Control` input
device, pressing Prime Video produced:

```text
MSC_SCAN 000c005f
EV_KEY   02f0 DOWN
EV_KEY   02f0 UP
```

TCL/Google TV then turns this into a protected global launch request for:

```text
com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity
```

If Prime Video is not enabled for the current user, TCL's partner customizer
opens the Prime Video installation page instead.

## Why an ordinary redirect APK does not work

- The global key is consumed before `Activity.onKeyDown` and an Android
  accessibility service can receive it.
- `com.google.global_button.ACTION_LAUNCH_APP` is protected with Google's
  signature/privileged permission, so a regular broadcast receiver cannot
  intercept it.
- An APK cannot safely use `com.amazon.amazonvideo.livingroom` as its package:
  the system image already knows that package and only accepts an update signed
  with Amazon's matching certificate.
- The TCL resource overlay that assigns global keys is immutable on a normal,
  non-rooted device.
- The Google TV launcher's exported
  `com.google.android.apps.tv.launcherx.CONFIGURE_GLOBAL_BUTTON` action is left
  in its manifest, but the tested launcher version ignores that action in its
  receiver implementation.

No signature checks, Android permissions, or system partitions are bypassed by
this workaround.

## Autonomous helper app

The optional `tclredirect` module builds a separate, device-aware helper APK. On TCL it connects to
the TV's ADB daemon through `127.0.0.1:5555`, using its own RSA key stored in
the helper's device-protected private storage. It then starts a small process
with Android's ADB shell identity. The process:

1. finds the Bluetooth remote's Consumer Control input device;
2. watches for raw key code `02f0` going down;
3. starts `ee.local.go3tvplus.debug/ee.local.go3tvplus.MainActivity`;
4. disables `com.tcl.partnercustomizer` for user 0 so the Prime installation
   page cannot win the launch race.

The helper also:

- grants its own package TCL's `APP_AUTO_START` app-op through the authorized
  local ADB connection;
- receives `BOOT_COMPLETED` and schedules an immediate listener check;
- runs a persisted 15-minute JobScheduler check, so Android killing the shell
  process does not leave the button permanently broken;
- requires no computer or home server after the one-time setup.

Build and install the helper:

```bash
./gradlew :tclredirect:assembleRelease
adb -s <TCL_IP>:5555 install -r \
  tclredirect/build/outputs/apk/release/tclredirect-release.apk
```

Open **Go3 TV+ nupusuunaja**, choose **Seo ADB ja käivita**, and approve the
Android ADB dialog with **Always allow** selected. This is the helper's own key,
not the computer's key. Network ADB must remain enabled on the TCL.

The remote listener and its log live at:

```text
/data/local/tmp/go3-button-redirect.sh
/data/local/tmp/go3-button-redirect.pid
/data/local/tmp/go3-button-redirect.log
```

Useful checks:

```bash
adb -s <TCL_IP>:5555 shell \
  'cat /data/local/tmp/go3-button-redirect.pid'
adb -s <TCL_IP>:5555 shell \
  'tail /data/local/tmp/go3-button-redirect.log'
```

On the physical restart test, the old listener PID was `4749`; the helper
created a new listener with PID `4262` about nine seconds after Android reported
boot completion.

## Sony Netflix button

The tested Sony BRAVIA remote maps scan code `583` (`0x0247`) to Android
`KEYCODE_BUTTON_4` (`191`). Sony maps that global key directly to
`com.netflix.ninja/.MainActivity`, so a normal manifest broadcast receiver does
not receive it. Disabling Netflix alone only makes Sony display “feature not
available”.

On Sony, the same helper enables its own accessibility key-filter service using
the one-time authorized local ADB connection. The service consumes
`KEYCODE_BUTTON_4` before Sony's global-key handler and launches Go3 TV+.
Enabling it preserves any accessibility services that were already active.
The enabled-service setting is persistent, so no shell listener, computer,
network ADB connection, or periodic recovery job is required after setup.

The helper disables `com.netflix.ninja` for user 0 during setup. Choosing the
helper's **Taasta Netflixi nupp** action removes only the Go3 key-filter service
from the enabled accessibility-service list and re-enables Netflix. The tested
physical press was recorded as:

```text
I/Go3ButtonRedirect: Netflix button intercepted; opening Go3 TV+
mResumedActivity: ee.local.go3tvplus.debug/ee.local.go3tvplus.MainActivity
```

## Restoring TCL's original behaviour

Open the helper and choose **Taasta Prime-nupp**. This stops the listener,
re-enables TCL's partner customizer, changes `APP_AUTO_START` back to `ignore`,
cancels the helper jobs, and clears its configured state.

The tested TV still has Prime Video in its read-only system image, even when it
has been removed for user 0. It can be restored without downloading an APK:

```bash
adb -s <TCL_IP>:5555 shell \
  cmd package install-existing --user 0 com.amazon.amazonvideo.livingroom
```

## Known constraints

- This is specific to the tested TCL remote and raw key code `02f0`.
- Disabling network ADB prevents the helper from recreating the listener.
- Clearing helper app data deletes its RSA key and requires one-time ADB
  authorization again.
- Uninstalling the helper does not execute its restore button. Restore the
  original button before uninstalling, or re-enable the partner customizer with
  ADB.
