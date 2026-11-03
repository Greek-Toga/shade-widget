# Shade Widget

A home screen widget for Android that mirrors your notification shade. Notifications appear in the same order and sections as the shade, groups expand and collapse with the chevron, and tapping a notification opens it exactly as it would from the shade.

Requires Android 14 or newer. Built and tested on a Pixel 10 Pro.

## Install

1. Download the app: [ShadeWidget.apk](https://github.com/Greek-Toga/shade-widget/releases/latest/download/ShadeWidget.apk). (Backup link: [dist/ShadeWidget.apk](https://github.com/Greek-Toga/shade-widget/raw/main/dist/ShadeWidget.apk).)
2. Open the downloaded file on your phone. If asked, allow installs from your browser or Files app, then tap Install. If Play Protect warns about an unknown app, choose to install anyway.
3. Open Shade Widget and tap "Open notification access", then turn it on.
4. If that switch is greyed out: go back, tap "Open App info", tap the ⋮ menu in the top right, choose "Allow restricted settings", confirm, then repeat step 3.
5. Tap "Add widget to home screen". Long-press the widget to resize it.

To update, install the newer APK over the old one. Your settings and widget stay in place.

## What it does

- Shows every notification in the shade, in the shade's order: calls, conversations, alerting, then silent.
- Groups show as one card with a count and a chevron. Tap the chevron to expand or collapse; tap the group header to open the app.
- Pixel's Promotions, News, Social and Recommendations bundles are grouped the same way.
- Tapping a notification opens it. If the shade would clear it on tap, the widget clears it too.
- Material You colors from your wallpaper, a see-through background, and automatic light and dark mode.
- Optional delete buttons: turn on "Show delete buttons" in the app, and every notification that can be cleared shows an X: standalone ones right away, ones inside a group when the group is expanded. Deleting removes it from the shade too, and deleting the last one removes the group.

## What it doesn't do

- Music and video players aren't shown. Android puts them in Quick Settings rather than the notification list.
- Android hides one-time passcodes from third-party apps, so 2FA codes show as hidden.
- Notifications with fully custom layouts show as plain title and text.
- No action buttons (Reply, Archive...) from the widget.

## Privacy

The app reads your notifications only to draw the widget. It has no internet permission, so nothing leaves your phone. Only install the APK from this page or from someone you trust.

## Building from source

`build.sh` builds the APK with `aapt`, `javac`, `dx`, `zipalign` and `apksigner`. It expects Android platform jars at `/opt/asdk/android-34.jar` (resource linking) and `/opt/asdk/android-36.jar` (compiling), and a signing keystore at `release.keystore`, which is not included in this repository.
