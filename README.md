# Scrubgnome Away from Home

*Twig's pocket edition. Strip the snitchy metadata out of phone videos before you post them.*

> "Great gears! Your video is blabbing where, when and with what you filmed it. Hand it over." — Twig

Scrubgnome Away from Home (AFH) is the Android sidekick to desktop
[Scrubgnome](https://github.com/kultrshok/hidemydoppler). It does one job fast:

1. **Pick** a video (or *Share* one to "Scrub with Twig" from your gallery).
2. **See** what it's leaking: GPS location, recording time, phone make/model, software version, hidden data tracks.
3. **Scrub & save** a clean copy to `Movies/Scrubgnome/` with a random filename.
4. **Share / upload** the clean copy.

Optional: **Listen for hidden tracking tones.** Twig scans the audio for inaudible
"ultrasonic beacon" signals (17.5–22 kHz) and warns you if he hears any.

**Privacy promise:** the app has **no internet permission at all**. Android won't let it
talk to the network even if it wanted to. Everything happens on your phone.

---

## Install (no app store needed)

1. Open the repo's **Actions** tab → newest green **Build APK** run → download **ScrubgnomeAFH-apk** (a zip holding the APK).
   Tagged versions also show up under **Releases**.
2. Copy the APK to your phone and tap it. Android will ask you to allow installs from that app (Files/Chrome). Allow it once.
3. Requires **Android 10 or newer**.

## What gets removed

| Leak | Where it hides | What Twig does |
|---|---|---|
| GPS location | `©xyz`, QuickTime location keys, XMP | box neutralized and zeroed |
| Recording date/time | `mvhd` / `tkhd` / `mdhd` headers, `©day`, XMP | timestamps zeroed |
| Phone make & model | `com.android.model`, `©mak`/`©mod`, Apple keys | box neutralized and zeroed |
| OS / software version | `com.android.version`, `©swr`, `©too` | box neutralized and zeroed |
| Hidden data tracks | Pixel `mett`, iPhone `mebx`, camera-motion/GPS, subtitle tracks | sample bytes zeroed **and** track removed |
| XMP sidecar blobs | top-level `uuid` boxes | neutralized and zeroed |
| Vendor trailers | junk after the movie data (e.g. Samsung) | cut off |
| Handler names | `hdlr` ("VideoHandle", "Core Media…") | blanked |
| Original filename | `PXL_20261108_001736.mp4` *is* a timestamp | clean copy gets `scrubbed_<random>.mp4` |

**Lossless:** nothing is re-encoded. Metadata boxes become same-size `free` boxes full of zeros,
so every pointer in the file stays valid and the video and sound are bit-for-bit untouched.
After scrubbing, Twig re-scans the result and refuses to save if anything survived.

Supported: MP4, MOV, 3GP (everything phones record). Not supported: WebM/MKV.

## The hidden-tone check (and its limits)

Cross-device ad tracking sometimes plays near-ultrasonic beacons (steady pilot tones or short
data bursts) that phones can hear and people can't. Twig decodes the audio on-device, runs an
FFT over 17.5–22 kHz and reports:

| Result | Meaning |
|---|---|
| 🔴 **Steady inaudible tone** | A constant narrow tone, beacon-like. Could also be electronics (chargers, displays, pest repellers). |
| 🟠 **Possible data bursts** | Narrow blips that come and go or hop frequencies. |
| 🔵 **Some ultrasonic noise** | Broadband hiss, no beacon pattern. |
| 🟢 **No tracking tones** / **No room for hidden tones** | Clean, or the phone's encoder already cut everything above the beacon band. |

It's a **warning, not proof**. It can miss a very faint beacon buried under loud full-range sound,
and it can't detect watermarks hidden *inside* the audible range. The pocket edition doesn't
remove tones; use desktop Scrubgnome's audio cleaning for that.

## Build it yourself

**Easiest:** push to GitHub and let Actions build it (see `.github/workflows/build-apk.yml`).

**Android Studio:** *File → Open* this folder, wait for Gradle sync, press ▶ with your phone plugged in
(USB debugging on).

**Desktop self-test of the scrubber core** (needs Java 17+ and ffmpeg):
```
bash tools/selftest.sh
```

## Project layout

```
app/src/main/java/com/kultrshok/scrubgnome/
├── core/Mp4Scrubber.java         ← the scrubber (pure Java, no Android; reusable module)
├── core/UltrasonicAnalyzer.java  ← the tone detector (pure Java, no Android; reusable module)
├── AudioToneScanner.java         ← Android audio decoder that feeds the analyzer
└── MainActivity.java             ← the one-screen UI (Twig's voice lives here)
tools/                            ← desktop self-test
```
No third-party libraries. The two `core/` classes drop straight into any Java/Kotlin project.

## License

MIT © kultrshok. See [LICENSE](LICENSE).

<sub>Generated with AI</sub>
