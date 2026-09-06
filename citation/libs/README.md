# Vendored libraries

## `sherpa-onnx-jvm-1.13.7.jar` (Apache-2.0)

The Java API for [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), which runs Citation's
on-device neural voice. Committed rather than fetched because it is 188 KB and because a failure to
reach a host must never be a failure to compile — the *native* libraries it calls into are the large
part, and those are fetched at build time by `fetchNeuralVoiceRuntime` (see `build.gradle.kts`),
pinned by version and SHA-256.

The same jar serves the JVM and Android: its loader checks for Android and falls through to
`System.loadLibrary("sherpa-onnx-jni")`, which resolves against the fetched `jniLibs`.

**Licensing:** sherpa-onnx itself is Apache-2.0, but the text-to-phoneme path it uses for Piper
voices is piper-phonemize over **espeak-ng, which is GPLv3** — as is the pronunciation data in
`src/main/assets/espeak-ng-data`. For a personal build that is nothing to act on; distributing the
APK would carry GPLv3's obligations.

Upgrading: bump `sherpaVersion` in `citation/build.gradle.kts`, replace this jar from
`https://huggingface.co/csukuangfj2/sherpa-onnx-libs/resolve/main/jni/<version>/sherpa-onnx-jvm-<version>.jar`,
and update `sherpaSha256` to the SHA-256 of the matching `-android.tar.bz2`.
