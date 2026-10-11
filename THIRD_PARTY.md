# Third-party dependencies and notices

BetterDesk's own source and documentation use the [MIT license](LICENSE). That
license does not replace the terms for bundled runtimes, libraries or transitive
native dependencies. This is a maintained inventory of major dependencies, not a
completed SBOM or a claim that redistribution has been fully audited.

## Major dependency inventory

Versions below distinguish source declarations from inspected 0.5.0 Windows
package contents. Use Gradle's resolved dependency reports before each release;
transitive versions can differ from the plugin version.

| Component | Declared / observed version | Use and upstream license reference |
| --- | --- | --- |
| Kotlin | Plugin 2.1.20; packaged Windows stdlib 2.1.21 | Language/runtime; [Apache 2.0](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt) |
| Compose Multiplatform / Material 3 | 1.8.2 | Shared UI; [Apache 2.0](https://github.com/JetBrains/compose-multiplatform/blob/v1.8.2/LICENSE.txt) |
| Kotlin coroutines / serialization | 1.10.2 / 1.8.1 | Async work/JSON; upstream LICENSE files in their artifacts/source |
| AndroidX activity/core | 1.10.1 / 1.16.0 | Native integration; inspect AAR license/notice metadata alongside [AOSP licensing policy](https://source.android.com/docs/setup/contribute/licenses) |
| Bouncy Castle bcpkix | 1.80; packaged bcprov/bcutil 1.80.2 | J-PAKE and certificates; [Bouncy Castle license](https://www.bouncycastle.org/licence.html), interpreted by upstream as MIT |
| JNA / JNA Platform | 5.16.0 | Windows native APIs; [Apache 2.0 or LGPL 2.1-or-later](https://github.com/java-native-access/jna) |
| Skiko | Packaged 0.9.4.2 | Compose native rendering; [Apache 2.0 bindings](https://github.com/JetBrains/skiko/blob/master/LICENSE), plus Skia/native dependency notices |
| JavaCPP / FFmpeg presets | 1.5.14 | Native decoder bindings; bundled dual-license text permits Apache 2.0 or GPL with Classpath exception |
| FFmpeg | 8.1.2-1.5.14 `windows-x86_64` | Windows AVC decode; selected native variant and source obligations below |
| OpenJDK runtime | Local build uses JDK 17 | Embedded Windows runtime; vendor legal directory and [Microsoft OpenJDK licensing](https://learn.microsoft.com/java/openjdk/faq) (GPLv2 with Classpath exception plus component notices) |

The Gradle wrapper (8.14.3), Android Gradle Plugin (8.11.0), SDK and local .NET
compiler are build tools, not all shipped as app components. JUnit, Compose UI test,
AndroidX test runner/core/ext-junit and UI Automator belong to test configurations.
Review test distribution separately if publishing test APKs; they are currently
local-only. This inventory does not enumerate every Compose, AndroidX, Skia, ICU,
font/shaping or native codec transitive dependency.

## Native video dependencies

BetterDesk's own source remains MIT licensed. The Windows build now dynamically loads
FFmpeg 8.1.2 through JavaCPP/JavaCPP Presets 1.5.14 for AVC decoding and pixel conversion.
Gradle selects the ordinary `windows-x86_64` artifact, **not** `windows-x86_64-gpl`.
The ordinary FFmpeg distribution is LGPL v3; JavaCPP bindings are Apache 2.0.

- [FFmpeg source 8.1.2](https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.bz2)
- [Exact native build recipe and patches](https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg)
- [JavaCPP source](https://github.com/bytedeco/javacpp/tree/1.5.14)
- [JavaCPP Presets source](https://github.com/bytedeco/javacpp-presets/tree/1.5.14)

The license texts are included under `licenses/` in the application JAR and in
`desktop/src/main/resources/licenses/`. Native dependency JARs retain their upstream
notices. No changes are made to FFmpeg itself. The LGPL's incorporated GPL text is
included as well; its presence does not mean the selected binary is the GPL variant.

You may replace or rebuild these shared libraries, and reverse-engineer the application
to debug modifications to them, as permitted by the LGPL. The portable launcher expands
the dependency JARs beside the app in its local runtime cache. A reproducible route is to
replace the Gradle dependencies with your rebuilt compatible JARs and run the local
build script. Keep modified libraries in a separate build/cache; do not overwrite a
running process's native libraries. Consumer redistribution must also provide the
corresponding dependency source in a manner satisfying its license.

Android uses the operating system's MediaCodec implementation. No FFmpeg Android
binaries are packaged. The codec capability is negotiated automatically; no new user
setting or permission is introduced.

## Before redistribution

### Experimental WebRTC dependencies

The explicit `-Pbetterdesk.rtcProof=true` development configuration adds desktop
`dev.onvoid.webrtc:webrtc-java:0.19.0` and Android
`io.github.webrtc-sdk:android:150.7871.01`. Normal app builds do not resolve or
package them. Desktop bindings use [Apache 2.0](https://github.com/devopvoid/webrtc-java/blob/v0.19.0/LICENSE);
the Android distribution wrapper uses [MIT](https://github.com/webrtc-sdk/android/blob/v150.7871.01/LICENSE).
Both native artifacts embed WebRTC and additional native components with their
own terms; the wrapper license alone does not cover them. Inspect the Android
[upstream native notices](https://github.com/webrtc-sdk/android/tree/v150.7871.01/Licenses)
and desktop native source/build notices before redistribution.

The [proof inventory](experimental/README.md) records inspected artifact sizes,
hashes, ABI coverage and remaining gaps. No experimental package is being released.
Consolidated notices, matching native build recipes/source provenance and final
artifact inspection remain requirements before these become production dependencies.

### Distribution checklist

1. Export the exact resolved graphs using `:desktop:dependencies --configuration
   runtimeClasspath` and `:android:dependencies --configuration debugRuntimeClasspath`
   through the checked Gradle junction. Repeat for the actual release configuration
   when one is introduced. Inspect final JAR/APK/native files, not only direct declarations.
2. Retain copyright, LICENSE and NOTICE files for each shipped dependency and the
   embedded JDK's `runtime/legal/` tree. The current Windows JAR bundles the native
   video summary/texts under `licenses/`; it is not a complete notice bundle for
   all the components in the table.
3. Address Android notice packaging before public release. The current Gradle
   resource exclusions include generic `META-INF/LICENSE*` and `META-INF/NOTICE*`
   to avoid collisions. A complete consolidated notice asset/display is not yet
   implemented; repository links alone do not establish binary compliance.
4. Preserve matching native build recipes, patches, corresponding source and a
   compliant way to replace/relink applicable libraries. Confirm actual FFmpeg
   configuration/license at runtime before changing classifiers or codec options.
   Do not substitute a GPL/nonfree build silently. Consult
   [FFmpeg's upstream legal guidance](https://ffmpeg.org/legal.html).
5. Review codec/patent and jurisdiction-dependent distribution questions for the
   intended release separately. A library's open-source license and a successful
   local build do not settle every distribution obligation.

The experimental dependencies above are excluded from ordinary builds. Public
distribution remains pending the review described in [RELEASING.md](docs/RELEASING.md).
