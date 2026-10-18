# Beam

Touch two phones together and the thing on one of them lands on the other.

Samsung S Beam and Android Beam are gone — NDEF push was deprecated in Android 10 and removed
in 14 — so Beam rebuilds that interaction out of the primitives that still exist: **NFC Host
Card Emulation** for the tap, **Nearby Connections** for the bytes. No internet, no account, no
cloud, no analytics.

## How a beam works

```
  sender                              receiver
    |                                    |   HCE service registered in the manifest;
    |                                    |   no app open, no background service
    |  --- SELECT Beam AID ------------> |
    |  --- GET_SESSION ---------------->  |   mint single-use token, start advertising
    |  <-- token (nonce, secret, …) ----  |
    |                                    |
    |  ...discover endpoint "beam:<nonce>"...
    |  --- AUTH(proof ‖ id) ----------->  |   proof = HMAC(secret, nonce ‖ connection digits)
    |  <-- AUTH_OK --------------------   |
    |  --- MANIFEST ------------------>   |   "photo.jpg, 2.4 MB"
    |  <-- ACCEPT ---------------------   |   the user's answer
    |  --- FILE_HEADER + stream ------>   |
    |  --- DONE ---------------------->   |
```

The receiver is the one that publishes the connection details, and the sender never advertises.
The tap is what starts advertising, and it stops 30 seconds later — nothing is broadcast while
Beam sits idle.

The secret only ever crosses the ~1 cm NFC gap, and the proof is bound to the specific Nearby
connection's authentication digits, so a device that merely saw the advertisement cannot
connect, and a proof captured off the air cannot be replayed into another connection.

## Modules

| Module | What it is |
| --- | --- |
| `:core:model` | Payloads, transfer events, errors, the payload manifest. Pure Kotlin. |
| `:core:session` | Token codec, proof-of-tap, handshake state machine. Pure Kotlin — this is the protocol spec, and its tests are the contract. |
| `:core:transport` | The `Transport` interface and the registry that negotiates one. |
| `:transport:nearby` | Nearby Connections backend. **The only module allowed to name Nearby types.** |
| `:transport:wifidirect` | The planned replacement. Compiles, reports itself unavailable. |
| `:transport:fake` | In-process loopback with latency, throughput and failure injection. Debug and tests only. |
| `:feature:nfc` | HCE service, APDU dialect, reader-mode controller. |
| `:data:history` | Room. `:data:settings` | DataStore. |
| `:app` | Compose UI, foreground service, DI container. |

### The transport seam

`:app` and `:feature:nfc` never see a Nearby type. Two things enforce it: no other module
declares the dependency, and `./gradlew checkTransportBoundary` (wired into `check`) fails the
build on the import. Replacing Nearby with Wi-Fi Direct means implementing one interface and
changing one list in `BeamContainer`.

## Building

```bash
./gradlew check          # 68 unit tests, lint, transport-boundary guard
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 21 (pinned in `gradle.properties`) and an Android SDK with platform 37.

## Developer simulation

Debug builds only (if you want one my dc is [here](https://discord.com/users/1217159751440269364)), under Settings → Developer:

- **Simulate an incoming tap** — mints a real session and runs the real receive flow, so the
  foreground service, notification and confirmation screen can be exercised on one phone.
- **Scenarios** — a healthy 4 MB photo, a 1 MB/s link, a text beam, a declined transfer, a
  forged proof of tap, a link that dies at 50%, and a receiver that never appears. Each runs the
  real session protocol over `FakeTransport` with both ends in-process.

The release variant of `BeamDebug` depends on none of it, so a shipping APK contains no
simulator (verified: no `FakeTransport` in the release dex).

## Status

Working on hardware, between a Nothing A069 and a Pixel 6, both Android 17.

Verified end to end:

- **A closed phone receives.** Beam swiped away from recents still answers a tap: the platform
  binds its HCE service, which mints a session and starts the transfer. No app open, no
  account, no internet.
- **No pairing dialog, no Play Services, no Bluetooth.** Wi-Fi Direct wins the transport race;
  both phones derive the same group credentials from the tap.
- **Tap to done in about 5 seconds** for a small payload, of which roughly 2 are the human
  reading the confirmation. A 2.7 MB photo moved in 0.4 s once connected.
- **Byte-exact delivery**, checked on disk rather than trusted from the sender's report.
- **Permissions ask for themselves** from a clean install, including the permanently-denied
  case where the platform silently stops showing prompts.
- **Resume survives a real interruption.** An 8 MB transfer cut at 524,288 bytes was picked up
  by the next tap (`Resuming 1 file(s): {0=524288}`) and the stitched file hashed identical to
  the original — which is the only check that catches a plausible-looking but wrongly joined
  file.

### Things learned the hard way

- **`ACCESS_LOCAL_NETWORK`** (Android 16+) is required by Nearby Connections, and Play Services
  has no status code for it — a missing grant surfaces as a bare `INTERNAL_ERROR`, status 8,
  with the real reason only in GMS's own logcat.
- **`INTERNET` is required for Wi-Fi Direct.** Android gates the `socket()` syscall behind it,
  so a link-local socket on a group with no route off the device still needs it. Without it:
  `EPERM`.
- **Nearby Connections always uses Bluetooth for its control channel**, under every strategy, so
  the pairing dialog cannot be avoided while Nearby carries the beam. This is what motivated
  finishing Wi-Fi Direct.
- **A socket write is not a delivery.** The sender initially reported success after 222 ms,
  having only filled its local send buffer, then closed the connection and truncated the
  receiver at 696 KB of 2.7 MB. The receiver now sends a `RECEIVED` frame once every file is
  committed, and the sender waits for it.

### Both backends honour protocol 2

Nearby and Wi-Fi Direct share one frame vocabulary and one sequence, so a transfer gets the
same guarantees — relative paths, a per-file SHA-256, resume from an interrupted attempt —
whichever radio wins the race. They differ only where the medium forces it: a socket has no
message boundaries, so Wi-Fi Direct length-prefixes its frames and hashes each file on the
way past; Nearby's payloads are already messages, but it hands frames and streams to separate
callbacks with no ordering between them, so it sends each digest ahead of its file and the
receiver waits for the one that names the file it just finished.

That costs Nearby an extra local read per file, since Play Services reads the stream rather
than us. On the fallback path that is a fair price for being able to detect a damaged file.

### Transport fallback

A transport can report itself available and still fail when it runs. The sender tries each
shared transport in order, falling back only while nothing is committed — once a manifest has
been offered the user has seen a prompt and bytes may have moved, so a failure after that is
final. The receiver meanwhile listens on *every* shared transport at once, because nothing
coordinates which one the two phones would otherwise pick; whichever the sender reaches first
wins and the rest are cancelled.

### Known gaps

- Group formation dominates the latency and has not been optimised.
- The Nearby backend's connection handling has no automated coverage; only the frame protocol,
  identity encoding and error mapping do.
- Resume is verified against an injected interruption, but not against a genuinely flaky
  radio: the fault is clean and instantaneous, where a real one is noisy and gradual.
- Seen once and not explained: a sender finished a 12-byte text transfer without noticing it
  had ended, while the receiver completed normally in 241 ms. It has not reproduced since, and
  the sender's log was gone before it could be read, so there is no root cause here — only a
  sighting. A text payload rides inside the manifest, so the sender emits `Started` and then
  nothing until `Completed`, with no progress event in between; that is the first place to
  look if it recurs.

### Not doing

Cloud sync, accounts, messaging, social features, analytics. No Shizuku — no dependency, no
permission, no stub.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
