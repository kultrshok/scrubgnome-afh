package com.kultrshok.scrubgnome;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.kultrshok.scrubgnome.core.UltrasonicAnalyzer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes a video's audio track to PCM on-device and feeds it to UltrasonicAnalyzer.
 * Author: kultrshok. MIT License.
 */
final class AudioToneScanner {

    interface Progress { void update(float fraction); }

    /** Cap the scan so a 2-hour video doesn't cook the battery. */
    static final long MAX_US = 10L * 60 * 1_000_000;

    private AudioToneScanner() {}

    /** Returns null when the video has no audio track. */
    static UltrasonicAnalyzer.Result scan(File video, Progress progress) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(video.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            long limitUs = (durUs > 0) ? Math.min(durUs, MAX_US) : MAX_US;

            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int pcm = AudioFormat.ENCODING_PCM_16BIT;
            UltrasonicAnalyzer analyzer = null;
            float[] mono = new float[8192];

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int in = dec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(in);
                        int n = (ib == null) ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0 || ex.getSampleTime() > limitUs) {
                            dec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = dec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (out >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(out);
                        if (ob != null) {
                            if (analyzer == null) analyzer = new UltrasonicAnalyzer(sampleRate);
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            int bytesPer = (pcm == AudioFormat.ENCODING_PCM_FLOAT) ? 4 : 2;
                            int ch = Math.max(1, channels);
                            int frames = info.size / (bytesPer * ch);
                            if (mono.length < frames) mono = new float[frames];
                            for (int f = 0; f < frames; f++) {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) {
                                    sum += (bytesPer == 4) ? ob.getFloat() : ob.getShort() / 32768f;
                                }
                                mono[f] = sum / ch;
                            }
                            analyzer.feed(mono, frames);
                        }
                    }
                    if (progress != null && limitUs > 0) {
                        progress.update(Math.min(1f, (float) info.presentationTimeUs / limitUs));
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (analyzer == null) analyzer = new UltrasonicAnalyzer(sampleRate);
            return analyzer.finish();
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                dec.release();
            }
            ex.release();
        }
    }
}
