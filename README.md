# M3Q Root AZF1 for Galaxy S26 Ultra (SM-S948U)

Temporary KernelSU root and LSPosed recovery app for the US Galaxy S26 Ultra
SM-S948U SQS3AZF1 firmware.

> **Exact target only.** Do not install or run this build on another model,
> region, firmware, or kernel. A failed kernel attempt can panic or reboot
> the device.

Forked from [monovibe/s26u-m3q-temp-root](https://github.com/monovibe/s26u-m3q-temp-root)
(SM-S948N AZG3) and ported to the SM-S948U AZF1 image. The exploit offsets
were verified byte-for-byte identical between the two kernel images; only
identity strings, the image hash and the P0 fingerprint are per-build.
See `docs/PORT-AZF1-NOTES.txt` for the full porting report. UI translated
to English.

fyi this was done with ai because I'm a lazy bum

## Supported target

| Field | Required value |
| --- | --- |
| Model | `SM-S948U` |
| Device / product | `m3q` / `m3qsqw` |
| Firmware | `S948USQS3AZF1_OYN3AZF1` |
| Android build | `BP4A.251205.006` |
| Kernel | `6.12.30-android16-5-pd30ff70-abogkiS948USQS3AZF1-4k` |
| App | `M3Q Root AZF1 0.5.8-azf1` (`versionCode 16`) |

The app checks the model, full build fingerprint, and kernel release before
enabling a kernel action. Any mismatch is rejected.

> **Shizuku is strongly recommended and effectively required for the best
> result.** The fallback route remains available, but an already-running and
> authorized Shizuku server enables the fast tracefs KASLR route. In
> practice, prepare Shizuku first to greatly improve root speed and the
> chance of a successful run.

## App functions

- **Temporary root:** obtains RAM-only root and activates the bundled
  firmware-matched KernelSU late-load component.
- **Module reload:** reruns the active KernelSU modules' late-load, mount,
  and service stages without rebooting Android.
- **Soft boot:** restarts the Android app runtime (Zygote/System UI) so
  Zygisk and LSPosed can attach again.
- **Root management:** opens KernelSU Manager for per-app permission control.
- **Diagnostics:** shares a redacted local report. The app has no Internet
  permission and disables Android backup.

There is no automatic post-boot root. A real reboot removes the temporary
root and KernelSU module.

## Install and use

1. Download the APK from [GitHub Releases](../../releases).
2. Compare its SHA-256 with the release notes.
3. Install the APK and KernelSU Manager (`me.weishu.kernelsu`).
4. Start Shizuku through ADB and approve this app once. Treat this as the
   standard setup for the best root speed and success rate.
5. Wait until kernel uptime reaches 180 seconds.
6. Open the app and tap **Temporary root** once. Do not retry an uncertain
   kernel run in the same boot.
7. If modules or LSPosed are inactive, run **Module reload**, then
   **Soft boot**.

## Root process

The app uses a fail-closed, per-boot flow:

1. exact model, fingerprint, and kernel gate;
2. 180-second boot-settle gate and one fresh kernel-write claim per boot;
3. Shizuku tracefs KASLR route when already authorized, otherwise the
   bundled exact-image physical-P0 oracle;
4. bounded carrier validation and kernel R/W setup;
5. UID-restricted bootstrap helper;
6. hash-verified KernelSU late-load handoff.

See [Root process](docs/ROOT_PROCESS.md) and [Technical reference](docs/REFERENCE.md)
for the implementation boundaries (written for the upstream AZG3 target;
the AZF1 port keeps the same architecture with the target values above).

## Build

Requirements: JDK 17, Android SDK 37, and Android NDK `29.0.14206865`.

Linux x86_64:

```sh
cd android
./build-native.sh AZF1
./test-safety-policy.sh
./gradlew --no-daemon :app:lintRelease :app:assembleRelease
```

Windows PowerShell:

```powershell
cd android
.\build-native.ps1 -Target AZF1
.\test-safety-policy.ps1
.\gradlew.bat --no-daemon :app:lintRelease :app:assembleRelease
```

APK output: `android/app/build/outputs/apk/release/app-release.apk`.

The `AZG3` target (original SM-S948N build) is preserved in-tree and still
builds with `-Target AZG3` / no argument.

## Repository layout

```
android/                         Android app and build scripts
exploit/src/                     shared native components
exploit/src/targets/m3q-S948USQS3AZF1/  exact AZF1 target (this port)
exploit/src/targets/m3q-BP4A.251205.006/  original AZG3 target (reference)
exploit/vendor/root-my-galaxy/   physical-P0 fallback and provenance
docs/                            runtime and technical documentation
```

Generated APKs, JNI outputs, device logs, screenshots, local paths, and
research scratch files are intentionally excluded from Git history.

## Attribution and license

Port to SM-S948U SQS3AZF1 by the maintainer of this fork. Upstream project
by monovibe; the one-shot Samsung 8EG5 route was cross-checked against
[CyberMeowfia](https://github.com/polygraphene/CyberMeowfia); the
physical-P0 fallback derives from [Root My Galaxy
Payloads](https://github.com/BuSung-dev/Root-My-Galaxy-Payloads); Shizuku
integration uses [Shizuku API](https://github.com/RikkaApps/Shizuku-API).

See [LICENSE](LICENSE), [NOTICE](android/NOTICE), and the vendored
[provenance](exploit/vendor/root-my-galaxy/UPSTREAM.md). This project
remains under the Apache-2.0 license.
