# Releasing Dotwave

> **Read this first: the Nothing SDK licence limits what you can publish.**
> Nothing's `LICENSE.md` for the Glyph Matrix SDK does not allow redistributing the SDK (clause 2.1(c)) and requires
> written permission for commercial use (clause 2.2). A prebuilt APK contains the SDK, so publishing one may count as
> redistribution. This is not legal advice. Ask Nothing (`GDKsupport@nothing.tech`) before you publish an APK or an
> app-store listing, and keep the app free, with no ads or payments, unless they say otherwise.
> The MIT licence in this repo covers **your code only**.

There are three levels. Start with A; do B or C only after you have an answer from Nothing.

## A. Source-only release (safe, do this first)

No SDK is shipped, so nothing in the licence is touched. People build it themselves (see the README install steps).

1. Bump the version in `app/build.gradle.kts`: increase `versionCode` by 1 and set `versionName` (e.g. `1.0.0`).
2. Merge your branch into `main` and make sure the README and LICENSE are on `main`.
3. Tag and push:
   ```
   git tag -a v1.0.0 -m "Dotwave 1.0.0"
   git push origin v1.0.0
   ```
4. On GitHub: **Releases > Draft a new release**, pick the tag, click **Generate release notes**, and add a short "what's new"
   plus a "how to build" link to the README. Tick **Set as the latest release**. No files to attach.
5. Make sure the repository is **public** (Settings > General > Danger zone > Change visibility) and add topics such as
   `nothing-phone`, `glyph-matrix`, `android`, `kotlin`, `jetpack-compose`.

## B. Prebuilt, signed APK on GitHub Releases (needs Nothing's OK)

1. **Get written permission** from Nothing (see the box above) and keep the reply.
2. Create a signing key once and **back it up somewhere safe** (lose it and you can never update the app for existing users):
   ```
   keytool -genkeypair -v -keystore dotwave-release.jks -alias dotwave -keyalg RSA -keysize 2048 -validity 10000
   ```
3. Create `keystore.properties` in the project root (it is git-ignored, never commit it):
   ```
   storeFile=dotwave-release.jks
   storePassword=...
   keyAlias=dotwave
   keyPassword=...
   ```
4. Put `glyph-matrix-sdk-2.0.aar` in `app/libs/`, bump the version, then build:
   ```
   ./gradlew clean assembleRelease
   ```
   Output: `app/build/outputs/apk/release/app-release.apk` (signed automatically when `keystore.properties` exists).
5. Verify and test it on your phone **as a release build** (minify is on, so test every screen, the toy, music and the widget):
   ```
   $ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app-release.apk
   adb install -r app-release.apk
   ```
6. Create a checksum and attach both files to the GitHub release:
   ```
   sha256sum app-release.apk > app-release.apk.sha256
   ```
   Rename the APK to something clear, e.g. `Dotwave-1.0.0.apk`. In the notes say which phone and Nothing OS build you tested on.

## C. Google Play (needs Nothing's OK, plus extra work)

- **Change the application id first.** Play rejects `com.example.*`. In `app/build.gradle.kts` set `applicationId` to something
  you own (e.g. `io.github.ak120.dotwave`). `namespace` can stay as it is. Note: a new id is a new app, so existing testers must reinstall.
- Google Play developer account (one-time fee), then **App bundle**: `./gradlew bundleRelease` and upload the `.aab`
  (use Play App Signing).
- **Privacy policy URL** (required): say plainly that the app reads the title and artist of the playing media session and
  that notification access is only used to detect that a notification arrived and the playing song, nothing is stored or sent anywhere.
- **Declarations:** foreground service type `specialUse` (explain why in the form), notification-listener / media-session
  access, and the data-safety form (no data collected). Expect extra review for sensitive permissions.
- Set the target audience, content rating, store listing text and screenshots, then start with **Internal testing > Closed testing** before production.
- Because Nothing's SDK licence forbids commercial use without permission, keep it free and ask Nothing first.

## Release checklist
- [ ] Version bumped (`versionCode` and `versionName`)
- [ ] Tested on a real phone, release build
- [ ] README, LICENSE and credits up to date
- [ ] No keys, passwords or `.aar` committed (`git status` is clean of them)
- [ ] Tag pushed, GitHub release written
- [ ] Posted to the community (Reddit r/NothingTech, Nothing Community forum) with a short video

## After release
Watch the GitHub issues, and tag anything that needs a specific Nothing OS build or phone model. Re-test whenever Nothing OS or the SDK updates.
