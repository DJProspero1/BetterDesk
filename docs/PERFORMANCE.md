# Frame-rate work, updated 9 October 2026

## Target and measurement

Target 60 distinct frames per second during continuous motion. Count frames after
decoding and distinct frame IDs submitted for Compose drawing. Repainting one image
does not count again. A static desktop deliberately transmits fewer frames.
These counters do not measure the monitor's scanout or input-to-photon latency.

All experiments and builds run on the local Windows PC. Native Windows capture uses
an RTX 4070 and a 2560 x 1440 primary display running at 144 Hz; the transmitted image
is 1440 x 810. Android tests use the local API 36 emulator, 1080 x 2400 at 60 Hz,
initially captured at 576 x 1280. The final software-encoder fallback uses 432 x 960;
hardware encoders retain the higher resolution. Loopback/emulator networking is not
a physical Wi-Fi test.

## Changes

- Windows: Desktop Duplication replaces Java screenshots. D3D11 capture and Skia
  conversion run on a dedicated thread. Polling permits up to 120 updates per second;
  no-change and cursor-only notifications do not create duplicate frames. GDI remains
  a slower compatibility fallback for unsupported primary-display configurations.
- Android: MediaProjection can feed a MediaCodec input Surface directly, avoiding
  ImageReader readback and bitmap copies. Windows negotiates this AVC path and
  decodes it with the non-GPL FFmpeg native libraries. Other recipients and unsupported
  encoders retain JPEG. No setting or additional permission is required.
  Software codecs automatically use a 960-pixel long edge to prioritize motion;
  hardware codecs use up to 1280 pixels. This is a quality/performance tradeoff,
  not a claim that reduced-resolution video contains the original detail.
  The encoder requests real-time priority and a 60 FPS operating rate using
  [Android's MediaFormat settings](https://developer.android.com/reference/android/media/MediaFormat#KEY_PRIORITY).
- AVC uses predictive frames, no B-frames, and periodic independent keyframes with
  repeated codec headers. Dropping a predictive frame requests a new keyframe and
  suppresses dependent frames until a keyframe newer than the gap arrives. A queued
  older keyframe cannot incorrectly clear a later gap. Both capture and in-flight
  queues remain bounded; this recovery is required to avoid corrupted images.
- The first capture is retained until local approval has completed. Healthy capture
  heartbeats keep an unchanged image usable; a stalled capture expires remote input.
- Local counters report decoded/drawn FPS, 95th-percentile gaps between draws, and
  encoded payload bandwidth. They upload nothing and contain no screen or input data.
- The receiver publishes decoded images through thread-safe StateFlow rather than
  waiting for the UI dispatcher before acknowledging each frame. Four frames can be
  queued for capture and four can be in flight locally (twelve through the relay).
  A full window waits up to 16 ms for
  an acknowledgement before dropping. Buffers never grow without bound.

## Evidence

- Previous Windows screenshot path: about 24 FPS; GDI experiment: about 29 FPS.
- First Desktop Duplication end-to-end run: 63.24 received FPS, 63.18 drawn FPS,
  and 63.18 distinct encoded images per second over 15 seconds of continuous motion.
- Final 30-second Windows run: 62.57 received/drawn FPS and 62.44 distinct encoded
  images per second. P95 draw gap was 21 ms; JPEG payload averaged 37.96 Mbps.
- Android JPEG end-to-end runs: approximately 29.6–30.5 FPS. Copying averaged
  4.3 ms and JPEG encoding 3.6 ms, but capture also slowed the producing app.
- Emulator animation without sharing: 906 frames in approximately 15.07 seconds.
- Separate MediaProjection-to-AVC experiment: 900 frames in 15.001 seconds,
  59.996 FPS, 4.20 Mbps, using `c2.android.avc.encoder` (software).
  This experiment alone does not establish end-to-end throughput.
- Integrated AVC at 576 x 1280 improved from 40 FPS with all-intra encoding to
  53 FPS with predictive encoding and the four-frame window. Native decoding averaged
  1.58 ms per frame; the producing software encoder was then the main limit.
- At 432 x 960, intermediate emulator runs drew 55.2–57.1 FPS. After requesting
  real-time codec scheduling, the final 30-second run received 59.94 FPS and drew
  59.87 distinct frame IDs/s. P95 draw gap was 24 ms, payload 1.65 Mbps, and native
  decoding averaged 0.87 ms. This is close to nominal 60 Hz, not a measured result
  of at least 60 drawn FPS. This one run does not establish sustained performance
  on physical devices or congested networks.

Final app benchmarks are written to ignored `desktop/build/reports/stream-benchmark.txt`
and `android-render-benchmark.txt`. Release verification summarizes those results.

## Version 0.4 Internet relay measurement, 9 October

The same API 36 emulator streamed its real capture through the public Railway TCP
relay to the Windows decoder/viewer for 30 seconds after warmup. An eight-frame
acknowledgement window drew 48.56 FPS, with P95 draw gap 42 ms. Increasing the bounded
relay window to twelve frames received 57.42 FPS and drew 56.52 FPS, P95 gap 34 ms,
payload 2.11 Mbps and decode time 0.88 ms. The capture queue remains four frames;
direct LAN sessions retain their four-frame window. This reduces the throughput
ceiling imposed by round-trip acknowledgements but can hold more frames in flight.

The software source encoder also remained below nominal 60 FPS and recovery still
discarded predictive frames after gaps. This is a measured improvement, not a 60 FPS
result or a physical-device test. No input-to-photon latency was measured. Evidence
is in `desktop/build/reports/android-relay-performance.txt`; reproduction is in
VERIFICATION.md. Internet quality adaptation remains necessary on weaker links.

## Version 0.6 sound and frame-rate checks

New 30-second API 36 emulator-to-Windows Railway runs with actual playback sound
received/drew 39.41/38.65 FPS (P95 gap 58 ms), then 33.90/33.67 FPS (65 ms).
The video payload was 1.61 and 1.35 Mbps respectively; uncompressed stereo sound
adds about 1.536 Mbps before overhead. These numbers do **not** meet the 60 FPS
target and are lower than the historical video-only 0.4 measurement. Native source
logs also showed roughly 32-44 encoded FPS; receiver decoding remained under 1 ms.
The same-build comparison with capture sound and the fixture tone disabled drew
44.60 FPS (45.16 received, P95 gap 47 ms, video 1.87 Mbps). It was also below the
historical result. The difference suggests audio/emulator overhead deserves further
work, but these sequential runs do not isolate load or network variability as causes.
Do not describe them as a latency improvement or extrapolate them to physical phones.

For a same-build comparison, set `BETTERDESK_COMPARE_VIDEO_ONLY=1` with the opt-in
AndroidRelayDeviceTest capture check. That test-only flag disables audio negotiation
and the fixture tone, and writes `0.6-video-only.txt`; ordinary audio runs write
`0.6-audio-enabled.txt` under `desktop/build/reports/`. The shipped app has no such
environment override. Native sound requires further physical-device, synchronization
and constrained-network measurement. See [VERIFICATION.md](VERIFICATION.md).

## Reproduce locally

Run through the ASCII workspace junction described by `scripts/build-local.ps1`.
The ordinary build does not enable capture benchmarks.

- `BETTERDESK_STREAM_BENCHMARK=1`: run `:desktop:test --tests app.betterdesk.StreamBenchmarkTest`.
  Opens a moving pattern and the actual viewer, captures the primary desktop, and
  measures 30 seconds after warmup. Both sessions/windows close in `finally`.
- Build/install the separate `:motion-fixture:assembleDebug` APK for Android motion.
  Set `BETTERDESK_ANDROID_TEST=1` and `BETTERDESK_MOTION_FIXTURE=1`, then run
  `:desktop:test --tests app.betterdesk.AndroidDeviceTest`. Approve the genuine Android
  connection and screen-sharing prompts. `BETTERDESK_ANDROID_SERIAL` defaults to
  `emulator-5554`. The test verifies app switching, remote disconnect, stopped
  MediaProjection and an unchanged Android process ID. Set
  `BETTERDESK_NOTIFICATION_STOP=1` to instead press the notification's Disconnect
  action after the measurement. Both modes verify the sharing notification is
  present during capture and all app notifications disappear after stopping.
- The separate fixture's `codec=true` launch extra runs an 18-second direct encoder
  experiment after genuine screen-capture consent. It discards output and logs only
  aggregate timing/bandwidth under `BetterDeskCodec`. It is never included in the app.

## Remaining limits

A 60 Hz source has approximately 60 new frames per second available, so “always more
than 60” is not a valid universal promise. Hardware capability, thermal throttling,
display refresh, source-app rendering and network conditions still matter. Measure
both directions on physical devices before promising an average to clients.

Internet-grade adaptive bitrate, GPU display surfaces,
hardware Windows video encoding and input-to-photon latency measurements remain work.
If the link cannot sustain the payload, bounded queues prevent unlimited lag but FPS
will fall. A future adaptation policy should reduce resolution/bitrate before allowing
latency to accumulate. The Internet relay is now integrated; native builds remain local.
