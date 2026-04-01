package net.javacrumbs.cloffle.compiler;

import clojure.lang.RT;
import org.junit.Test;

/**
 * Loads {@code clojure/core.clj} through {@link RT#init()} ({@code RT.init()} → {@link RT#load(String)}).
 * Fails on the first form that breaks the Truffle bytecode path — fix and extend until green.
 */
public class CoreBytecodeLoadSmokeTest {

    @Test
    public void rtInitLoadsCoreCljWithBytecodeExecution() {
        RT.init();
    }
}
