#!/usr/bin/env bash
# Self-test for the web (Safari) version: scrub generated videos, check tones, extract audio.
set -euo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d)
ffmpeg -v error -y -f lavfi -i testsrc=d=2:s=320x240 -f lavfi -i "sine=f=19000:d=2" \
  -c:v libx264 -c:a aac -b:a 256k -metadata location="+51.4779-000.0015/" \
  -metadata com.apple.quicktime.model="TestPhone" -metadata creation_time=2026-01-01T12:00:00Z \
  -movflags use_metadata_tags -f mov "$T/in.mov"
printf '1\n00:00:00,000 --> 00:00:02,000\nGPSSECRET\n' > "$T/s.srt"
ffmpeg -v error -y -i "$T/in.mov" -i "$T/s.srt" -map 0 -map 1 -c copy -c:s mov_text "$T/in.mp4"
ffmpeg -v error -y -i "$T/in.mov" -vn -f s16le -ac 1 -ar 48000 "$T/a.raw"
node tools/web-selftest.mjs "$T/in.mov" "$T/in.mp4" "$T/a.raw" "$T/a.aac"
ffmpeg -v error -i "$T/in.mp4.web" -f null - && echo "web-scrubbed video still decodes: OK"
ffmpeg -v error -i "$T/a.aac" -f null - && echo "extracted ADTS audio decodes: OK"
if grep -a -q GPSSECRET "$T/in.mp4.web"; then echo "FAIL: hidden track text survived"; exit 1; fi
echo "WEB SELF-TEST PASSED"
