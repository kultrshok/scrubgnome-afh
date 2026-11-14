# Scrubgnome Away from Home: Bug Fixes

*What got fixed, and anything still known to bite.*

## v1.0 (2026-11-14)

**Fixed: iPhone videos failing with "Something survived the scrub: Other tags"** (iPhone web app and Android)
iPhone videos keep a small metadata block inside each track. Twig removed it correctly, but when he double-checked his own work he didn't recognise the empty block he'd left in its place and flagged it as leftover tags. The video was actually clean, but Twig refused to save it. He now recognises his own blank blocks anywhere in the file.

**New: a copy is kept when a scrub can't finish** (iPhone web app)
Before, a failed scrub gave you nothing. Now Twig runs a second pass automatically. If something still survives, he saves a copy named `NOT_CLEAN_recheck_<random>` with a red warning so you can keep it and recheck it later. Don't post that copy.

**New: app icon** refreshed. On iPhone, re-add the app to the home screen to see it.

**Fixed: release builds** now start for version tags written as `V1.0` as well as `v1.0` (phone keyboards capitalise the first letter).

## Known issues

- **iPhone home-screen icon doesn't update** after an app update. That's iOS; re-add the app from Safari.
- **Photo Library picks on iPhone** may hand over a re-compressed copy. Use **Browse** for the original.
- **Tone removal** isn't in the pocket edition. Use desktop Scrubgnome.

## Reporting a bug

Open an issue on the GitHub repo. Include the phone model, iOS or Android version, the exact red message, and (with **Geek mode** on) the detail list. **Don't attach the video**: it's full of the data you're trying to hide.

---
Author: kultrshok · MIT License · Generated with AI
