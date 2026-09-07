/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jan 31, 2009 */

package clojure.lang;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.*;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

@ExportLibrary(InteropLibrary.class)
public final class LazySeq extends Obj implements ISeq, Sequential, List, IPending, IHashEq, TruffleObject, IReduce{

private static final long serialVersionUID = -7531333024710395876L;

private static final int UNREALIZED = 0;
private static final int FORCED = 1;
private static final int REALIZED = 2;

private transient IFn fn;
private Object sv;
private ISeq s;
private volatile int state;

public LazySeq(IFn f){
	fn = f;
	state = UNREALIZED;
}

private LazySeq(IPersistentMap meta, ISeq seq){
	super(meta);
	fn = null;
	s = seq;
	state = REALIZED;
}

public Obj withMeta(IPersistentMap meta){
	if(meta() == meta)
		return this;
	return new LazySeq(meta, seq());
}

final private void force() {
	if (fn != null) {
		if (fn instanceof net.javacrumbs.cloffle.nodes.ClojureClosure cc) {
			sv = cc.invoke();
		} else {
			sv = fn.invoke();
		}
		fn = null;
		state = FORCED;
	}
}

private Object sval() {
	synchronized (this) {
		if (state == REALIZED) return s;
		force();
		return sv;
	}
}

@TruffleBoundary
private ISeq realize() {
	synchronized (this) {
		if (state == REALIZED) return s;
		force();
		Object ls = sv;
		while (ls instanceof LazySeq lz) {
			if (lz == this)
				throw new IllegalStateException("Recursive lazy-seq realization");
			ls = lz.sval();
		}
		s = RT.seq(ls);
		sv = null;
		state = REALIZED;
		return s;
	}
}

public final ISeq seq(){
	if (state == REALIZED)
		return s;
	return realize();
}

public int count(){
	int c = 0;
	for(ISeq s = seq(); s != null; s = s.next())
		++c;                                                                                
	return c;
}

public Object first(){
	ISeq sq = seq();
	return sq == null ? null : sq.first();
}

public ISeq next(){
	ISeq sq = seq();
	return sq == null ? null : sq.next();
}

public ISeq more(){
	ISeq sq = seq();
	return sq == null ? PersistentList.EMPTY : sq.more();
}

public ISeq cons(Object o){
	return RT.cons(o, seq());
}

public IPersistentCollection empty(){
	return PersistentList.EMPTY;
}

public boolean equiv(Object o){
	ISeq s = seq();
	if(s != null)
		return s.equiv(o);
	else
		return (o instanceof Sequential || o instanceof List) && RT.seq(o) == null;
}

public int hashCode(){
	ISeq s = seq();
	if(s == null)
		return 1;
	return Util.hash(s);
}

public int hasheq(){
	return Murmur3.hashOrdered(this);
}

public boolean equals(Object o){
	ISeq s = seq();
	if(s != null)
		return s.equals(o);
	else
		return (o instanceof Sequential || o instanceof List) && RT.seq(o) == null;
}


// java.util.Collection implementation

public Object[] toArray(){
	return RT.seqToArray(seq());
}

public boolean add(Object o){
	throw new UnsupportedOperationException();
}

public boolean remove(Object o){
	throw new UnsupportedOperationException();
}

public boolean addAll(Collection c){
	throw new UnsupportedOperationException();
}

public void clear(){
	throw new UnsupportedOperationException();
}

public boolean retainAll(Collection c){
	throw new UnsupportedOperationException();
}

public boolean removeAll(Collection c){
	throw new UnsupportedOperationException();
}

public boolean containsAll(Collection c){
	for(Object o : c)
		{
		if(!contains(o))
			return false;
		}
	return true;
}

public Object[] toArray(Object[] a){
    return RT.seqToPassedArray(seq(), a);
}

public int size(){
	return count();
}

public boolean isEmpty(){
	return seq() == null;
}

public boolean contains(Object o){
	for(ISeq s = seq(); s != null; s = s.next())
		{
		if(Util.equiv(s.first(), o))
			return true;
		}
	return false;
}

public Iterator iterator(){
	return new SeqIterator(this);
}

//////////// List stuff /////////////////
private List reify(){
	return new ArrayList(this);
}

public List subList(int fromIndex, int toIndex){
	return reify().subList(fromIndex, toIndex);
}

public Object set(int index, Object element){
	throw new UnsupportedOperationException();
}

public Object remove(int index){
	throw new UnsupportedOperationException();
}

public int indexOf(Object o){
	ISeq s = seq();
	for(int i = 0; s != null; s = s.next(), i++)
		{
		if(Util.equiv(s.first(), o))
			return i;
		}
	return -1;
}

public int lastIndexOf(Object o){
	return reify().lastIndexOf(o);
}

public ListIterator listIterator(){
	return reify().listIterator();
}

public ListIterator listIterator(int index){
	return reify().listIterator(index);
}

public Object get(int index){
	return RT.nth(this, index);
}

public void add(int index, Object element){
	throw new UnsupportedOperationException();
}

public boolean addAll(int index, Collection c){
	throw new UnsupportedOperationException();
}

public boolean isRealized(){
    return state != UNREALIZED;
}

@Override
public Object reduce(IFn rf) {
	ISeq s = seq();
	if (s == null) {
		return rf.invoke();
	}
	Object acc = s.first();
	s = s.next();
	while (s != null) {
		acc = rf.invoke(acc, s.first());
		if (RT.isReduced(acc)) {
			return ((IDeref) acc).deref();
		}
		s = s.next();
	}
	return acc;
}

@Override
public Object reduce(IFn rf, Object start) {
	Object acc = start;
	ISeq s = seq();
	while (s != null) {
		acc = rf.invoke(acc, s.first());
		if (RT.isReduced(acc)) {
			return ((IDeref) acc).deref();
		}
		s = s.next();
	}
	return acc;
}

// custom Serializable implementation - ensure seq is fully-realized before writing
private void writeObject(java.io.ObjectOutputStream out) throws IOException {
	ISeq s = this;
	while(s != null) {
		s = s.next();
	}
	out.defaultWriteObject();
}

@ExportMessage
boolean hasArrayElements() { return true; }

@ExportMessage
long getArraySize() { return count(); }

@ExportMessage
boolean isArrayElementReadable(long index) { return index >= 0 && index < count(); }

@ExportMessage
Object readArrayElement(long index) throws InvalidArrayIndexException {
	if (index < 0) throw InvalidArrayIndexException.create(index);
	ISeq s = seq();
	for (long i = 0; i < index && s != null; i++) s = s.next();
	if (s == null) throw InvalidArrayIndexException.create(index);
	return ClojureInterop.wrapForPolyglot(s.first());
}

@ExportMessage
String toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
	if (!allowSideEffects) return "clojure.lang.LazySeq";
	return toString();
}
}
