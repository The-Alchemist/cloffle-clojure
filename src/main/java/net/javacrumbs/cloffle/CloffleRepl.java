package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public class CloffleRepl {

    public static void main(String[] args) throws IOException {
        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {

            if (args.length > 0) {
                Value result = context.eval("cloffle", String.join(" ", args));
                System.out.println(formatResult(result));
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Cloffle REPL (Clojure on Truffle)");
            System.out.println("Type an expression, or :quit to exit.");
            System.out.println();

            StringBuilder buffer = new StringBuilder();
            boolean multiline = false;

            while (true) {
                System.out.print(multiline ? "  .. " : "cloffle=> ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (!multiline && trimmed.equals(":quit")) {
                    break;
                }

                buffer.append(line).append('\n');
                String input = buffer.toString().trim();

                if (!isBalanced(input)) {
                    multiline = true;
                    continue;
                }

                multiline = false;
                buffer.setLength(0);

                if (input.isEmpty()) {
                    continue;
                }

                try {
                    Value result = context.eval("cloffle", input);
                    System.out.println(formatResult(result));
                } catch (PolyglotException e) {
                    if (e.isInternalError()) {
                        System.err.println("Internal error: " + e.getMessage());
                    } else {
                        System.err.println("Error: " + e.getMessage());
                    }
                }
            }

            System.out.println("Bye.");
        }
    }

    private static String formatResult(Value result) {
        if (result == null || result.isNull()) {
            return "nil";
        }
        return result.toString();
    }

    private static boolean isBalanced(String input) {
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        boolean inString = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && inString) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == ';') break; // rest of line is comment
            switch (c) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
            }
        }
        return parens <= 0 && brackets <= 0 && braces <= 0 && !inString;
    }
}
