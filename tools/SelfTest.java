import com.kultrshok.scrubgnome.core.Mp4Scrubber;
import com.kultrshok.scrubgnome.core.UltrasonicAnalyzer;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

/** Scrubs the given files in place and verifies them; runs the tone detector on raw PCM. */
public class SelfTest {
    public static void main(String[] a) throws Exception {
        for (int i = 0; i < 2; i++) {
            File f = new File(a[i]);
            Mp4Scrubber.Report before = Mp4Scrubber.scan(f);
            System.out.println(f.getName() + " before: " + before.categories);
            if (before.isClean()) throw new AssertionError("test file should carry metadata");
            Mp4Scrubber.scrub(f, null);
            Mp4Scrubber.Report after = Mp4Scrubber.scan(f);
            System.out.println(f.getName() + " after : " + (after.isClean() ? "CLEAN" : after.categories));
            if (!after.isClean()) throw new AssertionError("metadata survived");
        }
        byte[] d = Files.readAllBytes(new File(a[2]).toPath());
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        float[] s = new float[d.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = bb.getShort() / 32768f;
        UltrasonicAnalyzer u = new UltrasonicAnalyzer(48000);
        u.feed(s, s.length);
        UltrasonicAnalyzer.Result r = u.finish();
        System.out.println("tone check: " + r.level + " - " + r.headline);
        if (r.level != UltrasonicAnalyzer.Level.HIGH) throw new AssertionError("19 kHz beacon not detected");
    }
}
