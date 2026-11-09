#!/usr/bin/env bash
# Desktop self-test for the pure-Java core: makes a video stuffed with fake GPS/device
# tags plus a hidden data track, scrubs it, and checks nothing survived.
set -euo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d)
CORE=app/src/main/java/com/kultrshok/scrubgnome/core
javac -d "$T/cls" $CORE/*.java tools/SelfTest.java
ffmpeg -v error -y -f lavfi -i testsrc=d=2:s=320x240 -f lavfi -i "sine=f=19000:d=2" \
  -c:v libx264 -c:a aac -b:a 256k -metadata location="+51.4779-000.0015/" \
  -metadata com.apple.quicktime.model="TestPhone" -metadata creation_time=2026-01-01T12:00:00Z \
  -movflags use_metadata_tags -f mov "$T/in.mov"
printf '1\n00:00:00,000 --> 00:00:02,000\nGPSSECRET\n' > "$T/s.srt"
ffmpeg -v error -y -i "$T/in.mov" -i "$T/s.srt" -map 0 -map 1 -c copy -c:s mov_text "$T/in.mp4"
ffmpeg -v error -y -i "$T/in.mp4" -vn -f s16le -ac 1 -ar 48000 "$T/a.raw"
java -cp "$T/cls" SelfTest "$T/in.mov" "$T/in.mp4" "$T/a.raw"
ffmpeg -v error -i "$T/in.mp4" -f null - && echo "scrubbed video still decodes: OK"
if grep -a -q GPSSECRET "$T/in.mp4"; then echo "FAIL: hidden track text survived"; exit 1; fi
echo "SELF-TEST PASSED"
