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

Debug builds only, under Settings → Developer:

- **Simulate an incoming tap** — mints a real session and runs the real receive flow, so the
  foreground service, notification and confirmation screen can be exercised on one phone.
- **Scenarios** — a healthy 4 MB photo, a 1 MB/s link, a text beam, a declined transfer, a
  forged proof of tap, a link that dies at 50%, and a receiver that never appears. Each runs the
  real session protocol over `FakeTransport` with both ends in-process.

The release variant of `BeamDebug` depends on none of it, so a shipping APK contains no
simulator (verified: no `FakeTransport` in the release dex).

## Status

Everything above compiles, and the protocol, the HCE card side, the history DAO and a full
in-process beam are covered by 68 passing unit tests. What is *not* yet verified is the part
that needs hardware: NFC reader mode, HCE across two real devices, and Nearby Connections
itself. That is the next step.

### Not doing

Cloud sync, accounts, messaging, social features, analytics. No Shizuku — no dependency, no
permission, no stub.
