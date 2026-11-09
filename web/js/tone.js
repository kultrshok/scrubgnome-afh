// Scrubgnome Away from Home (web) - near-ultrasonic tracking-tone check.
// Port of core/UltrasonicAnalyzer.java, plus a lightweight AAC extractor so the
// browser can decode just the audio (as ADTS) instead of loading the whole video.
// Author: kultrshok. MIT License.

import { sampleRanges } from './scrubber.js';

export const Level = { NONE: 'NONE', NO_ROOM: 'NO_ROOM', LOW: 'LOW', MEDIUM: 'MEDIUM', HIGH: 'HIGH' };

const BAND_LO = 17500, BAND_HI_MAX = 22000, N = 4096, PEAK_DB = 15, FLOOR_DB = -80;
const MAX_SECONDS = 600;

function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const a = i + k, b = a + len / 2;
        const xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
        const t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
      }
    }
  }
}

function sideMedian(p, lo, hi) {
  lo = Math.max(lo, 1); hi = Math.min(hi, p.length - 1);
  if (hi < lo) return 1e-30;
  const v = Array.from(p.subarray(lo, hi + 1)).sort((a, b) => a - b);
  return v[v.length >> 1];
}

export class UltrasonicAnalyzer {
  constructor(sampleRate) {
    this.sr = sampleRate;
    const binHz = sampleRate / N;
    const hi = Math.min(BAND_HI_MAX, sampleRate / 2 - 2 * binHz);
    this.binLo = Math.ceil(BAND_LO / binHz);
    this.binHi = Math.max(this.binLo, Math.floor(hi / binHz));
    this.refLo = Math.floor(1000 / binHz);
    this.refHi = Math.floor(8000 / binHz);
    this.win = Float64Array.from({ length: N }, (_, i) => 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
    this.re = new Float64Array(N); this.im = new Float64Array(N);
    this.buf = new Float32Array(N); this.fill = 0;
    this.avgPow = new Float64Array(N / 2); this.peakCount = new Int32Array(N / 2);
    this.frames = 0; this.burstFrames = 0; this.loudFrames = 0; this.prevPeak = -1; this.hops = 0;
    this.bandSum = 0; this.refSum = 0;
  }

  bandPossible() { return this.sr / 2 > BAND_LO + 500; }

  feed(mono) {
    let i = 0;
    while (i < mono.length) {
      const k = Math.min(mono.length - i, N - this.fill);
      this.buf.set(mono.subarray(i, i + k), this.fill);
      this.fill += k; i += k;
      if (this.fill === N) { this.frame(); this.fill = 0; }
    }
  }

  frame() {
    const { re, im, buf, win } = this;
    let rms = 0;
    for (let i = 0; i < N; i++) { re[i] = buf[i] * win[i]; im[i] = 0; rms += buf[i] * buf[i]; }
    if (Math.sqrt(rms / N) < 1e-6) return;
    fft(re, im);
    this.frames++;
    const norm = (N / 4) ** 2, p = new Float64Array(N / 2);
    for (let b = 1; b < N / 2; b++) { p[b] = (re[b] * re[b] + im[b] * im[b]) / norm; this.avgPow[b] += p[b]; }
    let band = 0, ref = 0;
    for (let b = this.binLo; b <= this.binHi; b++) band += p[b];
    for (let b = this.refLo; b <= this.refHi; b++) ref += p[b];
    this.bandSum += band; this.refSum += ref;
    if (!this.bandPossible()) return;
    let best = -1, bestProm = 0, any = false;
    for (let b = this.binLo + 2; b <= this.binHi - 2; b++) {
      if (p[b] < p[b - 1] || p[b] < p[b + 1]) continue;
      const db = 10 * Math.log10(p[b] + 1e-30);
      if (db < FLOOR_DB) continue;
      const side = Math.max(sideMedian(p, b - 24, b - 3), sideMedian(p, b + 3, b + 24));
      const prom = db - 10 * Math.log10(side + 1e-30);
      if (prom >= PEAK_DB) { any = true; this.peakCount[b]++; if (prom > bestProm) { bestProm = prom; best = b; } }
    }
    if (any) {
      this.burstFrames++;
      if (this.prevPeak > 0 && Math.abs(best - this.prevPeak) > 2) this.hops++;
      this.prevPeak = best;
    } else this.prevPeak = -1;
    if (10 * Math.log10(band + 1e-30) > -70) this.loudFrames++;
  }

  finish() {
    if (this.fill > N / 2) { this.buf.fill(0, this.fill); this.frame(); }
    const r = { level: Level.NONE, headline: '', explanation: '', details: [], cutoffHz: 0, toneHz: 0, seconds: this.frames * N / this.sr };
    const binHz = this.sr / N, frames = this.frames;
    if (!frames) {
      r.headline = 'No sound to check';
      r.explanation = "The audio track is silent, so there's nothing that could carry a hidden tone.";
      return r;
    }
    if (!this.bandPossible()) {
      r.level = Level.NO_ROOM;
      r.headline = 'No room for hidden tones';
      r.explanation = `This audio is recorded at ${(this.sr / 1000).toFixed(1)} kHz, which can't hold sounds above ${(this.sr / 2000).toFixed(1)} kHz. Ultrasonic beacons can't be in here.`;
      return r;
    }
    const avg = this.avgPow.map(v => v / frames);
    const avgDb = avg.map(v => 10 * Math.log10(v + 1e-30));
    let cutBin = 1;
    for (let b = N / 2 - 2; b > 1; b--) if (avgDb[b] > -105) { cutBin = b; break; }
    r.cutoffHz = cutBin * binHz;

    let bestBin = -1, bestCount = 0;
    for (let b = this.binLo + 1; b < this.binHi; b++) {
      const c = this.peakCount[b - 1] + this.peakCount[b] + this.peakCount[b + 1];
      if (c > bestCount) { bestCount = c; bestBin = b; }
    }
    const steadyShare = Math.min(bestCount, frames) / frames;
    let avgProm = 0, avgBin = -1;
    for (let b = this.binLo + 2; b <= this.binHi - 2; b++) {
      if (avgDb[b] < avgDb[b - 1] || avgDb[b] < avgDb[b + 1] || avgDb[b] < -110) continue;
      const side = Math.max(sideMedian(avg, b - 24, b - 3), sideMedian(avg, b + 3, b + 24));
      const prom = avgDb[b] - 10 * Math.log10(side + 1e-30);
      if (prom > avgProm) { avgProm = prom; avgBin = b; }
    }
    const avgTone = frames >= 20 && avgProm >= 8;
    const burstShare = this.burstFrames / frames;
    const bandToRef = 10 * Math.log10((this.bandSum + 1e-30) / (this.refSum + 1e-30));
    r.toneHz = bestBin > 0 ? bestBin * binHz : 0;
    if (avgTone && steadyShare < 0.25) r.toneHz = avgBin * binHz;

    r.details.push(`Analyzed ${r.seconds.toFixed(1)} s at ${this.sr} Hz, ${frames} frames of ${N} samples`);
    r.details.push(`Audio content reaches ~${(r.cutoffHz / 1000).toFixed(1)} kHz (encoder cutoff)`);
    r.details.push(`Steady narrow peak: ${Math.round(steadyShare * 100)}% of frames${bestCount ? ` near ${(bestBin * binHz / 1000).toFixed(2)} kHz` : ''}`);
    r.details.push(`Frames with any narrow peak in 17.5-22 kHz: ${Math.round(burstShare * 100)}% (${this.hops} frequency hops)`);
    r.details.push(`Strongest steady peak in averaged spectrum: ${avgProm.toFixed(1)} dB above surroundings${avgBin > 0 ? ` at ${(avgBin * binHz / 1000).toFixed(2)} kHz` : ''}`);
    r.details.push(`Beacon band vs 1-8 kHz energy: ${bandToRef.toFixed(1)} dB`);

    if (r.cutoffHz < BAND_LO - 250 && this.burstFrames === 0) {
      r.level = Level.NO_ROOM;
      r.headline = 'No room for hidden tones';
      r.explanation = `The audio stops at about ${(r.cutoffHz / 1000).toFixed(1)} kHz, below the band tracking beacons use (17.5-22 kHz). An ultrasonic beacon can't be hiding in this file.`;
    } else if (steadyShare >= 0.25 || avgTone) {
      r.level = Level.HIGH;
      r.headline = `Steady inaudible tone at ${(r.toneHz / 1000).toFixed(1)} kHz`;
      r.explanation = "There's a constant high-pitched tone most people can't hear. That is what ultrasonic tracking beacons look like. It could also be electronics nearby (chargers, displays, pest repellers). Consider cleaning the audio before posting.";
    } else if (burstShare >= 0.05 && (this.hops >= 3 || steadyShare >= 0.05)) {
      r.level = Level.MEDIUM;
      r.headline = 'Possible inaudible data bursts';
      r.explanation = 'Short, narrow high-pitched blips come and go in the beacon range. That pattern can be a beacon sending data, but it can also be ordinary gear noise. Worth cleaning the audio if privacy matters for this one.';
    } else if (bandToRef > -25 && this.loudFrames > frames / 4) {
      r.level = Level.LOW;
      r.headline = 'Some ultrasonic noise';
      r.explanation = "There's more high-frequency hiss than usual, but no beacon-like tones. Usually that's just electronics or wind.";
    } else {
      r.headline = 'No tracking tones found';
      r.explanation = 'Nothing in the 17.5-22 kHz range looks like a tracking beacon.';
    }
    r.explanation += " (This checks for inaudible beacons only. Watermarks hidden inside normal sound can't be detected this way.)";
    return r;
  }
}

// ---------------------------------------------------------------------------
// AAC extraction: video file -> ADTS stream of the first audio track (no full load)
// ---------------------------------------------------------------------------

const be32 = (d, o) => ((d[o] << 24) >>> 0) + (d[o + 1] << 16) + (d[o + 2] << 8) + d[o + 3];
const SFI = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];

class Reader {
  constructor(blob) { this.blob = blob; this.size = blob.size; }
  async read(pos, len) { return new Uint8Array(await this.blob.slice(pos, pos + len).arrayBuffer()); }
  async u32(pos) { return be32(await this.read(pos, 4), 0); }
}

async function readBox(r, pos, limit) {
  if (pos + 8 > limit) return null;
  const h = await r.read(pos, 8);
  let size = be32(h, 0), hdr = 8;
  const type = String.fromCharCode(h[4], h[5], h[6], h[7]);
  if (size === 1) { const l = await r.read(pos + 8, 8); size = be32(l, 0) * 4294967296 + be32(l, 4); hdr = 16; }
  else if (size === 0) size = limit - pos;
  if (size < hdr || pos + size > limit) return null;
  return { pos, size, hdr, type, payload: pos + hdr, end: pos + size };
}

async function kids(r, box) {
  const out = [];
  for (let pos = box.payload; pos + 8 <= box.end;) {
    const b = await readBox(r, pos, box.end);
    if (!b) break;
    out.push(b); pos = b.end;
  }
  return out;
}

function parseEsds(d) {
  // find 'esds' then walk descriptors to tag 5 (DecoderSpecificInfo)
  let i = -1;
  for (let k = 0; k < d.length - 4; k++) if (d[k] === 0x65 && d[k + 1] === 0x73 && d[k + 2] === 0x64 && d[k + 3] === 0x73) { i = k + 8; break; }
  if (i < 0) return null;
  const len = () => { let n = 0, b; do { b = d[i++]; n = (n << 7) | (b & 0x7f); } while (b & 0x80 && i < d.length); return n; };
  if (d[i++] !== 3) return null;
  len(); i += 2;
  const flags = d[i++];
  if (flags & 0x80) i += 2;
  if (flags & 0x40) i += 1 + d[i];
  if (flags & 0x20) i += 2;
  if (d[i++] !== 4) return null;
  len();
  const oti = d[i]; i += 13;
  if (oti !== 0x40 && oti !== 0x66 && oti !== 0x67) return null; // MPEG-4 / MPEG-2 AAC
  if (d[i++] !== 5) return null;
  const n = len();
  const asc = d.subarray(i, i + n);
  let aot = asc[0] >> 3;
  let sfi = ((asc[0] & 7) << 1) | (asc[1] >> 7);
  const ch = (asc[1] >> 3) & 15;
  if (aot === 31 || sfi === 15) return null; // exotic; let the fallback handle it
  if (aot === 5 || aot === 29) aot = 2; // HE-AAC: ADTS carries the LC core, decoder finds SBR
  if (aot < 1 || aot > 4) return null;
  return { profile: aot - 1, sfi, ch: ch || 2, sampleRate: SFI[sfi] };
}

/** Returns { adts: Uint8Array, sampleRate } or null when the audio isn't plain AAC. */
export async function extractAac(blob, maxSeconds = MAX_SECONDS) {
  const r = new Reader(blob);
  let pos = 0, moov = null;
  while (pos < r.size) {
    const b = await readBox(r, pos, r.size);
    if (!b) break;
    if (b.type === 'moov') { moov = b; break; }
    pos = b.end;
  }
  if (!moov) return null;
  let foundAudio = false;
  for (const t of await kids(r, moov)) {
    if (t.type !== 'trak') continue;
    let handler = null, stbl = null;
    for (const m of await kids(r, (await kids(r, t)).find(c => c.type === 'mdia') || { payload: 0, end: 0 })) {
      if (m.type === 'hdlr') { const h = await r.read(m.payload + 8, 4); handler = String.fromCharCode(...h); }
      if (m.type === 'minf') for (const mi of await kids(r, m)) if (mi.type === 'stbl') stbl = mi;
    }
    if (handler !== 'soun' || !stbl) continue;
    foundAudio = true;
    const st = await kids(r, stbl);
    const stsd = st.find(c => c.type === 'stsd');
    if (!stsd) return null;
    const desc = await r.read(stsd.payload, Math.min(stsd.size - stsd.hdr, 4096));
    const entryType = String.fromCharCode(desc[12], desc[13], desc[14], desc[15]);
    if (entryType !== 'mp4a') return null;
    const cfg = parseEsds(desc);
    if (!cfg) return null;
    const maxFrames = Math.ceil(maxSeconds * cfg.sampleRate / 1024) + 1;
    const ranges = (await sampleRanges(r, st)).slice(0, maxFrames);
    if (!ranges.length) return null;
    const total = ranges.reduce((s, [, n]) => s + n + 7, 0);
    const out = new Uint8Array(total);
    let o = 0, i = 0;
    while (i < ranges.length) {
      // read contiguous runs in one go
      let j = i, runEnd = ranges[i][0] + ranges[i][1];
      while (j + 1 < ranges.length && ranges[j + 1][0] === runEnd && runEnd - ranges[i][0] < (4 << 20)) { j++; runEnd += ranges[j][1]; }
      const data = await r.read(ranges[i][0], runEnd - ranges[i][0]);
      let p = 0;
      for (let k = i; k <= j; k++) {
        const n = ranges[k][1], fl = n + 7;
        out[o] = 0xff; out[o + 1] = 0xf1;
        out[o + 2] = (cfg.profile << 6) | (cfg.sfi << 2) | ((cfg.ch >> 2) & 1);
        out[o + 3] = ((cfg.ch & 3) << 6) | ((fl >> 11) & 3);
        out[o + 4] = (fl >> 3) & 0xff;
        out[o + 5] = ((fl & 7) << 5) | 0x1f;
        out[o + 6] = 0xfc;
        out.set(data.subarray(p, p + n), o + 7);
        o += fl; p += n;
      }
      i = j + 1;
    }
    return { adts: out.subarray(0, o), sampleRate: cfg.sampleRate };
  }
  return foundAudio ? null : { none: true };
}

/** Browser only: decode audio and run the analyzer.
 *  Returns { none: true } when there's no audio track, null when the audio can't be decoded. */
export async function checkTones(blob, onProgress) {
  const say = f => onProgress && onProgress(f);
  say(0.05);
  let audio = null, decodeRate = 48000;
  const aac = await extractAac(blob);
  const OAC = globalThis.OfflineAudioContext || globalThis.webkitOfflineAudioContext;
  const decode = (ctx, buf) => new Promise((ok, bad) => {
    const p = ctx.decodeAudioData(buf, ok, bad);
    if (p && p.then) p.then(ok, bad);
  });
  say(0.25);
  if (aac && aac.none) return { none: true };
  if (aac) {
    decodeRate = Math.max(aac.sampleRate, 44100);
    audio = await decode(new OAC(1, 1, decodeRate), aac.adts.slice().buffer);
  } else if (blob.size < 300 * 1048576) {
    try { audio = await decode(new OAC(1, 1, 48000), await blob.arrayBuffer()); } catch { audio = null; }
    if (!audio) return null;
  } else {
    return null;
  }
  say(0.6);
  const an = new UltrasonicAnalyzer(audio.sampleRate);
  const len = Math.min(audio.length, MAX_SECONDS * audio.sampleRate);
  const chans = Array.from({ length: audio.numberOfChannels }, (_, c) => audio.getChannelData(c));
  const step = 1 << 16, mono = new Float32Array(step);
  for (let s = 0; s < len; s += step) {
    const n = Math.min(step, len - s);
    for (let i = 0; i < n; i++) { let sum = 0; for (const ch of chans) sum += ch[s + i]; mono[i] = sum / chans.length; }
    an.feed(mono.subarray(0, n));
    if ((s / step) % 16 === 0) { say(0.6 + 0.4 * s / len); await new Promise(res => setTimeout(res)); }
  }
  say(1);
  return an.finish();
}
