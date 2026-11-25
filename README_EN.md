# Virtual Touchpad

English | [简体中文](README.md)

A thumb-sized virtual joystick for foldable phones: a translucent touch area floats in the bottom-right
corner of the screen. Slide your thumb inside it to move the on-screen cursor, tap to click at the cursor
position, press and hold to long-press. One hand can reach the other side of the screen, the top edge and
the top-right corner — no switching hands, no shrinking the whole screen.

Inspired by the "virtual touchscreen" feature of the Huawei Mate X8; the goal is to make it work on other
foldables as well (Xiaomi MIX Fold / HyperOS first).

## Features

- **Virtual joystick**: a translucent circular touch area in the bottom-right corner. Sliding your thumb
  inside it moves the cursor, a tap clicks at the cursor, holding still long-presses
- **Flick to swipe**: flicks inside the pad (≥ 40dp of travel at ≥ 900dp/s) inject a swipe in the same
  direction at the cursor — paging through photos, scrolling lists, switching pages, without lifting the thumb
- **Moving the pad**: hold the outer ring for 3 seconds (one short vibration), then drag — the joystick can
  be placed anywhere
- **Auto edge-hide**: after 10 s idle the whole pad slides off the screen edge, leaving only a small arc
  along the edge (the visible amount is adjustable in settings, 25% by default); tap once to restore it
- **Auto hide**: hides on the lock screen, when the IME is shown, when the notification shade / control
  center is pulled down, during full-screen video and games; you can also pick apps in which it never shows
- **Unfolded only**: hidden while the foldable is folded (short side < 600dp)
- **Control center tile**: a bundled Quick Settings tile (pull down the control center → “edit” in the top-right → tap “Virtual Touchpad” to add it). Tapping the tile toggles the pad; **long-pressing it opens the app's settings page directly**. Two static shortcuts are also provided
- **Three appearances**: frosted glass (matched to the original), skeuomorphic inset, joystick cap

## Installation

1. Download the APK from [Releases](../../releases) and install it
   - The current release is **v1.7.2 (signed with the release key)**. If you installed v1.0 or a debug
     build before, **uninstall it first** — a different signature cannot overwrite; v1.1 and later upgrade
     in place
2. Open the app; it asks for the accessibility permission on first launch. This is required: drawing over
   other apps and injecting taps into them is only possible with an accessibility service
3. Also enable autostart / background running and set the battery policy to "unrestricted", so the service
   is not killed by the system

## Compatibility

- Android 8.0 (API 26) and later
- The whole interaction path is verified on an emulator (API 35, 2200×2400 unfolded)
- On Xiaomi HyperOS, allow "display pop-up windows while running in the background" under
  App settings → Permissions, and allow autostart
- Huawei / Honor and other models have not been tested one by one; an accessibility service is a generic
  mechanism, so it should work in theory

## Known limitations

- Full-screen detection is unreliable on some ROMs (the system does not deliver a dependable window
  signal), so the "apps that hide the joystick" list is provided as a fallback
- Adding the tile is left entirely to the system: the app no longer offers a “add it for me” button,
  so you add it yourself from the control center's edit page (on HyperOS / MIUI it lives in the
  “app shortcuts” group)
- The three appearances look flatter on dark wallpapers

## Building

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug
```

The repo uses the Aliyun Maven mirror by default (see `settings.gradle`), so it builds directly from
mainland China; replace it with the official source if you prefer.

## Implementation notes

- Both the joystick and the cursor are `TYPE_ACCESSIBILITY_OVERLAY` windows added through `WindowManager`
- Taps are injected with `AccessibilityService.dispatchGesture`, whose coordinates are **physical screen
  coordinates**; the cursor window is therefore sized to the cursor itself and positioned by window
  coordinates, so system insets cannot push the cursor and its tap point apart
- System-UI occlusion detection: lock screen (KeyguardManager) → notification shade (a `TYPE_SYSTEM`
  window taller than 60% of the screen) → IME (`TYPE_INPUT_METHOD` window height) → full screen (the
  status-bar window is gone or squeezed to zero height) → app list

## License

[PolyForm Noncommercial License 1.0.0](LICENSE) + commercial license, dual-licensed.

- **Personal study, research and hobby use**: free to use, modify and distribute, provided the copyright
  notice and attribution are kept
- **Commercial use**: requires separate written permission, see [LICENSE-COMMERCIAL.md](LICENSE-COMMERCIAL.md)

Original author: eosed (<https://github.com/eosed/VirtualTouchpad>). Keep the attribution when you build on it.
