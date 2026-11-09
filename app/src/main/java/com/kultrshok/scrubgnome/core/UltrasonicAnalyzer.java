package com.kultrshok.scrubgnome.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scrubgnome Mobile - near-ultrasonic tracking-tone scanner.
 *
 * Audio beacons used for cross-device tracking (SilverPush, LISNR, Shopkick-style
 * "ultrasonic cross-device tracking") sit just above most adults' hearing,
 * roughly 17.5 - 22 kHz, as steady pilot tones or short FSK/chirp bursts.
 * This class looks for that signature in decoded PCM:
 *
 *  1. Steady tone : a narrow spectral peak that stands well above its
 *                   neighbours in the beacon band for a large share of frames.
 *  2. Data bursts : narrow peaks that come and go (on/off keying, FSK hopping).
 *  3. Band energy : the band holds unusually much energy relative to the
 *                   audible content (broadband - could be gear noise).
 *  4. Headroom    : where the encoder's low-pass cuts off. If the audio stops
 *                   below the band, an ultrasonic beacon cannot be in this file.
 *
 * It is a heuristic warning, not proof: watermarks hidden inside the audible
 * band are not detectable this way.
 *
 * Pure Java (no Android imports) so it can be tested on a desktop JVM.
 * Author: kultrshok. MIT License.
 */
public final class UltrasonicAnalyzer {

    public enum Level { NONE, NO_ROOM, LOW, MEDIUM, HIGH }

    public static final class Result {
        public Level level = Level.NONE;
        public String headline = "";
        public String explanation = "";
        public final List<String> details = new ArrayList<>();
        public double cutoffHz;
        public double toneHz;
        public double seconds;
    }

    public static final double BAND_LO = 17500.0;
    public static final double BAND_HI_MAX = 22000.0;

    private static final int N = 4096;           // FFT size (~85 ms at 48 kHz)
    private static final double PEAK_DB = 15.0;   // peak must beat local median by this much
    private static final double FLOOR_DB = -80.0; // ignore peaks quieter than this (dBFS)

    private final int sampleRate;
    private final int binLo, binHi, refLo, refHi;
    private final double[] window = new double[N];
    private final double[] re = new double[N], im = new double[N];
    private final float[] buf = new float[N];
    private int bufFill;

    private final double[] avgPow = new double[N / 2];
    private final int[] peakCount = new int[N / 2];
    private int frames, burstFrames, loudFrames;
    private int prevPeakBin = -1, hops;
    private double bandEnergySum, refEnergySum;

    public UltrasonicAnalyzer(int sampleRate) {
        this.sampleRate = sampleRate;
        double binHz = (double) sampleRate / N;
        double hi = Math.min(BAND_HI_MAX, sampleRate / 2.0 - 2 * binHz);
        binLo = (int) Math.ceil(BAND_LO / binHz);
        binHi = Math.max(binLo, (int) Math.floor(hi / binHz));
        refLo = (int) (1000 / binHz);
        refHi = (int) (8000 / binHz);
        for (int i = 0; i < N; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
    }

    /** True when the sample rate can even carry the beacon band. */
    public boolean bandPossible() { return sampleRate / 2.0 > BAND_LO + 500; }

    /** Feed mono samples in [-1, 1]. */
    public void feed(float[] mono, int count) {
        int i = 0;
        while (i < count) {
            int k = Math.min(count - i, N - bufFill);
            System.arraycopy(mono, i, buf, bufFill, k);
            bufFill += k;
            i += k;
            if (bufFill == N) { analyzeFrame(); bufFill = 0; }
        }
    }

    public double secondsAnalyzed() { return (double) frames * N / sampleRate; }

    private void analyzeFrame() {
        double rms = 0;
        for (int i = 0; i < N; i++) { re[i] = buf[i] * window[i]; im[i] = 0; rms += buf[i] * buf[i]; }
        if (Math.sqrt(rms / N) < 1e-6) return; // digital silence: skip
        fft(re, im);
        frames++;
        double norm = (N / 4.0) * (N / 4.0);  // Hann-windowed full-scale sine -> ~0 dBFS
        double[] p = new double[N / 2];
        for (int b = 1; b < N / 2; b++) {
            p[b] = (re[b] * re[b] + im[b] * im[b]) / norm;
            avgPow[b] += p[b];
        }
        double band = 0, ref = 0;
        for (int b = binLo; b <= binHi; b++) band += p[b];
        for (int b = refLo; b <= refHi; b++) ref += p[b];
        bandEnergySum += band;
        refEnergySum += ref;
        if (!bandPossible()) return;

        // narrow peaks in the beacon band
        int bestBin = -1;
        double bestProm = 0;
        boolean any = false;
        for (int b = binLo + 2; b <= binHi - 2; b++) {
            if (p[b] < p[b - 1] || p[b] < p[b + 1]) continue;
            double db = 10 * Math.log10(p[b] + 1e-30);
            if (db < FLOOR_DB) continue;
            // a real tone stands out on BOTH sides (a filter edge only on one)
            double left = sideMedian(p, b - 24, b - 3);
            double right = sideMedian(p, b + 3, b + 24);
            double prom = db - 10 * Math.log10(Math.max(left, right) + 1e-30);
            if (prom >= PEAK_DB) {
                any = true;
                peakCount[b]++;
                if (prom > bestProm) { bestProm = prom; bestBin = b; }
            }
        }
        if (any) {
            burstFrames++;
            if (prevPeakBin > 0 && Math.abs(bestBin - prevPeakBin) > 2) hops++;
            prevPeakBin = bestBin;
        } else {
            prevPeakBin = -1;
        }
        if (10 * Math.log10(band + 1e-30) > -70) loudFrames++;
    }

    private double[] avgPowCache;
    private double[] avgPowNorm() {
        if (avgPowCache == null) {
            avgPowCache = new double[avgPow.length];
            for (int b = 0; b < avgPow.length; b++) avgPowCache[b] = avgPow[b] / Math.max(1, frames);
        }
        return avgPowCache;
    }

    private static double sideMedian(double[] p, int lo, int hi) {
        lo = Math.max(lo, 1);
        hi = Math.min(hi, p.length - 1);
        if (hi < lo) return 1e-30;
        double[] v = new double[hi - lo + 1];
        for (int b = lo; b <= hi; b++) v[b - lo] = p[b];
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }

    public Result finish() {
        if (bufFill > N / 2) { for (int i = bufFill; i < N; i++) buf[i] = 0; analyzeFrame(); }
        Result r = new Result();
        r.seconds = secondsAnalyzed();
        double binHz = (double) sampleRate / N;

        if (frames == 0) {
            r.level = Level.NONE;
            r.headline = "No sound to check";
            r.explanation = "The audio track is silent, so there's nothing that could carry a hidden tone.";
            return r;
        }
        if (!bandPossible()) {
            r.level = Level.NO_ROOM;
            r.headline = "No room for hidden tones";
            r.explanation = String.format(Locale.US,
                    "This audio is recorded at %.1f kHz, which can't hold sounds above %.1f kHz. Ultrasonic beacons can't be in here.",
                    sampleRate / 1000.0, sampleRate / 2000.0);
            return r;
        }

        // encoder cutoff: highest bin whose average power is clearly above the top-end floor
        double[] avgDb = new double[N / 2];
        for (int b = 1; b < N / 2; b++) avgDb[b] = 10 * Math.log10(avgPow[b] / frames + 1e-30);
        // encoders zero everything above their low-pass, so look for the last bin with real energy
        int cutBin = 1;
        for (int b = N / 2 - 2; b > 1; b--) {
            if (avgDb[b] > -105) { cutBin = b; break; }
        }
        r.cutoffHz = cutBin * binHz;

        // best steady tone (merge +/-1 bin to allow slight drift)
        int bestBin = -1, bestCount = 0;
        for (int b = binLo + 1; b < binHi; b++) {
            int c = peakCount[b - 1] + peakCount[b] + peakCount[b + 1];
            if (c > bestCount) { bestCount = c; bestBin = b; }
        }
        double steadyShare = (double) Math.min(bestCount, frames) / frames;

        // long-term average spectrum: noise averages out, a steady tone doesn't
        double avgProm = 0;
        int avgBin = -1;
        for (int b = binLo + 2; b <= binHi - 2; b++) {
            if (avgDb[b] < avgDb[b - 1] || avgDb[b] < avgDb[b + 1] || avgDb[b] < -110) continue;
            double side = Math.max(sideMedian(avgPowNorm(), b - 24, b - 3), sideMedian(avgPowNorm(), b + 3, b + 24));
            double prom = avgDb[b] - 10 * Math.log10(side + 1e-30);
            if (prom > avgProm) { avgProm = prom; avgBin = b; }
        }
        boolean avgTone = frames >= 20 && avgProm >= 8;
        double burstShare = (double) burstFrames / frames;
        double bandToRefDb = 10 * Math.log10((bandEnergySum + 1e-30) / (refEnergySum + 1e-30));
        r.toneHz = bestBin > 0 ? bestBin * binHz : 0;
        if (avgTone && steadyShare < 0.25) r.toneHz = avgBin * binHz;

        r.details.add(String.format(Locale.US, "Analyzed %.1f s at %d Hz, %d frames of %d samples", r.seconds, sampleRate, frames, N));
        r.details.add(String.format(Locale.US, "Audio content reaches ~%.1f kHz (encoder cutoff)", r.cutoffHz / 1000));
        r.details.add(String.format(Locale.US, "Steady narrow peak: %.0f%% of frames%s", steadyShare * 100,
                bestBin > 0 && bestCount > 0 ? String.format(Locale.US, " near %.2f kHz", r.toneHz / 1000) : ""));
        r.details.add(String.format(Locale.US, "Frames with any narrow peak in 17.5-22 kHz: %.0f%% (%d frequency hops)", burstShare * 100, hops));
        r.details.add(String.format(Locale.US, "Strongest steady peak in averaged spectrum: %.1f dB above surroundings%s", avgProm,
                avgBin > 0 ? String.format(Locale.US, " at %.2f kHz", avgBin * binHz / 1000) : ""));
        r.details.add(String.format(Locale.US, "Beacon band vs 1-8 kHz energy: %.1f dB", bandToRefDb));

        if (r.cutoffHz < BAND_LO - 250 && burstFrames == 0) {
            r.level = Level.NO_ROOM;
            r.headline = "No room for hidden tones";
            r.explanation = String.format(Locale.US,
                    "The audio stops at about %.1f kHz, below the band tracking beacons use (17.5-22 kHz). An ultrasonic beacon can't be hiding in this file.",
                    r.cutoffHz / 1000);
        } else if (steadyShare >= 0.25 || avgTone) {
            r.level = Level.HIGH;
            r.headline = String.format(Locale.US, "Steady inaudible tone at %.1f kHz", r.toneHz / 1000);
            r.explanation = "There's a constant high-pitched tone most people can't hear. That is what ultrasonic tracking beacons look like. "
                    + "It could also be electronics nearby (chargers, displays, pest repellers). Consider cleaning the audio before posting.";
        } else if (burstShare >= 0.05 && (hops >= 3 || steadyShare >= 0.05)) {
            r.level = Level.MEDIUM;
            r.headline = "Possible inaudible data bursts";
            r.explanation = "Short, narrow high-pitched blips come and go in the beacon range. That pattern can be a beacon sending data, "
                    + "but it can also be ordinary gear noise. Worth cleaning the audio if privacy matters for this one.";
        } else if (bandToRefDb > -25 && loudFrames > frames / 4) {
            r.level = Level.LOW;
            r.headline = "Some ultrasonic noise";
            r.explanation = "There's more high-frequency hiss than usual, but no beacon-like tones. Usually that's just electronics or wind.";
        } else {
            r.level = Level.NONE;
            r.headline = "No tracking tones found";
            r.explanation = "Nothing in the 17.5-22 kHz range looks like a tracking beacon.";
        }
        r.explanation += " (This checks for inaudible beacons only. Watermarks hidden inside normal sound can't be detected this way.)";
        return r;
    }

    // ---- iterative radix-2 FFT --------------------------------------------------
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
}
