/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/


package clojure.lang;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.IOException;

// Loads and validates libs via Truffle execution. The clojure.compile.path
// system property is accepted for compatibility but no .class files are
// produced -- all execution goes through the Truffle backend.

public class Compile{

private static final String PATH_PROP = "clojure.compile.path";
private static final String REFLECTION_WARNING_PROP = "clojure.compile.warn-on-reflection";
private static final String UNCHECKED_MATH_PROP = "clojure.compile.unchecked-math";

private static final Var compile_path = RT.var("clojure.core", "*compile-path*");
private static final Var compile = RT.var("clojure.core", "compile");
private static final Var warn_on_reflection = RT.var("clojure.core", "*warn-on-reflection*");
private static final Var unchecked_math = RT.var("clojure.core", "*unchecked-math*");

public static void main(String[] args) throws IOException, ClassNotFoundException{
	RT.init();
	OutputStreamWriter out = (OutputStreamWriter) RT.OUT.deref();
	PrintWriter err = RT.errPrintWriter();
	String path = System.getProperty(PATH_PROP, "target/classes");

    boolean warnOnReflection = System.getProperty(REFLECTION_WARNING_PROP, "false").equals("true");
    String uncheckedMathProp = System.getProperty(UNCHECKED_MATH_PROP);
    Object uncheckedMath = Boolean.FALSE;
    if("true".equals(uncheckedMathProp))
        uncheckedMath = Boolean.TRUE;
    else if("warn-on-boxed".equals(uncheckedMathProp))
        uncheckedMath = Keyword.intern("warn-on-boxed");

    // force load to avoid transitive compilation during lazy load
    RT.load("clojure/core/specs/alpha");

	try
		{
               Var.pushThreadBindings(RT.map(compile_path, path,
                       warn_on_reflection, warnOnReflection,
                       unchecked_math, uncheckedMath));

		for(String lib : args)
        {
            out.write("Loading " + lib + " (Truffle)\n");
            out.flush();
            compile.invoke(Symbol.intern(lib));
        }
		}
	finally
		{
        Var.popThreadBindings();
		try
			{
			out.flush();
			}
		catch(IOException e)
			{
			e.printStackTrace(err);
			}
		}
}
}
