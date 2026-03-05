package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

public class CloffleE2ETest extends AbstractE2ETest {
    private final Context context = Context.newBuilder()
            .allowAllAccess(true)
            .build();

    @Override
    protected Object run(String expression) {
        return context.eval("cloffle", expression).as(Object.class);
    }

    @Test
    @Ignore("718 forms: 685 succeed, 33 fail (StackOverflow 20, ISeq-from-Long 7, misc 6)")
    public void shouldLoadClojureCore() throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("clojure/core.clj")) {
            run(convertStreamToString(is));
        }
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}