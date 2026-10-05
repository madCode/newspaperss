# Releasing

## Once: the signing key

Release builds are signed with your own key. Make one (keep a copy
somewhere safe outside GitHub: losing it means Play can't take updates
from you), then add four secrets in GitHub › Settings › Secrets and
variables › Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the `.jks` file, base64: `base64 -i release.jks` |
| `KEYSTORE_PASSWORD` | the keystore's password |
| `KEY_ALIAS` | the key's alias |
| `KEY_PASSWORD` | the key's password |

They're the same names dailylog uses.

## Each release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set
   `versionName` (e.g. `0.2.0`).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: what
   changed, in a few lines (500 characters at most). F-Droid and the GitHub
   release both show it.
3. Merge to main, then tag that commit and push the tag:
   `git tag v0.2.0 && git push origin v0.2.0`.
4. The **Release** workflow checks the tag matches `versionName`, builds the
   signed APK and the AAB, and attaches them to a **draft** GitHub release.
   Try the APK on a phone, then publish the draft.

Before the first release, install a release build on a real phone and go
through onboarding, an edition and a send: CI only tests the debug build.

## F-Droid

F-Droid builds the app itself from the tag and signs it with its own key.

- **First time:** open a merge request to
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
  `metadata/com.app.newspaperss.yml`, from the draft in
  [docs/fdroid/](fdroid/com.app.newspaperss.yml). Its reviewers check that it
  builds; they may ask for changes (for example if their build server
  doesn't yet support compileSdk 37).
- **After that:** each new `v*` tag is picked up on its own. The
  `latest-debug` tag is ignored, as the metadata only checks `v` tags.

The store text and screenshots come from `fastlane/metadata/android/en-US/`.

## Google Play

- **The account:** a new personal developer account must run a closed test
  with at least 12 testers for 14 days before it can publish to everyone.
  Start this early.
- **Signing:** turn on Play App Signing and upload the AAB from the draft
  release. The key in the secrets above becomes your upload key.
- **The listing:** the same text and screenshots as `fastlane/`, the icon at
  `fastlane/metadata/android/en-US/images/icon.png`, and the 1024×500 feature
  graphic at `images/featureGraphic.png` beside it. Its source is
  [docs/store/featureGraphic.html](store/featureGraphic.html): open it in a
  browser at 1024×500 and screenshot it to change it.
- **Forms:** Data safety (nothing collected or shared), the content rating
  questionnaire, the target audience, and the privacy policy:
  https://github.com/madCode/newspaperss/blob/main/docs/PRIVACY.md
