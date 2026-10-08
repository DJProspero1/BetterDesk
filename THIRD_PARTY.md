# Native video dependencies

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
