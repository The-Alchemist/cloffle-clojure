package net.javacrumbs.cloffle;

import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Launches Cloffle with a Debug Adapter Protocol (DAP) server so that
 * VS Code (or any DAP client) can set breakpoints, step through code,
 * and inspect variables in Cloffle programs.
 *
 * <p>The DAP server is provided by GraalVM's built-in {@code dap} instrument.
 * It listens on a TCP port (default 4711) and speaks the standard DAP wire
 * protocol, so no custom VS Code extension is needed — a generic
 * {@code launch.json} "attach" configuration is sufficient.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... net.javacrumbs.cloffle.CloffleDapMain [options] [script.clj] [args...]
 *
 *   Options:
 *     --dap-port PORT    DAP server port (default: 4711)
 *     --dap-suspend      Suspend execution until debugger attaches (default)
 *     --dap-no-suspend   Start executing immediately; debugger can attach later
 *     --dap-wait         Wait for debugger to attach before running any code
 *     -e CODE            Evaluate CODE instead of loading a file
 *     -r                 Start a REPL after loading files / evaluating code
 *     --                 End of options; remaining args are script args
 * </pre>
 *
 * <p>Example with VS Code:
 * <pre>
 *   # Terminal:
 *   make cloffle-dap FILE=my_script.clj
 *
 *   # VS Code launch.json:
 *   {
 *     "type": "node",
 *     "request": "attach",
 *     "name": "Attach Cloffle DAP",
 *     "debugServer": 4711
 *   }
 * </pre>
 */
public final class CloffleDapMain {

    private static final int DEFAULT_DAP_PORT = 4711;

    public static void main(String[] args) throws IOException {
        RT.init();

        int port = DEFAULT_DAP_PORT;
        boolean suspend = true;
        boolean waitAttached = true;
        String evalCode = null;
        String scriptFile = null;
        String[] scriptArgs = new String[0];

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--dap-port" -> {
                    i++;
                    if (i < args.length) {
                        port = Integer.parseInt(args[i]);
                    }
                }
                case "--dap-suspend" -> suspend = true;
                case "--dap-no-suspend" -> suspend = false;
                case "--dap-wait" -> waitAttached = true;
                case "--dap-no-wait" -> waitAttached = false;
                case "-e" -> {
                    i++;
                    if (i < args.length) {
                        evalCode = args[i];
                    }
                }
                case "-r" -> { } // optional; no-script path always uses the Polyglot Cloffle REPL (see runRepl)
                case "--" -> {
                    i++;
                    if (i < args.length) {
                        scriptArgs = Arrays.copyOfRange(args, i, args.length);
                    }
                    i = args.length;
                    continue;
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        System.err.println("Unknown option: " + args[i]);
                        System.err.println("Usage: CloffleDapMain [--dap-port PORT] [--dap-no-suspend] [-e CODE | script.clj] [-r]");
                        System.exit(1);
                    }
                    scriptFile = args[i];
                    if (i + 1 < args.length) {
                        scriptArgs = Arrays.copyOfRange(args, i + 1, args.length);
                    }
                    i = args.length;
                    continue;
                }
            }
            i++;
        }

        System.err.println("[Cloffle DAP] Starting DAP server on port " + port);
        if (waitAttached) {
            System.err.println("[Cloffle DAP] Will wait for debugger to attach before executing");
        }
        if (suspend) {
            System.err.println("[Cloffle DAP] Execution will suspend at first statement");
        }
        System.err.println("[Cloffle DAP] Attach VS Code with: { \"type\": \"node\", \"request\": \"attach\", \"debugServer\": " + port + " }");

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", String.valueOf(suspend))
                .option("dap.WaitAttached", String.valueOf(waitAttached))
                .build();
             Context context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .in(System.in)
                .out(System.out)
                .err(System.err)
                .build()) {

            if (evalCode != null) {
                runEval(context, evalCode);
            } else if (scriptFile != null) {
                runScript(context, scriptFile, scriptArgs);
            } else {
                runRepl(context, scriptArgs);
            }
        } catch (PolyglotException e) {
            if (e.isExit()) {
                System.exit(e.getExitStatus());
            }
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runEval(Context context, String code) {
        Source src = Source.newBuilder("cloffle", code, "eval").buildLiteral();
        var result = context.eval(src);
        if (result != null && !result.isNull()) {
            System.out.println(result);
        }
    }

    private static void runScript(Context context, String path, String[] scriptArgs) throws IOException {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            System.err.println("File not found: " + path);
            System.exit(1);
        }

        String argsBinding = buildArgsBinding(path, scriptArgs);
        if (!argsBinding.isEmpty()) {
            Source argsSrc = Source.newBuilder("cloffle", argsBinding, "set-args").buildLiteral();
            context.eval(argsSrc);
        }

        String code = Files.readString(filePath);
        Source src = Source.newBuilder("cloffle", code, filePath.getFileName().toString())
                .uri(filePath.toUri())
                .build();
        context.eval(src);
    }

    private static void runRepl(Context context, String[] args) throws IOException {
        if (args.length == 0) {
            CloffleRepl.runInteractiveRepl(context);
            return;
        }
        String argsLiteral = toClojureVectorLiteral(prepend("-r", args));
        String code = "(require 'cloffle.main) (apply cloffle.main/main (seq " + argsLiteral + "))";
        Source src = Source.newBuilder("cloffle", code, "cloffle-dap-repl").buildLiteral();
        context.eval(src);
    }

    private static String buildArgsBinding(String scriptFile, String[] args) {
        if (args.length == 0) {
            return "(alter-var-root #'*command-line-args* (constantly nil))" +
                   "(alter-var-root #'*file* (constantly \"" + escapeForClojure(scriptFile) + "\"))";
        }
        String argsVec = toClojureVectorLiteral(args);
        return "(alter-var-root #'*command-line-args* (constantly (seq " + argsVec + ")))" +
               "(alter-var-root #'*file* (constantly \"" + escapeForClojure(scriptFile) + "\"))";
    }

    private static String[] prepend(String first, String[] rest) {
        String[] result = new String[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private static String toClojureVectorLiteral(String[] args) {
        if (args == null || args.length == 0) return "[]";
        return Arrays.stream(args)
                .map(CloffleDapMain::escapeForClojureString)
                .collect(Collectors.joining(" ", "[", "]"));
    }

    private static String escapeForClojureString(String s) {
        if (s == null) return "\"\"";
        return "\"" + escapeForClojure(s) + "\"";
    }

    private static String escapeForClojure(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
