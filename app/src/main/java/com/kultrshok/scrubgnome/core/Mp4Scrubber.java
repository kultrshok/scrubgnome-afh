package com.kultrshok.scrubgnome.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scrubgnome Mobile - lossless metadata scrubber for MP4 / MOV / 3GP (ISO-BMFF) videos.
 *
 * How it works (no re-encoding, no dependencies):
 *  - Metadata boxes (udta, meta, uuid/XMP, vendor boxes) are turned into 'free'
 *    boxes of the SAME size and their contents are zeroed. Nothing moves, so the
 *    sample offset tables stay valid and the video plays exactly as before.
 *  - Creation / modification timestamps in mvhd, tkhd and mdhd are zeroed.
 *  - Handler names (hdlr) are blanked.
 *  - Any track that is not video or audio (e.g. Pixel 'mett', iPhone 'mebx',
 *    GoPro GPS, subtitles) has its sample bytes zeroed in mdat and the track
 *    box is freed.
 *  - Unparseable vendor trailers after the movie data (e.g. Samsung SEF) are cut off.
 *
 * Pure Java on purpose: no Android imports, so it can be unit-tested on a desktop JVM.
 *
 * Author: kultrshok. MIT License.
 */
public final class Mp4Scrubber {

    // ---- user-facing categories -------------------------------------------------
    public static final String CAT_LOCATION = "Location (GPS)";
    public static final String CAT_DEVICE = "Device make & model";
    public static final String CAT_SOFTWARE = "Software / OS version";
    public static final String CAT_DATES = "Recording date & time";
    public static final String CAT_CAMERA = "Camera settings";
    public static final String CAT_XMP = "Embedded XMP document";
    public static final String CAT_TRACK = "Hidden data track";
    public static final String CAT_TRAILER = "Vendor data tacked on the end";
    public static final String CAT_HANDLER = "Handler / encoder names";
    public static final String CAT_OTHER = "Other tags";

    /** What was found (dry run) or removed (scrub). */
    public static final class Report {
        public final Set<String> categories = new LinkedHashSet<>();
        public final List<String> details = new ArrayList<>();
        public String recordedUtc;          // human readable original timestamp, if any
        public int boxesFreed;
        public int tracksRemoved;
        public long bytesZeroed;
        public boolean trailerRemoved;

        public boolean isClean() { return categories.isEmpty(); }
    }

    public static final class UnsupportedFormatException extends IOException {
        public UnsupportedFormatException(String msg) { super(msg); }
    }

    /** Optional progress callback (0..1). */
    public interface Progress { void update(float fraction); }

    // ---- box whitelists --------------------------------------------------------
    private static final Set<String> TOP_KEEP = set("ftyp", "moov", "mdat", "free", "skip", "wide",
            "moof", "mfra", "sidx", "styp", "ssix", "emsg", "prft", "pdin");
    private static final Set<String> MOOV_KEEP = set("mvhd", "trak", "mvex", "iods");
    private static final Set<String> TRAK_KEEP = set("tkhd", "edts", "tref", "mdia", "tapt", "txas",
            "load", "imap", "clip", "matt", "kmat");
    private static final Set<String> MDIA_KEEP = set("mdhd", "hdlr", "minf", "elng");
    private static final Set<String> AV_HANDLERS = set("vide", "soun");

    // ---- marker table for reporting --------------------------------------------
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();
    static {
        MARKERS.put("©xyz", CAT_LOCATION);
        MARKERS.put("iso6709", CAT_LOCATION);
        MARKERS.put("location", CAT_LOCATION);
        MARKERS.put("gpslatitude", CAT_LOCATION);
        MARKERS.put("gpslongitude", CAT_LOCATION);
        MARKERS.put("gpscoordinates", CAT_LOCATION);
        MARKERS.put("©mak", CAT_DEVICE);
        MARKERS.put("©mod", CAT_DEVICE);
        MARKERS.put("com.android.model", CAT_DEVICE);
        MARKERS.put("com.android.manufacturer", CAT_DEVICE);
        MARKERS.put("quicktime.make", CAT_DEVICE);
        MARKERS.put("quicktime.model", CAT_DEVICE);
        MARKERS.put("tiff:model", CAT_DEVICE);
        MARKERS.put("tiff:make", CAT_DEVICE);
        MARKERS.put("©swr", CAT_SOFTWARE);
        MARKERS.put("©too", CAT_SOFTWARE);
        MARKERS.put("com.android.version", CAT_SOFTWARE);
        MARKERS.put("quicktime.software", CAT_SOFTWARE);
        MARKERS.put("creatortool", CAT_SOFTWARE);
        MARKERS.put("©day", CAT_DATES);
        MARKERS.put("creationdate", CAT_DATES);
        MARKERS.put("createdate", CAT_DATES);
        MARKERS.put("datetimeoriginal", CAT_DATES);
        MARKERS.put("modifydate", CAT_DATES);
        MARKERS.put("capture.fps", CAT_CAMERA);
        MARKERS.put("lensmodel", CAT_CAMERA);
        MARKERS.put("focallength", CAT_CAMERA);
        MARKERS.put("x:xmpmeta", CAT_XMP);
        MARKERS.put("<?xpacket", CAT_XMP);
    }
    private static final byte[][] MARKER_BYTES;
    private static final String[] MARKER_CATS;
    static {
        MARKER_BYTES = new byte[MARKERS.size()][];
        MARKER_CATS = new String[MARKERS.size()];
        int i = 0;
        for (Map.Entry<String, String> e : MARKERS.entrySet()) {
            MARKER_BYTES[i] = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
            MARKER_CATS[i++] = e.getValue();
        }
    }

    private static final long MAC_EPOCH_OFFSET = 2082844800L; // 1904-01-01 -> 1970-01-01
    private static final int SCAN_CAP = 8 * 1024 * 1024;

    private final RandomAccessFile raf;
    private final boolean dryRun;
    private final Report report = new Report();
    private final Progress progress;
    private long fileLen;
    private int trackNo;

    private Mp4Scrubber(RandomAccessFile raf, boolean dryRun, Progress progress) throws IOException {
        this.raf = raf;
        this.dryRun = dryRun;
        this.progress = progress;
        this.fileLen = raf.length();
    }

    /** Look but don't touch: report what hidden data the file carries. */
    public static Report scan(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return new Mp4Scrubber(r, true, null).run();
        }
    }

    /** Scrub the file IN PLACE. Work on a copy, never the user's original. */
    public static Report scrub(File f, Progress p) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            Report rep = new Mp4Scrubber(r, false, p).run();
            r.getFD().sync();
            return rep;
        }
    }

    // ============================================================================

    private static final class Box {
        final long pos; final long size; final int hdr; final String type;
        Box(long pos, long size, int hdr, String type) { this.pos = pos; this.size = size; this.hdr = hdr; this.type = type; }
        long payload() { return pos + hdr; }
        long end() { return pos + size; }
    }

    private Report run() throws IOException {
        if (fileLen < 16) throw new UnsupportedFormatException("File is too small to be a video.");
        Box first = readBox(0, fileLen);
        if (first == null || !set("ftyp", "moov", "mdat", "wide", "free", "skip").contains(first.type)) {
            throw new UnsupportedFormatException("Not an MP4/MOV/3GP video (no ISO-BMFF header).");
        }
        boolean seenMoov = false, seenMdat = false;
        long pos = 0;
        while (pos < fileLen) {
            Box b = (fileLen - pos >= 8) ? readBox(pos, fileLen) : null;
            if (b == null) {
                if (seenMoov && seenMdat) { handleTrailer(pos); break; }
                throw new UnsupportedFormatException("Video structure is damaged or unsupported.");
            }
            switch (b.type) {
                case "moov":
                    seenMoov = true;
                    processMoov(b);
                    break;
                case "mdat":
                    seenMdat = true;
                    break;
                case "free":
                case "skip":
                    wipeFiller(b, "top-level " + b.type);
                    break;
                case "cmov":
                    throw new UnsupportedFormatException("Compressed movie header (cmov) is not supported.");
                default:
                    if (!TOP_KEEP.contains(b.type)) neutralize(b, "top-level '" + b.type + "'", null);
            }
            if (progress != null) progress.update(Math.min(1f, (float) b.end() / fileLen));
            pos = b.end();
        }
        if (!seenMoov) throw new UnsupportedFormatException("No movie header found (moov missing).");
        if (progress != null) progress.update(1f);
        return report;
    }

    // ---- moov / trak / mdia ----------------------------------------------------

    private void processMoov(Box moov) throws IOException {
        for (Box c : children(moov)) {
            if (c.type.equals("mvhd")) zeroTimes(c, "movie header");
            else if (c.type.equals("trak")) processTrak(c);
            else if (c.type.equals("free") || c.type.equals("skip")) wipeFiller(c, "moov/" + c.type);
            else if (!MOOV_KEEP.contains(c.type)) neutralize(c, "moov/" + c.type, null);
        }
    }

    private void processTrak(Box trak) throws IOException {
        trackNo++;
        String handler = null;
        Box stbl = null;
        for (Box c : children(trak)) {
            switch (c.type) {
                case "tkhd": zeroTimes(c, "track " + trackNo + " header"); break;
                case "mdia": {
                    for (Box m : children(c)) {
                        switch (m.type) {
                            case "mdhd": zeroTimes(m, "track " + trackNo + " media header"); break;
                            case "hdlr": handler = processHdlr(m); break;
                            case "minf":
                                for (Box mi : children(m)) if (mi.type.equals("stbl")) stbl = mi;
                                break;
                            case "free":
                            case "skip":
                                wipeFiller(m, "trak" + trackNo + "/mdia/" + m.type);
                                break;
                            default:
                                if (!MDIA_KEEP.contains(m.type)) neutralize(m, "trak" + trackNo + "/mdia/" + m.type, null);
                        }
                    }
                    break;
                }
                case "free":
                case "skip":
                    wipeFiller(c, "trak" + trackNo + "/" + c.type);
                    break;
                default:
                    if (!TRAK_KEEP.contains(c.type)) neutralize(c, "trak" + trackNo + "/" + c.type, null);
            }
        }
        if (handler != null && !AV_HANDLERS.contains(handler)) {
            long zeroed = (stbl != null) ? wipeSamples(stbl) : 0;
            report.categories.add(CAT_TRACK);
            report.tracksRemoved++;
            report.details.add(String.format(Locale.US,
                    "Track %d (handler '%s'): %s of sample data zeroed, track removed",
                    trackNo, handler, human(zeroed)));
            neutralize(trak, "trak" + trackNo, CAT_TRACK);
        }
    }

    /** Returns the 4CC handler type and blanks the handler name. */
    private String processHdlr(Box h) throws IOException {
        long p = h.payload();
        if (h.size < h.hdr + 12) return null;
        byte[] t = read(p + 8, 4);
        String handler = new String(t, StandardCharsets.ISO_8859_1);
        long nameStart = p + 24;
        if (nameStart < h.end()) {
            byte[] name = read(nameStart, (int) Math.min(256, h.end() - nameStart));
            if (!allZero(name)) {
                report.categories.add(CAT_HANDLER);
                String n = printable(name);
                report.details.add("Track " + trackNo + " handler name '" + n + "' blanked");
                zero(nameStart, h.end() - nameStart);
            }
        }
        return handler;
    }

    // ---- sample wiping for non-AV tracks ------------------------------------------

    private long wipeSamples(Box stbl) throws IOException {
        long[] sizes = null;
        long[] chunkOffsets = null;
        long[][] stsc = null;
        for (Box c : children(stbl)) {
            long p = c.payload();
            switch (c.type) {
                case "stsz": {
                    long fixed = u32(p + 4), count = u32(p + 8);
                    sizes = new long[(int) count];
                    if (fixed != 0) Arrays.fill(sizes, fixed);
                    else { byte[] d = read(p + 12, (int) (count * 4)); for (int i = 0; i < count; i++) sizes[i] = be32(d, i * 4); }
                    break;
                }
                case "stz2": {
                    int field = read(p + 7, 1)[0] & 0xff; long count = u32(p + 8);
                    sizes = new long[(int) count];
                    byte[] d = read(p + 12, (int) ((count * field + 7) / 8));
                    for (int i = 0; i < count; i++) {
                        if (field == 4) { int b = d[i / 2] & 0xff; sizes[i] = (i % 2 == 0) ? (b >> 4) : (b & 0xf); }
                        else if (field == 8) sizes[i] = d[i] & 0xff;
                        else sizes[i] = ((d[i * 2] & 0xff) << 8) | (d[i * 2 + 1] & 0xff);
                    }
                    break;
                }
                case "stco": {
                    long count = u32(p + 4);
                    chunkOffsets = new long[(int) count];
                    byte[] d = read(p + 8, (int) (count * 4));
                    for (int i = 0; i < count; i++) chunkOffsets[i] = be32(d, i * 4);
                    break;
                }
                case "co64": {
                    long count = u32(p + 4);
                    chunkOffsets = new long[(int) count];
                    byte[] d = read(p + 8, (int) (count * 8));
                    for (int i = 0; i < count; i++) chunkOffsets[i] = (be32(d, i * 8) << 32) | be32(d, i * 8 + 4);
                    break;
                }
                case "stsc": {
                    long count = u32(p + 4);
                    stsc = new long[(int) count][2];
                    byte[] d = read(p + 8, (int) (count * 12));
                    for (int i = 0; i < count; i++) { stsc[i][0] = be32(d, i * 12); stsc[i][1] = be32(d, i * 12 + 4); }
                    break;
                }
                default: break;
            }
        }
        if (sizes == null || chunkOffsets == null || stsc == null || stsc.length == 0) return 0;
        long total = 0;
        int sample = 0, e = 0;
        for (int chunk = 1; chunk <= chunkOffsets.length && sample < sizes.length; chunk++) {
            while (e + 1 < stsc.length && stsc[e + 1][0] <= chunk) e++;
            long off = chunkOffsets[chunk - 1];
            for (long s = 0; s < stsc[e][1] && sample < sizes.length; s++, sample++) {
                long len = sizes[sample];
                if (off >= 0 && len > 0 && off + len <= fileLen) {
                    scanMarkers(off, len, null);
                    zero(off, len);
                    total += len;
                }
                off += len;
            }
        }
        return total;
    }

    // ---- primitives ------------------------------------------------------------

    /** Turn a box into a same-size 'free' box with zeroed contents. */
    private void neutralize(Box b, String label, String forcedCat) throws IOException {
        boolean hit = scanMarkers(b.payload(), b.size - b.hdr, forcedCat);
        if (!hit && forcedCat == null) report.categories.add(CAT_OTHER);
        report.boxesFreed++;
        report.details.add(label + " (" + human(b.size) + ") removed");
        if (!dryRun) {
            raf.seek(b.pos + 4);
            raf.write("free".getBytes(StandardCharsets.ISO_8859_1));
            zero(b.payload(), b.size - b.hdr);
        }
    }

    /** Existing padding boxes: zero any leftover junk inside them. */
    private void wipeFiller(Box b, String label) throws IOException {
        long len = b.size - b.hdr;
        if (len <= 0) return;
        if (isRegionZero(b.payload(), len)) return;
        boolean hit = scanMarkers(b.payload(), len, null);
        if (hit) report.details.add(label + " padding held leftover metadata, wiped");
        zero(b.payload(), len);
    }

    private void handleTrailer(long pos) throws IOException {
        long len = fileLen - pos;
        scanMarkers(pos, len, null);
        report.categories.add(CAT_TRAILER);
        report.trailerRemoved = true;
        report.details.add(human(len) + " of unstructured vendor data after the video removed");
        if (!dryRun) { raf.setLength(pos); fileLen = pos; }
    }

    private void zeroTimes(Box b, String label) throws IOException {
        long p = b.payload();
        int version = read(p, 1)[0] & 0xff;
        int len = (version == 1) ? 16 : 8;
        if (b.size < b.hdr + 4 + len) return;
        byte[] t = read(p + 4, len);
        if (allZero(t)) return;
        long created = (version == 1) ? ((be32(t, 0) << 32) | be32(t, 4)) : be32(t, 0);
        if (report.recordedUtc == null && created > MAC_EPOCH_OFFSET) {
            report.recordedUtc = utc(created - MAC_EPOCH_OFFSET);
        }
        report.categories.add(CAT_DATES);
        report.details.add(label + " timestamps zeroed");
        zero(p + 4, len);
    }

    private boolean scanMarkers(long pos, long len, String forcedCat) throws IOException {
        if (forcedCat != null) report.categories.add(forcedCat);
        int n = (int) Math.min(len, SCAN_CAP);
        if (n <= 0) return false;
        byte[] d = read(pos, n);
        for (int i = 0; i < d.length; i++) { byte c = d[i]; if (c >= 'A' && c <= 'Z') d[i] = (byte) (c + 32); }
        boolean hit = false;
        for (int m = 0; m < MARKER_BYTES.length; m++) {
            if (indexOf(d, MARKER_BYTES[m]) >= 0) { report.categories.add(MARKER_CATS[m]); hit = true; }
        }
        return hit;
    }

    private void zero(long pos, long len) throws IOException {
        if (len <= 0) return;
        report.bytesZeroed += len;
        if (dryRun) return;
        byte[] z = new byte[(int) Math.min(len, 1 << 16)];
        raf.seek(pos);
        long left = len;
        while (left > 0) { int k = (int) Math.min(left, z.length); raf.write(z, 0, k); left -= k; }
    }

    private boolean isRegionZero(long pos, long len) throws IOException {
        byte[] buf = new byte[(int) Math.min(len, 1 << 16)];
        raf.seek(pos);
        long left = len;
        while (left > 0) {
            int k = (int) Math.min(left, buf.length);
            raf.readFully(buf, 0, k);
            for (int i = 0; i < k; i++) if (buf[i] != 0) return false;
            left -= k;
        }
        return true;
    }

    private List<Box> children(Box parent) throws IOException {
        List<Box> out = new ArrayList<>();
        long pos = parent.payload();
        long end = parent.end();
        while (pos + 8 <= end) {
            Box b = readBox(pos, end);
            if (b == null) break;
            out.add(b);
            pos = b.end();
        }
        return out;
    }

    private Box readBox(long pos, long limit) throws IOException {
        if (pos + 8 > limit) return null;
        byte[] h = read(pos, 8);
        long size = be32(h, 0);
        int hdr = 8;
        if (!validType(h)) return null;
        String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
        if (size == 1) {
            if (pos + 16 > limit) return null;
            byte[] l = read(pos + 8, 8);
            size = (be32(l, 0) << 32) | be32(l, 4);
            hdr = 16;
        } else if (size == 0) {
            size = limit - pos;
        }
        if (size < hdr || pos + size > limit) return null;
        return new Box(pos, size, hdr, type);
    }

    private static boolean validType(byte[] h) {
        for (int i = 4; i < 8; i++) {
            int c = h[i] & 0xff;
            if (!((c >= 0x20 && c <= 0x7e) || c == 0xa9)) return false;
        }
        return true;
    }

    private byte[] read(long pos, int len) throws IOException {
        byte[] b = new byte[len];
        raf.seek(pos);
        raf.readFully(b);
        return b;
    }

    private long u32(long pos) throws IOException { return be32(read(pos, 4), 0); }

    private static long be32(byte[] d, int o) {
        return ((long) (d[o] & 0xff) << 24) | ((d[o + 1] & 0xff) << 16) | ((d[o + 2] & 0xff) << 8) | (d[o + 3] & 0xff);
    }

    private static boolean allZero(byte[] b) { for (byte x : b) if (x != 0) return false; return true; }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static String printable(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) { int c = x & 0xff; if (c >= 0x20 && c < 0x7f) sb.append((char) c); }
        return sb.toString().trim();
    }

    private static String utc(long epochSec) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(epochSec * 1000L));
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static Set<String> set(String... s) { return new LinkedHashSet<>(Arrays.asList(s)); }
}
