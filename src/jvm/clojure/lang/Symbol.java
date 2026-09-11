/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Mar 25, 2006 11:42:47 AM */

package clojure.lang;

import java.io.Serializable;
import java.io.ObjectStreamException;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.strings.TruffleString;

public class Symbol extends AFn implements IObj, Comparable, Named, Serializable, IHashEq, TruffleObject{

private static final long serialVersionUID = 1191039485148212259L;

final String ns;
final String name;
private int _hasheq;
final IPersistentMap _meta;
final String _str;
@com.oracle.truffle.api.CompilerDirectives.CompilationFinal
transient TruffleString _truffleStr;

public TruffleString toTruffleString() {
	if (_truffleStr == null) {
		_truffleStr = TruffleString.fromJavaStringUncached(toString(), TruffleString.Encoding.UTF_16);
	}
	return _truffleStr;
}

public String toString(){
	return _str;
}

public String getNamespace(){
	return ns;
}

public String getName(){
	return name;
}

// the create thunks preserve binary compatibility with code compiled
// against earlier version of Clojure and can be removed (at some point).
static public Symbol create(String ns, String name) {
    return Symbol.intern(ns, name);
}

static public Symbol create(String nsname) {
    return Symbol.intern(nsname);
}
    
static public Symbol intern(String ns, String name){
	if(name == null)
		return null;
	return new Symbol(ns, name);
}

static public Symbol intern(String nsname){
	int i = nsname.indexOf('/');
	if(i == -1 || nsname.equals("/"))
		return new Symbol(null, nsname);
	else
		return new Symbol(nsname.substring(0, i), nsname.substring(i + 1));
}

private Symbol(String ns_interned, String name_interned){
	this.name = name_interned;
	this.ns = ns_interned;
	this._meta = null;
	this._str = (ns_interned != null) ? (ns_interned + "/" + name_interned).intern() : name_interned;
}

public boolean equals(Object o){
	if(this == o)
		return true;
	if(!(o instanceof Symbol))
		return false;

	Symbol symbol = (Symbol) o;

	return Util.equals(ns,symbol.ns) && name.equals(symbol.name);
}

public int hashCode(){
	return Util.hashCombine(name.hashCode(), Util.hash(ns));
}

public int hasheq() {
	if(_hasheq == 0){
		_hasheq = Util.hashCombine(Murmur3.hashUnencodedChars(name), Util.hash(ns));
	}
	return _hasheq;
}

public IObj withMeta(IPersistentMap meta){
	if(meta() == meta)
		return this;
	return new Symbol(meta, ns, name);
}

private Symbol(IPersistentMap meta, String ns, String name){
	this.name = name;
	this.ns = ns;
	this._meta = meta;
	this._str = (ns != null) ? (ns + "/" + name).intern() : name;
}

public int compareTo(Object o){
	Symbol s = (Symbol) o;
	if(this.equals(o))
		return 0;
	if(this.ns == null && s.ns != null)
		return -1;
	if(this.ns != null)
		{
		if(s.ns == null)
			return 1;
		int nsc = this.ns.compareTo(s.ns);
		if(nsc != 0)
			return nsc;
		}
	return this.name.compareTo(s.name);
}

private Object readResolve() throws ObjectStreamException{
	return intern(ns, name);
}

public Object invoke(Object obj) {
	return RT.get(obj, this);
}

public Object invoke(Object obj, Object notFound) {
	return RT.get(obj, this, notFound);
}

public IPersistentMap meta(){
	return _meta;
}

// Interned symbols shared by the compiler and reader (special forms, host interop, core refs).
public static final Symbol DEF = intern("def");
public static final Symbol LOOP = intern("loop*");
public static final Symbol RECUR = intern("recur");
public static final Symbol IF = intern("if");
public static final Symbol LET = intern("let*");
public static final Symbol LETFN = intern("letfn*");
public static final Symbol DO = intern("do");
public static final Symbol FN = intern("fn*");
public static final Symbol FNONCE = (Symbol) intern("fn*").withMeta(RT.map(Keyword.onceKey, RT.T));
public static final Symbol QUOTE = intern("quote");
public static final Symbol THE_VAR = intern("var");
public static final Symbol DOT = intern(".");
public static final Symbol ASSIGN = intern("set!");
public static final Symbol TRY = intern("try");
public static final Symbol CATCH = intern("catch");
public static final Symbol FINALLY = intern("finally");
public static final Symbol THROW = intern("throw");
public static final Symbol MONITOR_ENTER = intern("monitor-enter");
public static final Symbol MONITOR_EXIT = intern("monitor-exit");
public static final Symbol IMPORT = intern("clojure.core", "import*");
public static final Symbol DEFTYPE = intern("deftype*");
public static final Symbol CASE = intern("case*");
public static final Symbol CLASS = intern("Class");
public static final Symbol NEW = intern("new");
public static final Symbol THIS = intern("this");
public static final Symbol REIFY = intern("reify*");
public static final Symbol LIST = intern("clojure.core", "list");
public static final Symbol HASHMAP = intern("clojure.core", "hash-map");
public static final Symbol VECTOR = intern("clojure.core", "vector");
public static final Symbol IDENTITY = intern("clojure.core", "identity");
public static final Symbol _AMP_ = intern("&");
public static final Symbol ISEQ = intern("clojure.lang.ISeq");
public static final Symbol INVOKE_STATIC = intern("invokeStatic");
public static final Symbol NS = intern("ns");
public static final Symbol IN_NS = intern("in-ns");
}
