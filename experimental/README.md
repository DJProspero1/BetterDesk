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
The final synthetic-audio/video proof APK built on 10 October is 62,421,310 bytes; the native Windows
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

`RtcControlChannel` adds development-only framing for the existing `Message`
schema on that channel. It must own one authenticated peer, with exactly one
wrapper and no raw traffic during its framed phase. It does not implement
`SessionTransport` or grant input/clipboard permission. Each direction starts at
sequence 1 and requires consecutive messages in the fixed transport generation.
A 32-byte big-endian header contains BDC1, a zero reserved word, generation,
sequence, total JSON bytes and fragment offset. Canonical 8192-byte chunks keep
native messages below 16 KiB; a maximum-sized control message needs two fragments.
Reassembly allocates at most the existing 16 KiB JSON ceiling and admits one
reader. Four writers are admitted, including the active writer; one fair lock keeps
each message's fragments together. Each write/read has one five-second deadline,
including queueing/reassembly. This finite-fixture timeout is not a production
idle policy. The caller's input FIFO must still establish command order before
concurrent producers reach this adapter. Text fields are bounded before JSON encoding.
Overflow, replay, wrong generation, malformed lengths/UTF-8/schema,
incomplete messages and I/O failure close the peer. Serialization errors are
replaced with a content-free exception; interruption is preserved.

The finite control fixture exchanges ordered Unicode input, keyboard, clipboard
barrier/ack, maximum-sized clipboard text and switch/status messages in both
directions after raw/media checks finish. Nothing dispatches them to OS input,
capture, files or a clipboard. Parser/Stop tests and a native replay check cover
rejection and cleanup. The callback enqueue and Stop queue clearing now share a
lock, preventing an incoming callback from refilling the inbox after revocation.
Bulk data, production authorization/freshness, readiness/cutover and paste-barrier
execution remain separate unfinished work.

Each peer gathers a complete, bounded SDP once. Data-only signaling version 1
accepts one application section. Explicit synthetic-video signaling version 2
accepts one video section plus application, with BUNDLE, RTCP mux and matching
SHA-256 DTLS fingerprints/ICE credentials. Explicit synthetic-audio/video signaling version 3 adds one bundled audio section
with the same identity and RTCP mux. All profiles cap SDP at 16 KiB/256 lines and
32 candidates. The selected proof mode must match the authenticated envelope;
version 1 rejects media SDP and version 2 rejects audio or unbundled/different
identities. This is a
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

Data/video-only desktop peers use a dummy audio module; Android disables peer
audio recording/playout in those profiles.
Data-only peers create no media sources. Video peers use `CustomVideoSource` on
desktop and an externally fed screencast `VideoSource` on Android, with explicit
software video encoder/decoder factories. Neither opens a camera, microphone,
MediaProjection, accessibility service or a real desktop capture. Android's source
started/stopped callbacks describe the synthetic producer, never OS permission.

The raw bridge copies bounded I420 planes (even dimensions, at most 640 × 360) and
holds at most two pending frame writes. A full admission budget returns false for
the caller to drop that frame. Accepted frames require increasing timestamps and
age under two seconds; failures close the proof. Native frame references are
released after each push. Decoded callbacks read four synthetic luminance samples
while the native buffer is valid and retain only the latest small sample. They
never retain a native decoded buffer or write pixels to disk. This is not a render
pipeline or input freshness/epoch implementation.

The video fixture sends 96 × 64 grayscale quadrants in both directions, checks
decoded dimensions/orientation/luminance with a bounded lossy tolerance, and
requires nonzero RTP bytes and at least three encoded/decoded frames at each peer.
It preserves the authenticated TLS bootstrap/data checks. Native stats expose only
aggregate video counters; raw reports contain network addresses and must not be
logged. Stop clears pending samples/writes and releases tracks, sources and sinks.
Desktop receiver wrappers are disposed explicitly; Android's peer owns cached
receivers and tracks, so teardown must avoid double-disposal.
The fixture acknowledges each checked frame only to coordinate its finite sample
sequence; this is not RTP pacing or a production frame-ack protocol. Native codec/
jitter-buffer behavior, hardware acceleration, delivered FPS and source/display
timestamp mapping remain unmeasured.

The audio/video profile injects copied 10 ms, 48 kHz mono PCM16 chunks (960 bytes)
with increasing timestamps. Chunks older than 100 ms are rejected for dropping
without closing the peer; future/non-increasing timestamps fail closed. Four pending worker writes
and Android's four-chunk native admission queue bound input; full queues return
false for the producer to drop. Android underflow/stale input becomes silence. The selected fork
does not pace its external recorder loop when hardware recording is disabled;
the callback supplies a monotonic 10 ms clock, without catch-up bursts.
Decoded callbacks retain only the latest non-silent aggregate sample (rate,
channels and peak), never PCM or native buffers. Audio RTP stats expose aggregate
packet/byte counts only. Stop clears audio queues/samples and closes sources,
tracks, modules and the owned Android scheduler. Android disposes local audio
tracks/sources before the factory and releases the ADM after it; retaining local
audio references through factory destruction can block native shutdown.

Desktop `CustomAudioSource` disables device capture in the selected SDK. Audio
proofs use `HeadlessAudioDeviceModule`, which drives a receive playout clock without
opening physical devices; the dummy module did not deliver decoded audio. Android
uses this SDK fork's `AudioBufferCallback` and playback callback, with
`setAudioRecordEnabled(false)` **before factory/track creation**, never re-enabled.
Inspection of the selected AAR confirms that this bypasses hardware AudioRecord
initialization/start while allowing external buffers. Android peer recording here
means feeding external PCM; it does not enable the hardware recorder. Tests require
`RECORD_AUDIO` to remain denied. Native AudioTrack output runs on the dedicated
emulator, launched without audible host output. These APIs are specific to the
selected bindings; do not assume a default microphone source is safe.

The finite synthetic tone checks decoding and RTP in both directions. It does not
capture device playback, establish stereo fidelity or physical sound quality, measure A/V sync or
integrate WASAPI/AudioPlaybackCapture, mute or production media ownership.

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
The proof loads real Linux and Android WebRTC JNI and exchanges synthetic bytes.
On 10 October, the final APK passed Android-only native data/video/audio checks and interoperation
with desktop on an API 36.1/Android 16 emulator; see the exact build hashes and
results in [verification](../docs/VERIFICATION.md). This does not establish Windows
native operation, physical/OEM compatibility, separate-network direct access or
delivered media performance.
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
then deletes it. A failed host run also stops its guest instrumentation process.
It requires the instrumentation success result and checks native
bytes in both directions. Stop closes the listener/peers and removes the ADB
reverse rule. Use a fresh test device: these local debug APKs are not signed with
the previous release key and must not replace an existing user's app or be
published as updates. No signing keys are copied between machines.
The host harness runs three checks (data-only, synthetic video and synthetic audio/video),
installs the same final APKs and checks received pixels, decoded PCM peaks and
aggregate video/audio RTP counters.
Android-only instrumentation methods are `nativeDataChannelAndTeardown` and
`nativeVideoTracksAndTeardown` plus `nativeExternalAudioWithoutMicrophone`; invoke them explicitly on the dedicated test device.
The emulator must finish booting before installation. Android native logging uses
the injected no-op logger alone: combining it with `enableLogToDebugOutput` throws
during initialization. Native ICE callbacks enqueue SDP work only when gathering
is complete. These startup and callback fixes are covered by the final native runs.

Future media integration must retain native capture permission/service ownership.
The synthetic raw-video bridge establishes an SDK path without OS capture. Windows
capture conversion/hardware encoding, Android projection/rendering integration,
production frame-age/epoch mapping and real playback-only audio capture remain open.
No default microphone source is acceptable. Do not send the current
screen/audio TCP stream through this one reliable channel and call media migration
complete. Files need separate bounded bulk scheduling and explicit cutover policy.

The current proof has no connection to `HelpSession` and no UI opt-in. Next work:
production candidate/authentication/version policy, authenticated signaling,
transport readiness/commit and native media bridges. See the
[milestone checklist](../docs/plans/DIRECT_CONNECTIONS_HANDOFF.md).
