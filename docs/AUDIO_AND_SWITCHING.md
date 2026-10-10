# Device sound and switching screens

Version 0.6.0 adds device playback audio and an attended direction switch on the
existing encrypted connection. Both endpoints must use this version's media
protocol (version 2, retained in version 3 for 0.7.0); update Windows and Android together. The Railway broker API
stays version 1 because it forwards opaque bytes.

## Sound

The local sharing approval covers screen, device playback and supported control.
The microphone is never opened. A viewer can mute/unmute received sound; muting
flushes local playback without disconnecting video. Capture denial/unavailability
keeps a view-only or controlled video session usable and is shown in the UI.

Windows captures the default multimedia **render** endpoint through WASAPI loopback,
using shared mode and the native mix format. Native buffers are converted to
48 kHz, signed 16-bit, stereo PCM. Playback uses the bundled JVM's native Java Sound
output. Protected audio, a missing/default-device change, exclusive-mode software
or audio driver behavior can prevent capture. See
[Microsoft's loopback documentation](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording).

Android 10/API 29+ uses AudioPlaybackCapture attached to the current MediaProjection,
selecting media/game/unknown playback usage and excluding BetterDesk's own UID.
It requires `RECORD_AUDIO` even though no microphone AudioSource is configured.
The native permission dialog can describe this as microphone permission; in-app
copy clarifies that the microphone stays off. Denying it does not block the screen.
Android 8/9 remain screen-only sources. Android playback uses AudioTrack with a
bounded buffer, and disables capture of its own received playback.

Android captures only eligible apps in the same user profile whose capture policy
allows it. Protected media, calls, some older apps and apps that opt out can remain
silent. Those restrictions are respected, not bypassed. See
[Android playback-capture requirements](https://developer.android.com/media/platform/av-capture).

Each encrypted audio packet holds 20 ms (3,840 bytes) of stereo PCM. Capture and
playback each have at most three queued packets; native playback buffers are
bounded as well. Playback runs off the socket reader so a slow speaker cannot
block frame decoding. Muting, stopping and switching flush/release the old playback;
stale packets from another media epoch cannot reopen it.

PCM costs about 1.536 Mbps before transport overhead. It avoids adding another codec
dependency and preserves the selected 16-bit stereo samples, but is not an adaptive
Internet audio codec. It shares TCP congestion with video; no separate audio route
or sample-accurate audio/video clock synchronization is implemented. Sample-rate
conversion is linear; Windows multichannel sources currently use the front stereo
pair. Full surround downmix, device-change recovery, compression and measured
audio-to-video synchronization remain improvements. Do not promise every app's sound
or a fixed latency on every network.

## Request and approval

**Switch screens** appears on the sharing screen and directly on the viewer. The
requester sees a waiting state with Cancel switch. The other device sees an inline
request explaining whose screen will be shared, with Allow switch and Decline.
Requests expire after 30 seconds. While a request is pending, remote input is
disabled so the connected person cannot influence the approval. Decline/cancel
before acceptance leaves screen/audio flowing in the original direction.

Requesting a switch is the requester's consent to the described reversal; the
other device must also approve. The device becoming the source still passes its
own control/capture permission flow. A previous Android accessibility grant does
not replace a fresh MediaProjection approval. The same session works in both
directions without copying another code or opening a link.

## Handoff protocol

1. `switchRequest` carries a random request token and the current media epoch.
2. `switchAccepted` echoes the token only after local approval. Decline, cancellation
   or expiry sends `switchCancel`. Concurrent requests reject each other safely.
3. Each endpoint disables input, invalidates old callbacks, stops native capture/
   playback and waits for its media writers to finish. It sends `switchStopped`
   with the token, next epoch, its receive codec and audio capability.
4. Only after both stop markers does each side flip its viewer/source role and
   reset frame/audio counters. TLS server/client identities do not change.
5. The new source obtains any required OS permissions, then sends `ready` and
   fresh media. Old-epoch packets and queued input cannot cross the handoff.

The socket reader and heartbeats stay alive during handoff/permission prompts.
There is no unbounded drain: a writer that cannot stop within five seconds fails
the session. Once handoff starts, denial, disconnect or protocol/transport failure
closes access rather than silently restoring capture. A cancel racing acceptance
may therefore end the session; it must never authorize a new source.

Implementation: [HelpSession](../shared/src/main/kotlin/app/betterdesk/core/HelpSession.kt),
[Wire](../shared/src/main/kotlin/app/betterdesk/core/Wire.kt),
[Windows audio](../desktop/src/main/kotlin/app/betterdesk/desktop/WasapiAudio.kt), and
[Android audio](../android/src/main/kotlin/app/betterdesk/PlaybackAudio.kt).
See [TESTING.md](TESTING.md) and dated [VERIFICATION.md](VERIFICATION.md) for what has
actually been measured and exercised.
