# Releasing

## Once: the signing key

Release builds are signed with newspaperss's own key, not dailylog's:
the apps share nothing that needs a common key, and separate keys keep a
problem with one from reaching the other. Make one (keep a copy somewhere
safe outside GitHub: losing it means nobody can take updates from you), then
add four secrets in GitHub › Settings › Secrets and
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
   changed, in a few lines (500 characters at most), and the same text as
   `<versionCode>1.txt` to `<versionCode>4.txt` (for version code 2: `2.txt`,
   `21.txt` … `24.txt`). The GitHub release shows the first; F-Droid finds a
   changelog by each APK's own version code (below). The Release workflow
   stops if a copy is missing or different.
3. Merge to main, then tag that commit and push the tag:
   `git tag v0.2.0 && git push origin v0.2.0`.
4. The **Release** workflow checks the tag matches `versionName`, builds the
   signed APKs and the AAB, and attaches them to a **draft** GitHub release.
   Try the arm64 APK on a phone, then publish the draft the same day:
   F-Droid downloads these APKs to check its own build against (below), and
   it can't see a draft.

There's one APK per ABI (arm64-v8a, armeabi-v7a, x86_64, x86), since the
natural voice's native code is about 30 MB for each 64-bit one. Each has
version code `versionCode * 10` plus 1 to 4, in that order: armeabi-v7a,
arm64-v8a, x86, x86_64. A store offers a device the highest code it can
run. The AAB takes the first of these, `versionCode * 10 + 1`, as Android's
build gives a bundle its first output's code. Play makes its own per-device
APKs from it and only needs the code to rise, which it does.

Before the first release, install a release build on a real phone and go
through onboarding, an edition and a send: CI only tests the debug build.

## F-Droid

F-Droid builds the app from the tag, checks its build is byte for byte the
same as the APKs on the GitHub release, and if so publishes those APKs, signed
with your key. This is F-Droid's *reproducible builds* route, and the one
dailylog uses (`com.app.dailylog` in fdroiddata).

- **Why this way:** one signature everywhere. Someone can move between the
  GitHub APK and F-Droid's (or an updater like Obtainium) without
  uninstalling, and F-Droid prefers it. The other route, F-Droid signing with
  its own key, is simpler, but its users could never take a GitHub APK as an
  update, and moving to your key later would make every one of them
  reinstall. So it's decided before the first release.
- **What the draft says for it:**
  - Each build's `binary:` is the GitHub release's APK for that ABI
    (`NewspapeRSS-%v-<abi>.apk`, as the Release workflow names them).
  - `AllowedAPKSigningKeys:` pins your certificate. It's the SHA-256 of the
    signing certificate: public data, not a secret. Read it from the key that
    actually signs releases, the one in the secrets, by running the
    **Signing fingerprint** workflow (Actions › Signing fingerprint › Run
    workflow): it prints the value and publishes nothing. A copy of the
    `.jks` elsewhere could differ, and a mismatch only shows when F-Droid
    rejects a tagged release. Run it again whenever the secrets change. The
    private key and passwords stay in the secrets and your own copy; nothing
    else needs them. The draft has the value the workflow printed on 17 Oct
    for the new newspaperss key (`0f1bb6e4…`). If the secrets ever change,
    run it again and update the draft before the next tag.
  - The `sudo:` lines take the build server down to 16 CPU cores. They're
    copied from dailylog, whose 3.1.2 failed to reproduce until they were
    added: some build outputs (the baseline profile, `assets/dexopt/`) can
    depend on how many cores made them, and this app ships one too.
- **Checked here:** two release builds of the same commit, one on 4 cores
  and one on 1, each from a fresh checkout with `--no-build-cache
  --rerun-tasks`, came out byte for byte the same for all four ABIs. That
  doesn't prove F-Droid's build server matches GitHub's runner (a different
  JDK or a machine with many more cores could still differ); only F-Droid's
  first build shows that. If one release fails to reproduce, F-Droid skips
  it and its log says which files differ.
- **First time:** open a merge request to
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
  `metadata/com.app.newspaperss.yml`, from the draft in
  [docs/fdroid/](fdroid/com.app.newspaperss.yml), once
  `AllowedAPKSigningKeys` is in and `v0.1.0`'s release is published. Its
  reviewers check that it builds; they may ask for changes (for example if
  their build server doesn't yet support compileSdk 37).
- **After that:** each new `v*` tag is picked up on its own, one build per
  ABI (`VercodeOperation`). The `latest-debug` tag is ignored, as
  `UpdateCheckMode` only matches `v` tags.
- **The draft is kept in the form `fdroid rewritemeta` writes,** so it has no
  comments; fdroiddata's CI rejects anything else. Check it with
  fdroidserver (`pip install fdroidserver`) in a sparse clone of fdroiddata
  (`git sparse-checkout set config`), with the draft in `metadata/`:
  `fdroid lint com.app.newspaperss` and `fdroid rewritemeta com.app.newspaperss`
  should change nothing.
- **Why else it looks as it does:**
  - Four builds, one per ABI, because the app's build splits its APKs; each
    names its APK with `output:`.
  - `NonFreeNet`: the natural voice (Kokoro) downloads from Hugging Face, as
    SherpaTTS, the other sherpa-onnx app on F-Droid, declares.
  - sherpa-onnx comes from JitPack, which F-Droid's scanner allows; SherpaTTS
    uses the same library the same way.

The store text and screenshots come from `fastlane/metadata/android/en-US/`.

## Google Play

- **The account:** a new personal developer account must run a closed test
  with at least 12 testers, opted in *continuously* for 14 days, before it
  can apply to publish to everyone. Start this early. It is the longest part
  of a first release and nothing else waits on it: F-Droid and the GitHub
  release have no such gate, so a tag can ship there while Play's clock runs.

  **The signing choice below happens at the first upload to any track, the
  closed test included** — not at production. So settle signing before
  uploading anything.

  1. **Create app.** The package name is fixed from here on.
  2. **Testing → Closed testing → Create track.** A new track, not the
     default alpha one.
  3. **Add testers through a Google Group**, not an email list. With a list,
     every change to who is testing means editing the release; with a group
     it is a group membership. Each tester needs a Google account.
  4. **Upload the AAB** to that track. Play Console asks about app signing
     here.
  5. **Share the opt-in link.** A tester's 14 days start when they accept it,
     not when they are invited.
  6. After 14 days with 12 still opted in, **apply for production**. Google
     reviews the application, which takes its own time.

  What catches people out is the word *continuously*: one person leaving on
  day 9 puts the count under 12 and that slot's wait starts again, so
  recruit more than 12. Google also weighs whether testers used the app, so
  accounts that never opened it make a weak application. Besides people you
  know with Android phones, reciprocal testing communities exist for exactly
  this requirement — r/androiddev runs recurring testing-swap threads — and
  using them is allowed. Reciprocal means what it says: you join someone
  else's test in return. Worth pairing with a post somewhere the app's
  actual users are (r/rss, r/androidapps), because those testers open it
  more than once, and engagement is part of what the application is judged
  on.

  Play's rules change. Where the Console disagrees with this, the Console is
  right.
- **Signing:** Play App Signing re-signs what Play delivers. By default
  Google makes the app signing key and the key in the secrets above becomes
  only your upload key, so Play's APKs would carry a different signature from
  GitHub's and F-Droid's, and people couldn't move between them without
  reinstalling. **Decided:** use your own app signing key when Play Console
  asks at the first upload, the same key, so Play, GitHub and F-Droid all
  carry one signature. The consequence: the key in the secrets is then the
  app signing key everywhere, not just a Play upload key Google could reset.
  Losing it stays recoverable for Play (Google keeps a copy under Play App
  Signing), but a leak reaches every channel at once. Then upload the AAB
  from the release.
- **The listing:** the same text and screenshots as `fastlane/`, the icon at
  `fastlane/metadata/android/en-US/images/icon.png`, and the 1024×500 feature
  graphic at `images/featureGraphic.png` beside it. Its source is
  [docs/store/featureGraphic.html](store/featureGraphic.html): open it in a
  browser at 1024×500 and screenshot it to change it.
- **Forms:** Data safety (nothing collected or shared), the content rating
  questionnaire, the target audience, and the privacy policy:
  https://seafloor-studio.github.io/newspaperss/privacy/. That page is a copy
  of [PRIVACY.md](PRIVACY.md), kept in
  [seafloor-studio.github.io](https://github.com/Seafloor-Studio/seafloor-studio.github.io):
  a change to one goes in the other.
