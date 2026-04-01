package clojure.lang;

import net.javacrumbs.cloffle.compiler.CloffleCompiler;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Subprocess entry point for bisecting the ArrayIndexOutOfBoundsException
 * in bytecode mode.
 *
 * Mode 1 (core bisect): Load first N lines of core.clj, then run test form.
 *   -Dcloffle.bisect.core.lines=N
 *   -Dcloffle.bisect.test.form="(some clojure form)"
 *
 * Mode 2 (full harness): Load full core, clojure.main, then load a test script.
 *   -Dcloffle.bisect.mode=harness
 *   -Dcloffle.bisect.script=path/to/script.clj
 */
public class CoreBisectTest {

    private static final String CORE_CLJ_PATH = "src/clj/clojure/core.clj";

    static String readCoreSlice(int lineLimit) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(CORE_CLJ_PATH))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < lineLimit) {
                lines.add(line);
                count++;
            }
        }
        String text = String.join("\n", lines);
        int depth = 0;
        int lastBalanced = 0;
        boolean inString = false;
        boolean escape = false;
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '\n') { inComment = false; continue; }
            if (inComment) continue;
            if (c == ';' && !inString) { inComment = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) lastBalanced = i + 1;
            }
        }
        return text.substring(0, lastBalanced);
    }

    public static void main(String[] args) {
        String mode = System.getProperty("cloffle.bisect.mode", "core");

        try {
            if ("harness".equals(mode)) {
                runHarnessMode();
            } else {
                runCoreBisectMode();
            }
        } catch (Throwable t) {
            System.err.println("FAIL: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runCoreBisectMode() throws Exception {
        int lineLimit = Integer.parseInt(System.getProperty("cloffle.bisect.core.lines", "0"));
        String testForm = System.getProperty("cloffle.bisect.test.form",
                "(let* [a (. System getProperty \"java.version\")] (if a a \"unknown\"))");
        if (lineLimit <= 0) {
            System.err.println("cloffle.bisect.core.lines not set or invalid");
            System.exit(1);
        }
        String slice = readCoreSlice(lineLimit);
        System.err.println("Loading core.clj slice (" + lineLimit + " lines, "
                + slice.length() + " chars) ...");
        Compiler.load(new StringReader(slice), "core.clj", "core.clj");
        System.err.println("Core slice loaded OK. Testing form...");
        Object result = CloffleCompiler.executeForm(
                LispReader.read(new LineNumberingPushbackReader(new StringReader(testForm)),
                        false, null, false, null));
        System.out.println("OK: " + result);
    }

    private static void runHarnessMode() throws Exception {
        String scriptPath = System.getProperty("cloffle.bisect.script",
                "src/script/run_test_surefire.clj");
        System.err.println("Simulating clojure.main with script: " + scriptPath);
        clojure.main.main(new String[]{scriptPath});
    }
}
