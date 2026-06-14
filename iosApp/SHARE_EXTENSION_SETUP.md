# iOS Share Extension — wiring steps

"Share to Jetzy" from Photos / Files / Safari, the iOS counterpart of Android's share-sheet intake.

The **code is written** (`iosApp/ShareExtension/*` + the Kotlin intake in `SharedInbox.kt`, already
wired into app launch + foreground). What's left is Xcode/Apple-account plumbing that can only be
done on your machine. ~10 minutes.

## 1. Create the target
Xcode → **File ▸ New ▸ Target… ▸ Share Extension**.
- Product Name: **ShareExtension**
- Embed in: **iosApp**
- When prompted "Activate scheme?", **Cancel** (keep the app scheme).

Xcode generates a `ShareExtension/` group with a `ShareViewController.swift`, `Info.plist`, and
maybe a `MainInterface.storyboard`.

## 2. Replace the generated files with the ones in this repo
- Delete Xcode's generated `ShareViewController.swift` and `MainInterface.storyboard`.
- Add the repo files to the **ShareExtension** target:
  `iosApp/ShareExtension/ShareViewController.swift`, `Info.plist`, `ShareExtension.entitlements`.
  (In Xcode, select the file → File Inspector → Target Membership = ShareExtension.)
- In the target's **Build Settings**, set **Info.plist File** to `ShareExtension/Info.plist` and
  remove any `UIKit Main Storyboard` / `NSExtensionMainStoryboard` reference (this is a no-UI
  extension driven by `NSExtensionPrincipalClass`).
- Set the extension's **iOS Deployment Target** to **16.0** and pick your Team under
  Signing & Capabilities.

## 3. App Group on BOTH targets
The app and the extension share files through an App Group. They must use the **same** id, and it
must match `SharedInbox.APP_GROUP` (`group.com.yuroyami.jetzy`) and the entitlements files.

For **iosApp** AND **ShareExtension** targets → Signing & Capabilities → **+ Capability ▸ App
Groups** → add `group.com.yuroyami.jetzy`.
(This registers the group on your Apple Developer account / provisioning profile.)

Confirm the extension uses `ShareExtension/ShareExtension.entitlements` (Build Settings ▸ Code
Signing Entitlements). Confirm the app's own entitlements file also lists the group.

> If you change the group id, change it in **all four** places: both targets' entitlements,
> `SharedInbox.APP_GROUP`, and `ShareViewController.appGroup`.

## 4. (Optional) instant-open URL scheme
Without this, a shared file is picked up the next time you open Jetzy. With it, the extension brings
Jetzy to the foreground immediately.

Main app **Info.plist** → add a URL type with scheme `jetzy`:
```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array><string>jetzy</string></array>
  </dict>
</array>
```

## 5. Build & test (on device — the App Group container is real only on a device/simulator)
1. `cd iosApp && pod install` (re-link the shared framework into the new target if needed).
2. Run the app once (so it's installed), then Photos ▸ select a photo ▸ Share ▸ **Jetzy**.
3. Reopen Jetzy → the photo should be staged in the send tray.

## How it works
```
Share sheet ──▶ ShareViewController ──▶ App Group/Inbox/<uuid>-name ──▶ (app foreground)
                                                                          SharedInbox.drainInto()
                                                                          ──▶ copied to tmp,
                                                                              staged in tray
```

## Notes / follow-ups
- Plain-text-only shares (no file) aren't handled yet — `loadFileRepresentation(UTType.item)` covers
  files/images/videos/PDFs. Add a `UTType.plainText` branch if you want text shares too.
- The kmpssot plugin syncs version/icons, not targets — adding this target won't be clobbered, but
  re-run `pod install` after creating it.
