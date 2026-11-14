# Scrubgnome Away from Home: FAQ

*Twig's answers to the questions people actually ask. Video edition (Android app + iPhone web app).*

## The basics

**What does it do?**
It strips the hidden "snitch" data out of phone videos before you post them: GPS location, recording date and time, phone make and model, software version, hidden data tracks and vendor junk. The picture and sound are not touched. Nothing is re-encoded.

**Does my video get uploaded anywhere?**
No. The Android app has no internet permission at all, and the iPhone web page is locked by its security policy so it *can't* send data anywhere. All the work happens on your phone.

**Which files does it handle?**
MP4, MOV and 3GP, which covers everything phones record. WebM and MKV aren't supported.

**Why does my clean copy have a random name?**
Phone file names like `PXL_20261108_001736.mp4` are timestamps. The clean copy gets `scrubbed_<random>` instead.

## iPhone

**How do I install it?**
Open **kultrshok.github.io/scrubgnome-afh** in Safari, then tap **Share → Add to Home Screen**. After the first visit it works offline.

**Photo Library or Browse?**
Use **Browse** (the Files picker) when you can. It hands over the untouched original. The Photo Library option may give Twig a re-compressed copy, which still scrubs fine but isn't the original.

**I got "Something survived the scrub". What now?**
Don't post that one. Twig still saves a copy named `NOT_CLEAN_recheck_<random>` so you can keep it and recheck it later: tap **Save copy for rechecking** (then **Save Video**), tap **Scrub another**, and load the copy back in to see exactly what's left. Turn on **Geek mode** for the full list. If it keeps failing, run it through desktop Scrubgnome.

**I updated the app but the home-screen icon didn't change.**
iOS never refreshes home-screen icons. Delete the home-screen app and add it again from Safari.

## Android

**How do I install it?**
Download the APK from the repo's **Releases** page (or **Actions → latest Build APK → Artifacts**), open it on your phone, and allow installs from that app once. Needs Android 10 or newer.

**Can I scrub straight from my gallery?**
Yes. Share a video to **Scrub with Twig**. Clean copies land in `Movies/Scrubgnome/`.

## The tracking-tone check

**What is "Listen for hidden tracking tones"?**
Some ad trackers play near-ultrasonic beacons (17.5–22 kHz) that phones hear and people don't. Twig scans the audio for them and tells you what he heard.

**Twig says he heard a tone. Am I being tracked?**
Maybe. It's a warning, not proof. Phone chargers, screens and pest repellers can whine up there too. The pocket edition can't remove tones; desktop Scrubgnome's audio cleaning can.

**Can it miss things?**
Yes. A very faint beacon under loud audio can slip past, and it can't detect watermarks hidden inside the audible range.

## Limits

**Does scrubbing hide where I filmed it?**
It removes the *hidden* data only. A street sign, a landmark, a reflection or a distinctive sound (like a jet overhead) in the video itself is still there. Desktop Scrubgnome has tools for the sound side.

**Is it really lossless?**
Yes. Metadata blocks are swapped for same-size blank blocks, so every pointer in the file stays valid and the video and audio are bit-for-bit identical.

---
Author: kultrshok · MIT License · Generated with AI
