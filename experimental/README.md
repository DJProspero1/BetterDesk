# Native direct-connection feasibility proof

This is the first implementation work for [the direct connection plan](../docs/plans/DIRECT_CONNECTIONS.md).
It is **not enabled in either shipped app** and does not implement direct screen,
sound, input or files. The production session still uses LAN/Railway pinned TLS.

Explicitly pass `-Pbetterdesk.rtcProof=true` to compile these sources and resolve
their dependencies. Ordinary builds exclude both the sources and WebRTC artifacts.
Do not package or publish an experimental build as a completed feature.

## Integration decision

Use separate native adapters behind shared Kotlin boundaries:

- Desktop: `dev.onvoid.webrtc:webrtc-java:0.19.0`, native `windows-x86_64`.
  The Java source and Windows JNI artifact were inspected from Maven Central.
  A Linux JNI artifact is selected **only for local proof tests**, not the Windows
  application runtime. Windows x64 remains the app's supported desktop target.
- Android: `io.github.webrtc-sdk:android:150.7871.01`, using `org.webrtc` APIs.
  The AAR includes `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` JNI libraries.
  Android remains minSdk 26 / targetSdk 36; actual device compatibility is unverified.

Binding source tags resolve to desktop commit
`d2b5a8ad7ec407417bc455c668f4efbf8144480e` and Android distribution commit
`7b6390fb098303b31af76906bf15f7decaa4ef95`. The exact embedded WebRTC source
commit/reproducible native provenance still needs verification. The desktop JNI
[tagged Maven build](https://github.com/devopvoid/webrtc-java/blob/v0.19.0/webrtc-jni/pom.xml)
selects `branch-heads/7977`; a branch name alone does not attest the source commit
used for the downloaded binary.

These prebuilt native dependencies avoid building the large upstream WebRTC tree.
They are third-party builds, not Google-provided binary distributions. Local app
compilation/tests stay on the local host. No cloud native builds were added.
Do not assume that desktop/Android engine versions imply codec or wire compatibility;
prove interoperability through adapters before enabling the feature.

Downloaded artifacts inspected on 10 October 2026:

| Artifact | Download bytes | SHA-256 |
| --- | ---: | --- |
| webrtc-java-0.19.0.jar | 132671 | `90e3ca56d2a11a474e07125fc07622a4f6be8f25f8cddab273d911739093ccdb` |
| webrtc-java-0.19.0-sources.jar | 165688 | `7887b36ae2dbe8a654d0fff3f6e4ba5a0d3eb9afc07d6cfc7bb627c1b4a38e0e` |
| webrtc-java-0.19.0-windows-x86_64.jar | 8780111 | `04ed89a0ed1faf8a7888ae02fcc1c7e9474311f5136e27e511b237b7aec9f82e` |
| webrtc-java-0.19.0-linux-x86_64.jar | 9876852 | `0195ae27572722906c8eccb466cd1c02ed22a1209925d69d009beda24b1004f4` |
| android-150.7871.01.aar | 49147033 | `0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0` |

The Android uncompressed native libraries total about 48 MB across four ABIs.
The local proof APK built on 10 October is 62,080,171 bytes; the native Windows
DLL expands to 21,750,784 bytes. Final portable EXE size remains unmeasured.
Inspection of DLL imports found Windows system libraries, including Media Foundation,
DXGI and D3D11; this is static evidence, not native Windows load/packaging coverage. These observed hashes identify the
inspected downloads; Gradle dependency verification is not yet configured and
they are not a claim of an audited native supply chain.

Upstream references: [desktop source/tag](https://github.com/devopvoid/webrtc-java/tree/v0.19.0),
[desktop artifact](https://repo.maven.apache.org/maven2/dev/onvoid/webrtc/webrtc-java/0.19.0/),
[Android artifact](https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.01/),
[Android distribution](https://github.com/webrtc-sdk/android).

## Proof boundary

`RtcPeer` serializes native operations on one worker with at most 64 queued tasks.
A separate deadline worker limits each started proof to 30 seconds and is stopped
on close. One negotiated reliable ordered data channel has a 16 KiB message ceiling,
64 KiB native send-buffer budget and 32 inbound-message capacity. Overflow closes
the proof; it never drops ordered data silently. Callback-owned buffers are copied
before return. Native callbacks enqueue operations rather than making synchronous
calls back into WebRTC. Close rejects outstanding work, unblocks readers and tears
down channel, peer, factory and audio-module resources.

Each peer gathers a complete, bounded SDP once. The proof accepts one application
media section, at most 32 candidates and one SHA-256 DTLS fingerprint. This is a
pre-native-parser bound, **not a production ICE destination policy**. SDP contains
network addresses and credentials and must never be logged or saved. Native debug
logging/tracing is disabled. There are no STUN/TURN requests or external signaling
in this proof: the fixtures use host candidates between their synthetic local peers.

The fixture exchanges the offer/answer through pinned TLS and checks the invitation
secret before accepting the offer. `RtcSignal` also validates protocol, distinct
source/recipient identities, session, generation, fixed expiry and fingerprint.
Its representation is redacted. These context checks do not authenticate an
untrusted channel by themselves. Authenticating the entire SDP binds its DTLS
fingerprint as well as its ICE parameters. This tests the transport bootstrap
boundary; it does not repeat code/J-PAKE pairing or request real capture consent.
Never pass an SDP from an untrusted mailbox to these adapters in production.

Desktop uses a dummy audio module. Android creates no media sources/tracks and
disables peer audio recording/playout. Neither proof opens a camera, microphone,
MediaProjection, accessibility service or a real desktop capture.

## Local commands and limits

With JDK 17, SDK 36 and the infrastructure fixture available, on a local Linux host:

```sh
ANDROID_HOME=/path/to/android-sdk \
BETTERDESK_BROKER_FIXTURE=/path/to/BetterDesk-Infrastructure/scripts/broker-fixture.mjs \
xvfb-run -a bash ./gradlew :desktop:test :android:compileDebugKotlin \
  -Pbetterdesk.rtcProof=true --no-daemon --no-parallel --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

Linux is a local compilation/synthetic-test workaround authorized by the owner
on 10 October 2026. It is not a supported desktop product or Windows packaging host.
The proof test loads real Linux WebRTC JNI and exchanges synthetic bytes; it does
not establish Windows native operation, Android runtime compatibility, physical
device coverage, separate-network direct access or delivered media performance.
The Android adapter still requires device instrumentation/interoperation coverage.
`RtcAndroidProofTest` is included in the test APK only with the same Gradle opt-in;
run it explicitly with the native Android instrumentation runner on a dedicated
disposable emulator/device. For the desktop/Android fixture, build both final APKs
and run the host harness:

```sh
ANDROID_HOME=/path/to/android-sdk BETTERDESK_RTC_ANDROID_SERIAL=emulator-5580 \
BETTERDESK_BROKER_FIXTURE=/path/to/BetterDesk-Infrastructure/scripts/broker-fixture.mjs \
xvfb-run -a bash ./gradlew :android:assembleDebug :android:assembleDebugAndroidTest :desktop:test \
  -Pbetterdesk.rtcProof=true --no-daemon --no-parallel --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

The harness installs without uninstalling, forwards a temporary loopback TLS port
with ADB reverse, streams private configuration into the app's private cache,
then deletes it. It requires the instrumentation success result and checks native
bytes in both directions. Stop closes the listener/peers and removes the ADB
reverse rule. Use a fresh test device: these local debug APKs are not signed with
the previous release key and must not replace an existing user's app or be
published as updates. No signing keys are copied between machines.

Future media integration must retain native capture permission/service ownership.
Desktop has custom video/audio sources, but the raw/encoded video bridge, Windows
hardware encoder, Android decoding/rendering and playback-only audio injection
remain open. No default microphone source is acceptable. Do not send the current
screen/audio TCP stream through this one reliable channel and call media migration
complete. Files need separate bounded bulk scheduling and explicit cutover policy.

The current proof has no connection to `HelpSession` and no UI opt-in. Next work:
production candidate/authentication/version policy, authenticated signaling,
transport readiness/commit and native media bridges. See the
[milestone checklist](../docs/plans/DIRECT_CONNECTIONS_HANDOFF.md).
