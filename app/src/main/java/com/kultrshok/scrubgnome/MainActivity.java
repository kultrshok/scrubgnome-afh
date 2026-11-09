package com.kultrshok.scrubgnome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.kultrshok.scrubgnome.core.Mp4Scrubber;
import com.kultrshok.scrubgnome.core.UltrasonicAnalyzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scrubgnome Away from Home - pick a video, scrub its metadata, save a clean copy.
 * One screen, no internet permission, nothing leaves the phone.
 * Author: kultrshok. MIT License.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 42;

    // palette
    private static final int BG = 0xFF121417, CARD = 0xFF1E2228, TEXT = 0xFFE6E6E6, MUTED = 0xFF9AA4AE;
    private static final int ACCENT = 0xFF7BC67E, ACCENT_DARK = 0xFF2F5D33, WARN = 0xFFE8B04B, DANGER = 0xFFE5534B, INFO = 0xFF6CB6FF;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // state
    private File workFile;
    private String sourceName, outExt = ".mp4", outMime = "video/mp4";
    private Uri savedUri;
    private boolean busy;

    // views
    private TextView twigSays, fileName, findings, findingsGeek, toneHead, toneBody, toneGeek, status, savedText;
    private LinearLayout fileCard, toneCard, savedCard;
    private Button pickBtn, scrubBtn, toneBtn, shareBtn, againBtn;
    private ProgressBar progress;
    private Switch geek;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        clearWork();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (isFinishing()) clearWork();
    }

    /** "Share to Scrubgnome" from the gallery lands here. */
    private void handleIncoming(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri u;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            u = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else {
            //noinspection deprecation
            u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (u != null) loadVideo(u);
    }

    // =============================================================================
    // UI
    // =============================================================================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView twig = new ImageView(this);
        twig.setImageResource(R.drawable.twig);
        twig.setAdjustViewBounds(true);
        twig.setContentDescription("Twig the tinker gnome");
        header.addView(twig, new LinearLayout.LayoutParams(dp(88), dp(88)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Scrubgnome", 26, TEXT, true));
        titles.addView(text("Away from Home", 16, ACCENT, false));
        header.addView(titles);
        col.addView(header);

        twigSays = text(getString(R.string.twig_intro), 15, TEXT, false);
        LinearLayout bubble = card();
        bubble.addView(twigSays);
        col.addView(bubble, cardParams());

        pickBtn = button("📂  Pick a video", true);
        pickBtn.setOnClickListener(v -> pick());
        col.addView(pickBtn, btnParams());

        // file + findings
        fileCard = card();
        fileName = text("", 15, TEXT, true);
        findings = text("", 15, TEXT, false);
        findingsGeek = text("", 12, MUTED, false);
        findingsGeek.setTypeface(Typeface.MONOSPACE);
        fileCard.addView(fileName);
        fileCard.addView(spacer(6));
        fileCard.addView(findings);
        fileCard.addView(findingsGeek);
        fileCard.setVisibility(View.GONE);
        col.addView(fileCard, cardParams());

        scrubBtn = button("🧹  Scrub & save clean copy", true);
        scrubBtn.setOnClickListener(v -> scrub());
        scrubBtn.setVisibility(View.GONE);
        col.addView(scrubBtn, btnParams());

        toneBtn = button("👂  Listen for hidden tracking tones", false);
        toneBtn.setOnClickListener(v -> listen());
        toneBtn.setVisibility(View.GONE);
        col.addView(toneBtn, btnParams());

        // tone result
        toneCard = card();
        toneHead = text("", 17, TEXT, true);
        toneBody = text("", 14, TEXT, false);
        toneGeek = text("", 12, MUTED, false);
        toneGeek.setTypeface(Typeface.MONOSPACE);
        toneCard.addView(toneHead);
        toneCard.addView(spacer(4));
        toneCard.addView(toneBody);
        toneCard.addView(toneGeek);
        toneCard.setVisibility(View.GONE);
        col.addView(toneCard, cardParams());

        // progress
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        col.addView(progress, new LinearLayout.LayoutParams(-1, dp(10)));
        status = text("", 13, MUTED, false);
        col.addView(status);

        // saved
        savedCard = card();
        savedText = text("", 15, TEXT, false);
        savedCard.addView(savedText);
        shareBtn = button("📤  Share / upload", true);
        shareBtn.setOnClickListener(v -> share());
        savedCard.addView(shareBtn, btnParams());
        againBtn = button("Scrub another", false);
        againBtn.setOnClickListener(v -> reset());
        savedCard.addView(againBtn, btnParams());
        savedCard.setVisibility(View.GONE);
        col.addView(savedCard, cardParams());

        // geek toggle + footer
        geek = new Switch(this);
        geek.setText("Geek mode (show the gory details)");
        geek.setTextColor(MUTED);
        geek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        geek.setOnCheckedChangeListener((b, on) -> applyGeek());
        col.addView(geek, cardParams());

        TextView level = text(getString(R.string.footer_level), 12, MUTED, false);
        col.addView(level, cardParams());
        TextView ai = text("Generated with AI", 11, MUTED, false);
        ai.setGravity(Gravity.END);
        col.addView(ai, new LinearLayout.LayoutParams(-1, -2));

        applyGeek();
        return root;
    }

    private void applyGeek() {
        int vis = geek.isChecked() ? View.VISIBLE : View.GONE;
        findingsGeek.setVisibility(findingsGeek.getText().length() > 0 ? vis : View.GONE);
        toneGeek.setVisibility(toneGeek.getText().length() > 0 ? vis : View.GONE);
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        pickBtn.setEnabled(!b);
        scrubBtn.setEnabled(!b);
        toneBtn.setEnabled(!b);
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        progress.setProgress(0);
        status.setText(msg == null ? "" : msg);
        float a = b ? 0.5f : 1f;
        pickBtn.setAlpha(a); scrubBtn.setAlpha(a); toneBtn.setAlpha(a);
    }

    private void setProgress(float f) {
        ui.post(() -> progress.setProgress((int) (f * 1000)));
    }

    // =============================================================================
    // Pick + load
    // =============================================================================

    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2"});
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            loadVideo(data.getData());
        }
    }

    private void loadVideo(Uri uri) {
        if (busy) return;
        reset();
        setBusy(true, "Hauling it into my workshop… careful, careful…");
        worker.execute(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String name = "video";
                long size = -1;
                try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0);
                        if (!c.isNull(1)) size = c.getLong(1);
                    }
                }
                pickOutputType(cr.getType(uri), name);
                File dir = workDir();
                File f = new File(dir, "work" + outExt);
                try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
                    if (in == null) throw new IOException("Couldn't open that video.");
                    copy(in, out, size);
                }
                Mp4Scrubber.Report rep = Mp4Scrubber.scan(f);
                final String n = name;
                final long sz = f.length();
                ui.post(() -> {
                    workFile = f;
                    sourceName = n;
                    setBusy(false, null);
                    showFindings(rep, sz);
                });
            } catch (Mp4Scrubber.UnsupportedFormatException e) {
                fail("#@%&! " + e.getMessage() + " I only operate on MP4, MOV and 3GP, s'il vous plaît.");
            } catch (Exception e) {
                fail("Gah! Couldn't load that one: " + e.getMessage());
            }
        });
    }

    private void showFindings(Mp4Scrubber.Report rep, long size) {
        fileCard.setVisibility(View.VISIBLE);
        scrubBtn.setVisibility(View.VISIBLE);
        toneBtn.setVisibility(View.VISIBLE);
        fileName.setText(sourceName + "  ·  " + Mp4Scrubber.human(size));
        StringBuilder sb = new StringBuilder();
        if (rep.isClean()) {
            sb.append("Huh. Already squeaky clean. Suspicious… but clean. You can still save a fresh copy with a random name.");
            twigSays.setText("Nothing to rip out! Either you're a pro or someone beat me to it. I'm mildly offended.");
        } else {
            sb.append("Twig sniffed out these snitches:\n");
            for (String c : rep.categories) sb.append("  • ").append(c).append('\n');
            if (rep.recordedUtc != null) sb.append("\nFilmed: ").append(rep.recordedUtc).append(" (says the file)\n");
            if (sourceName != null && sourceName.matches(".*\\d{8}.*")) {
                sb.append("\nAlso, the file NAME has a date in it. Your clean copy gets a random name.\n");
            }
            twigSays.setText("Great gears! This thing is blabbing like a tourist. Hit Scrub and I'll zero every last tattletale byte.");
        }
        findings.setText(sb.toString().trim());
        findingsGeek.setText(geekLines(rep.details));
        applyGeek();
    }

    // =============================================================================
    // Scrub + save
    // =============================================================================

    private void scrub() {
        if (busy || workFile == null) return;
        setBusy(true, "Scrubbing! Zap… wipe… zero…");
        final File f = workFile;
        worker.execute(() -> {
            try {
                Mp4Scrubber.Report done = Mp4Scrubber.scrub(f, p -> setProgress(p * 0.3f));
                Mp4Scrubber.Report verify = Mp4Scrubber.scan(f);
                if (!verify.isClean()) {
                    String left = String.valueOf(verify.categories);
                    fail("#@%&! Something survived the scrub: " + left + ". Don't post this one. Tell kultrshok.");
                    return;
                }
                ui.post(() -> status.setText("Clean as a whistle. Filing it away…"));
                String outName = "scrubbed_" + randomHex(4) + outExt;
                Uri saved = saveToMovies(f, outName);
                ui.post(() -> {
                    savedUri = saved;
                    setBusy(false, null);
                    scrubBtn.setVisibility(View.GONE);
                    savedCard.setVisibility(View.VISIBLE);
                    savedText.setText("Saved and double-checked: zero metadata left.\n\n"
                            + "📁 Movies/Scrubgnome/" + outName + "\n\n"
                            + "Removed " + done.boxesFreed + " tag blocks"
                            + (done.tracksRemoved > 0 ? ", " + done.tracksRemoved + " hidden data track(s)" : "")
                            + (done.trailerRemoved ? ", a vendor trailer" : "")
                            + ". Video and sound untouched, not one pixel re-encoded.");
                    twigSays.setText("Ha HA! It works! It actually works! Go on, post it. The trackers can taunt my shiny clean file all they like.");
                });
            } catch (Exception e) {
                fail("Gah! Scrub failed: " + e.getMessage());
            }
        });
    }

    private Uri saveToMovies(File src, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, outMime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Scrubgnome");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri u = cr.insert(coll, v);
        if (u == null) throw new IOException("Android wouldn't give me a spot in Movies.");
        try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(u)) {
            if (out == null) throw new IOException("Couldn't write to Movies.");
            long total = src.length();
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                setProgress(0.3f + 0.7f * done / Math.max(1, total));
            }
        } catch (IOException e) {
            cr.delete(u, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
        return u;
    }

    private void share() {
        if (savedUri == null) return;
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(outMime);
        s.putExtra(Intent.EXTRA_STREAM, savedUri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Send the clean copy to…"));
    }

    // =============================================================================
    // Inaudible tone scan
    // =============================================================================

    private void listen() {
        if (busy || workFile == null) return;
        setBusy(true, "Ear to the speaker… listening for squeaks only bats and ad-trackers can hear…");
        final File f = workFile;
        worker.execute(() -> {
            try {
                UltrasonicAnalyzer.Result r = AudioToneScanner.scan(f, this::setProgress);
                ui.post(() -> {
                    setBusy(false, null);
                    showTone(r);
                });
            } catch (Exception e) {
                fail("Gah! My ear trumpet jammed: " + e.getMessage());
            }
        });
    }

    private void showTone(UltrasonicAnalyzer.Result r) {
        toneCard.setVisibility(View.VISIBLE);
        if (r == null) {
            toneHead.setTextColor(MUTED);
            toneHead.setText("No audio track");
            toneBody.setText("Silent movie! Nothing in here can whistle at anybody.");
            toneGeek.setText("");
            applyGeek();
            return;
        }
        String prefix;
        int color;
        switch (r.level) {
            case HIGH: prefix = "#@%&! "; color = DANGER;
                twigSays.setText("Aha! A beacon! Pfah, it squeaks like a rusty hamster. I can't cut it out in the pocket edition yet. Run this clip through desktop Scrubgnome's audio cleaning before posting.");
                break;
            case MEDIUM: prefix = "Hmm… "; color = WARN;
                twigSays.setText("Something is chirping up there. Could be a beacon, could be a cheap phone charger. If it matters, clean the audio on the desktop version.");
                break;
            case LOW: prefix = "Meh. "; color = INFO;
                twigSays.setText("Just hiss up top. Electronics, wind, the usual gremlins. Nothing that looks like a beacon.");
                break;
            case NO_ROOM: prefix = "Ha! "; color = ACCENT;
                twigSays.setText("Your phone chopped off everything above the beacon band when it recorded. Nowhere for a tracker to hide!");
                break;
            default: prefix = "All quiet. "; color = ACCENT;
                twigSays.setText("Not a peep up there. The ad-goblins have nothing to say about this one.");
        }
        toneHead.setTextColor(color);
        toneHead.setText(prefix + r.headline);
        toneBody.setText(r.explanation);
        toneGeek.setText(geekLines(r.details));
        applyGeek();
    }

    // =============================================================================
    // helpers
    // =============================================================================

    private void reset() {
        workFile = null;
        savedUri = null;
        sourceName = null;
        clearWork();
        fileCard.setVisibility(View.GONE);
        scrubBtn.setVisibility(View.GONE);
        toneBtn.setVisibility(View.GONE);
        toneCard.setVisibility(View.GONE);
        savedCard.setVisibility(View.GONE);
        findingsGeek.setText("");
        toneGeek.setText("");
        twigSays.setText(getString(R.string.twig_intro));
        status.setText("");
    }

    private void fail(String msg) {
        ui.post(() -> {
            setBusy(false, null);
            status.setTextColor(DANGER);
            status.setText(msg);
            twigSays.setText("Well, #@%&. That didn't go to plan. Read the red bit below.");
            ui.postDelayed(() -> status.setTextColor(MUTED), 6000);
        });
    }

    private void pickOutputType(String mime, String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if ("video/quicktime".equals(mime) || lower.endsWith(".mov")) { outExt = ".mov"; outMime = "video/quicktime"; }
        else if ((mime != null && mime.startsWith("video/3gpp")) || lower.endsWith(".3gp")) { outExt = ".3gp"; outMime = "video/3gpp"; }
        else { outExt = ".mp4"; outMime = "video/mp4"; }
    }

    private File workDir() {
        File d = new File(getCacheDir(), "work");
        if (!d.exists() && !d.mkdirs()) d = getCacheDir();
        return d;
    }

    private void clearWork() {
        File d = new File(getCacheDir(), "work");
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private void copy(InputStream in, OutputStream out, long total) throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) setProgress((float) done / total);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private static String geekLines(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append("› ").append(l).append('\n');
        return sb.toString().trim();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        return c;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primary ? Color.BLACK : TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(54));
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD);
        g.setCornerRadius(dp(14));
        if (!primary) g.setStroke(dp(1), ACCENT_DARK);
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        return p;
    }

    private LinearLayout.LayoutParams btnParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }
}
