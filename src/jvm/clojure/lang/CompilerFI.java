/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
 */
package clojure.lang;

import static clojure.lang.Compiler.*;

import clojure.asm.Handle;
import clojure.asm.Label;
import clojure.asm.Opcodes;
import clojure.asm.Type;
import clojure.asm.commons.GeneratorAdapter;

import java.lang.invoke.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/** Functional-interface adapter support used by {@link Compiler.FISupport}. */
final class CompilerFI {
	private CompilerFI() {
	}

	private static final IPersistentSet AFN_FIS = RT.set(Callable.class, Runnable.class, Comparator.class);
	private static final IPersistentSet OBJECT_METHODS = RT.set("equals", "toString", "hashCode");

	private static final java.lang.reflect.Method[] NO_FI_METHOD = new java.lang.reflect.Method[0];

	/**
	 * Result is a pure function of {@code target} ({@link #AFN_FIS} / {@link #OBJECT_METHODS} are
	 * immutable), and {@link Class#isAnnotationPresent} is expensive: it parses annotations and
	 * generic signatures. Reflective call sites hit this on every boxed argument.
	 */
	private static final ClassValue<java.lang.reflect.Method[]> FI_METHOD = new ClassValue<>() {
		protected java.lang.reflect.Method[] computeValue(Class<?> target) {
			return computeFIMethod(target);
		}
	};

	// Return FI method if:
	// 1) Target is a functional interface and not already implemented by AFn
	// 2) Target method matches one of our fn invoker methods (0 <= arity <= 10)
	//
	// Boundary: reached from reflective call sites that Truffle partial-evaluates. The
	// isAnnotationPresent path recurses through sun.reflect.generics.parser.SignatureParser, which PE
	// cannot bound, so inlining it makes Graal bail out with "Too deep inlining".
	@com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
	static java.lang.reflect.Method maybeFIMethod(Class target) {
		if (target == null) {
			return null;
		}
		java.lang.reflect.Method[] found = FI_METHOD.get(target);
		return found.length == 0 ? null : found[0];
	}

	private static java.lang.reflect.Method[] computeFIMethod(Class target) {
		if (target.isAnnotationPresent(FunctionalInterface.class) && !AFN_FIS.contains(target)) {
			java.lang.reflect.Method[] methods = target.getMethods();
			for (java.lang.reflect.Method method : methods) {
				if (method.getParameterCount() >= 0 && method.getParameterCount() <= 10
						&& Modifier.isAbstract(method.getModifiers())
						&& !OBJECT_METHODS.contains(method.getName()))
					return new java.lang.reflect.Method[]{method};
			}
		}
		return NO_FI_METHOD;
	}

	// Invokers support only long, double, Object params; widen numerics
	private static Class toInvokerParamType(Class c) {
		if (c.equals(Byte.TYPE) || c.equals(Short.TYPE) || c.equals(Integer.TYPE) || c.equals(Long.TYPE)) {
			return Long.TYPE;
		} else if (c.equals(Float.TYPE) || c.equals(Double.TYPE)) {
			return Double.TYPE;
		}
		return Object.class;
	}

	/**
	 * If targetClass is FI and has an adaptable functional method
	 *   Find fn invoker method matching adaptable method of FI
	 *   Emit bytecode for (expr is emitted):
	 *     if(expr instanceof IFn && !(expr instanceof FI))
	 *       invokeDynamic(targetMethod, fnInvokerImplMethod)
	 * Else emit nothing
	 */
	static boolean maybeEmitFIAdapter(ObjExpr objx, GeneratorAdapter gen, Expr expr, Class targetClass) {
		// Optimization:
		// if(expr instanceof QualifiedMethodExpr)
		//   emitInvokeDynamic(targetMethod, QME method) // DON'T emit expr

		java.lang.reflect.Method targetMethod = maybeFIMethod(targetClass);
		if (targetMethod == null)
			return false;

		// compute fn invoker method
		int paramCount = targetMethod.getParameterCount();
		Class[] invokerParams = new Class[paramCount + 1];
		invokerParams[0] = IFn.class;  // close over Ifn as first arg
		StringBuilder invokeMethodBuilder = new StringBuilder("invoke");
		for (int i = 0; i < paramCount; i++) {
			// FnInvokers only has prims for first 2 args
			invokerParams[i + 1] = paramCount <= 2 ? toInvokerParamType(targetMethod.getParameterTypes()[i]) : Object.class;
			invokeMethodBuilder.append(FnInvokers.encodeInvokerType(invokerParams[i + 1]));
		}
		// FnInvokers has prim returns for <= 2 params, only Object for higher
		Class retType = targetMethod.getReturnType();
		char invokerReturnCode = FnInvokers.encodeInvokerType(paramCount <= 2 ? retType : Object.class);
		invokeMethodBuilder.append(invokerReturnCode);
		String invokerMethodName = invokeMethodBuilder.toString();

		// Emit adapter to fn invoker method
		Type samType = Type.getType(targetClass);
		Type ifnType = Type.getType(IFn.class);
		try {
			java.lang.reflect.Method fnInvokerMethod = FnInvokers.class.getMethod(invokerMethodName, invokerParams);

			// if not (expr instanceof IFn), go to end label
			expr.emit(C.EXPRESSION, objx, gen);
			gen.dup();
			gen.instanceOf(ifnType);
			Label endLabel = gen.newLabel();
			gen.ifZCmp(Opcodes.IFEQ, endLabel);

			// if (expr instanceof FI), go to end label
			gen.dup();
			gen.instanceOf(samType);
			gen.ifZCmp(Opcodes.IFNE, endLabel);

			// else adapt fn invoker method as impl for target method
			emitInvokeDynamicAdapter(gen, targetClass, targetMethod, FnInvokers.class, fnInvokerMethod);

			// end - checkcast that we have the target FI type
			gen.mark(endLabel);
			gen.checkCast(samType);
			return true;
		} catch (NoSuchMethodException e) {
			throw Util.sneakyThrow(e); // should never happen
		}

	}

	// LambdaMetafactory.metafactory() method handle for lambda bootstrap
	private static final Handle LMF_HANDLE =
			new Handle(Opcodes.H_INVOKESTATIC,
					"java/lang/invoke/LambdaMetafactory",
					"metafactory",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
					false);

	/**
	 * Emit an invokedynamic to adapt an implMethod to act as a targetMethod.
	 *
	 * implMethod may be a static method, a constructor, or an instance method. If it is an
	 * instance method, the first argument is the invocation instance.
	 *
	 * The implMethod may close over objects on the stack - these are passed as the initial arguments
	 * to implMethod. The trailing arguments must match the targetMethod arguments.
	 *
	 * See: https://docs.oracle.com/javase/8/docs/api/java/lang/invoke/LambdaMetafactory.html
	 * @param gen          ASM code generator, expects any closed-overs to be on the stack already
	 * @param targetClass  The target class
	 * @param targetMethod The target method
	 * @param implClass    The impl class
	 * @param implMethod   The impl method that will be adapted, takes closed-overs + args of targetMethod
	 */
	static void emitInvokeDynamicAdapter(GeneratorAdapter gen,
												 Class targetClass, java.lang.reflect.Method targetMethod,
												 Class implClass, Executable implMethod) {

		// Impl method - takes closed overs (on stack now) + args (when called)
		Class[] implParams = implMethod.getParameterTypes();
		Class retClass = isConstructor(implMethod) ? implClass
				: ((java.lang.reflect.Method) implMethod).getReturnType();

		int opCode = isConstructor(implMethod) ? Opcodes.H_INVOKESPECIAL :
				(isStaticMethod(implMethod) ? Opcodes.H_INVOKESTATIC :
						Opcodes.H_INVOKEVIRTUAL);

		Handle implHandle = new Handle(opCode,
				Type.getInternalName(implClass),
				implMethod.getName(),
				MethodType.methodType(retClass, implParams).toMethodDescriptorString(),
				false);

		// Adapter interface lambda-style: (closedOver*) -> targetType
		int implArgCount = implParams.length;
		if (isInstanceMethod(implMethod))  // instance is first "arg"
			implArgCount++;
		List lambdaParams = Arrays.asList(Arrays.copyOfRange(implParams, 0, implArgCount - targetMethod.getParameterCount()));
		MethodType lambdaSig = MethodType.methodType(targetClass, lambdaParams);

		Type targetType = Type.getType(targetMethod);
		gen.visitInvokeDynamicInsn(
				targetMethod.getName(),
				lambdaSig.toMethodDescriptorString(),  // adapter signature, closedOvers -> target
				LMF_HANDLE,  // bootstrap method handle: LambdaMetaFactory.metafactory()
				new Object[]{targetType, implHandle, targetType}); // arg types of bootstrap method
	}
}
