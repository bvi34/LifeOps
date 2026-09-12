# Releasing

The suite ships as one sideloaded APK. There is no Play Store in the loop, so the whole path from a
commit to a phone is:

```
git tag v1.4.2 && git push --tags
        │
        ▼
GitHub Actions  (.github/workflows/release.yml)
        │
        ├── test          ./gradlew test           — every module's JVM tests
        ├── build         ./gradlew :app:assembleRelease   — native llama.cpp backend included
        ├── sign          the release keystore, from repository secrets
        └── release       a GitHub Release on the tag
                │
                ▼
          release.apk
                │
                ▼
      Settings → Updates, on the phone
```

Everything below is set up once. After that, cutting a release is one `git tag`.

---

## The tag is the version

Nothing in the repository records a version number. `app/build.gradle.kts` reads the tag through
`-Plifeops.versionName`, and derives Android's `versionCode` from it as
`major × 1,000,000 + minor × 1,000 + patch` — so `v1.4.2` becomes versionName `1.4.2` and
versionCode `1004002`. That is monotonic without any state kept between builds, which a CI run
number is not: re-running a job, or tagging an older commit, would hand out codes in the wrong
order and Android would start refusing upgrades.

The tag must be exactly `vMAJOR.MINOR.PATCH`. The workflow rejects anything else before it builds,
because an unparseable tag produces an APK the in-app updater will then quietly refuse to offer.

A build with no tag — anyone's local `./gradlew assembleDebug` — is `0.0.0-dev`, versionCode 1.
That is deliberately below every real release, so a hand-built APK is always upgradeable.

## One-time setup

### 1. Make a signing keystore

Android identifies an app by its **signature**, not its name. Every release must be signed with the
*same* key, forever: an APK signed with a different key will not install over the existing one, it
can only be installed after uninstalling — which deletes all the suite's data. So this file is worth
backing up somewhere you will still have in five years.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias operations \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass 'CHOOSE-A-STRONG-PASSWORD' \
  -keypass  'THE-SAME-PASSWORD-AGAIN'
```

**Use the same password for `-storepass` and `-keypass`.** Modern `keytool` writes a PKCS12
keystore, and PKCS12 has no separate key password — give it two different ones and the build fails
much later with `Get Key failed: Given final block not properly padded`, which says nothing about
the real cause.

Then base64 it, since a repository secret holds text and this is a binary file:

```bash
base64 -w 0 release.jks > release.jks.base64   # macOS: base64 -i release.jks -o release.jks.base64
```

### 2. Add four repository secrets

*Settings → Secrets and variables → Actions → New repository secret*:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the contents of `release.jks.base64` |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | `operations` |
| `RELEASE_KEY_PASSWORD` | the key password (the same one) |

Keep `release.jks` and its password somewhere outside the repository. Losing them means never being
able to update an installed copy of the suite again.

The workflow fails loudly rather than quietly if these are missing or wrong: it checks that
`app-release.apk` exists (an unmatched signing config produces `app-release-unsigned.apk` instead
and the build otherwise *succeeds*), and runs `apksigner verify` on it before publishing.

### 3. Give the phone a GitHub token

**Only because this repository is private.** A private repository's releases are a `404` to an
anonymous request — indistinguishable, from the app's side, from a repository with no releases — so
without a token the in-app updater cannot see anything at all.

Make a **fine-grained** token at *github.com/settings/tokens*:

- **Repository access:** only `bvi34/LifeOps`
- **Permissions:** `Contents: Read-only`
- Nothing else.

Then on the phone: *Operations Sandbox → Settings → Updates → GitHub access*, paste it, Save. It is
stored keystore-encrypted on the device and sent only to `api.github.com`.

It is deliberately **not** baked into the APK. An APK is a file that gets copied around, and a
credential inside one is a credential published.

> If the repository is ever made public, remove the token — the updater works without one, and the
> download switches back to the plain public URL.

## Cutting a release

```bash
git tag v1.4.2
git push origin v1.4.2
```

That is the whole process. The workflow then:

1. checks the tag looks like a version;
2. runs every module's JVM tests — **a tag that fails them never becomes a release**;
3. builds the signed release APK, *including* Advisor's native llama.cpp backend, so the model on
   the phone is the real one rather than the placeholder engine;
4. verifies the APK is genuinely signed;
5. publishes a GitHub Release carrying `release.apk` and its `.sha256`, with notes generated from
   the commits since the previous tag.

Expect roughly 20–35 minutes, nearly all of it the native cross-compile.

To rebuild a tag that already exists — or to skip the native backend when it is broken and a
release is needed anyway — use *Actions → Release → Run workflow*, which takes the tag and a
`native_llm` toggle.

## Getting it onto the phone

**The normal way.** Open the suite, and if a newer release exists a line appears under the clock on
the home screen. Tap it, then *Download*, then *Install*. The first install will ask you to allow
Operations Sandbox to install apps — that is Android's per-app "install unknown apps" setting, which
cannot be granted from inside the app.

The suite checks at launch, at most once every six hours, and only ever *tells* you: it never
downloads and never installs on its own. That check can be switched off entirely on the Updates tab.

**The manual way.** Open the release page on the phone and tap `release.apk`.

Installing over the top keeps all the suite's data. It only works because every release is signed
with the same key — see above.

## CI

`.github/workflows/ci.yml` runs on every push and pull request, and is deliberately *not* the same
build as a release: it switches the native backend off, because that is a 10–20 minute arm64
cross-compile that no Kotlin change can break. A separate `native` job does build it — on pull
requests, on the default branch, and on request — so a change that breaks the NDK build is caught
before a tag is pushed rather than during a release.

Both workflows pin the NDK version (`NDK_VERSION`, passed to Gradle as `-Padvisor.ndkVersion`)
rather than taking whatever the runner image ships, because that default changes without warning and
llama.cpp is exactly the kind of code that notices. Bump it in both files at once.

## When the app finds no update

*Settings → Updates → Check for updates* reports what it actually established, and the message
distinguishes cases that look identical from the phone but have opposite fixes.

**"Nothing to install — the newest release has no APK attached."** The release exists; nothing was
built for it. Almost always this means the tag wasn't `vMAJOR.MINOR.PATCH`, so the release workflow
either rejected it or — before this was fixed — never started at all. Check the Actions tab: a tag
of the wrong shape now produces a failed run whose error says what to re-tag, rather than nothing.
The remedy is to re-tag the same commit and delete the APK-less release, since the updater looks at
`releases/latest` and a hand-made release stays newest until it goes:

```bash
git tag v0.1.0 0.1^{commit}
git push origin v0.1.0
# then delete the old release and its tag on GitHub
```

A release made by hand in the browser never has an APK on it. GitHub's "Source code (zip/tar.gz)"
are not release assets — they are generated on demand, they are not `release.apk`, and the updater
correctly ignores them.

**"Couldn't read the releases — GitHub answered 404/401/403."** This, and only this, is the case an
access token fixes: the releases aren't visible to the request. It means the repository is private
and no token is saved, or the saved token has expired or lost `Contents: Read-only` on this
repository. A token cannot conjure an APK onto a release that has none, which is why the two
messages are kept apart — the updater used to print this one for both, and it sent people off to
mint credentials for a problem that lived in CI.

**"Nothing published yet."** The releases are readable and there are none. Push a tag.

A local `./gradlew assembleDebug` build calls itself `0.0.0-dev`, which is below every real release,
so a dev build always has an update available to it once one is actually published.
