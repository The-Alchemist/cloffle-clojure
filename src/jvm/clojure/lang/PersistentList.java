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

import java.io.Serializable;
import java.util.*;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

@ValueType
public class PersistentList extends ASeq implements IPersistentList, IReduce, List, Counted {

private static final long serialVersionUID = -8833289659955219995L;

final Object _first;
final IPersistentList _rest;
final int _count;

static public class Primordial extends RestFn{
	final public int getRequiredArity(){
		return 0;
	}

	final protected Object doInvoke(Object args) {
		if(args instanceof ArraySeq)
			{
			Object[] argsarray = ((ArraySeq) args).array;
			IPersistentList ret = EMPTY;
			for(int i = argsarray.length - 1; i >= ((ArraySeq)args).i; --i)
				ret = (IPersistentList) ret.cons(argsarray[i]);
			return ret;
			}
		LinkedList list = new LinkedList();
		for(ISeq s = RT.seq(args); s != null; s = s.next())
			list.add(s.first());
		return create(list);
	}

	static public Object invokeStatic(ISeq args) {
		if(args instanceof ArraySeq)
			{
			Object[] argsarray = ((ArraySeq) args).array;
			IPersistentList ret = EMPTY;
			for(int i = argsarray.length - 1; i >= 0; --i)
				ret = (IPersistentList) ret.cons(argsarray[i]);
			return ret;
			}
		LinkedList list = new LinkedList();
		for(ISeq s = RT.seq(args); s != null; s = s.next())
			list.add(s.first());
		return create(list);
	}

	public IObj withMeta(IPersistentMap meta){
		throw new UnsupportedOperationException();
	}

	public IPersistentMap meta(){
		return null;
	}
}

public static IFn creator = new Primordial();

final public static EmptyList EMPTY = new EmptyList(null);

public PersistentList(Object first){
	this(null, first, null, 1);
}

PersistentList(IPersistentMap meta, Object _first, IPersistentList _rest, int _count){
	super(meta);
	this._first = _first;
	this._rest = _rest;
	this._count = _count;
}

public static IPersistentList createList() {
	return EMPTY;
}

public static PersistentList1 createList(Object e0) {
	return new PersistentList1(null, e0);
}

public static PersistentList2 createList(Object e0, Object e1) {
	return new PersistentList2(null, e0, e1);
}

public static PersistentList3 createList(Object e0, Object e1, Object e2) {
	return new PersistentList3(null, e0, e1, e2);
}

public static PersistentList4 createList(Object e0, Object e1, Object e2, Object e3) {
	return new PersistentList4(null, e0, e1, e2, e3);
}

public static PersistentList5 createList(Object e0, Object e1, Object e2, Object e3, Object e4) {
	return new PersistentList5(null, e0, e1, e2, e3, e4);
}

public static PersistentList6 createList(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
	return new PersistentList6(null, e0, e1, e2, e3, e4, e5);
}

public static PersistentList7 createList(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6) {
	return new PersistentList7(null, e0, e1, e2, e3, e4, e5, e6);
}

public static PersistentList8 createList(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6, Object e7) {
	return new PersistentList8(null, e0, e1, e2, e3, e4, e5, e6, e7);
}

public static IPersistentList createListFromArray(Object[] items) {
	switch (items.length) {
		case 0: return EMPTY;
		case 1: return createList(items[0]);
		case 2: return createList(items[0], items[1]);
		case 3: return createList(items[0], items[1], items[2]);
		case 4: return createList(items[0], items[1], items[2], items[3]);
		case 5: return createList(items[0], items[1], items[2], items[3], items[4]);
		case 6: return createList(items[0], items[1], items[2], items[3], items[4], items[5]);
		case 7: return createList(items[0], items[1], items[2], items[3], items[4], items[5], items[6]);
		case 8: return createList(items[0], items[1], items[2], items[3], items[4], items[5], items[6], items[7]);
		default: {
			IPersistentList ret = EMPTY;
			for (int i = items.length - 1; i >= 0; --i) {
				ret = (IPersistentList) ret.cons(items[i]);
			}
			return ret;
		}
	}
}

public static IPersistentList create(List init){
	int size = init.size();
	switch (size) {
		case 0: return EMPTY;
		case 1: return createList(init.get(0));
		case 2: return createList(init.get(0), init.get(1));
		case 3: return createList(init.get(0), init.get(1), init.get(2));
		case 4: return createList(init.get(0), init.get(1), init.get(2), init.get(3));
		case 5: return createList(init.get(0), init.get(1), init.get(2), init.get(3), init.get(4));
		case 6: return createList(init.get(0), init.get(1), init.get(2), init.get(3), init.get(4), init.get(5));
		case 7: return createList(init.get(0), init.get(1), init.get(2), init.get(3), init.get(4), init.get(5), init.get(6));
		case 8: return createList(init.get(0), init.get(1), init.get(2), init.get(3), init.get(4), init.get(5), init.get(6), init.get(7));
		default: {
			IPersistentList ret = EMPTY;
			for(ListIterator i = init.listIterator(size); i.hasPrevious();)
				{
				ret = (IPersistentList) ret.cons(i.previous());
				}
			return ret;
		}
	}
}

public Object first(){
	return _first;
}

public ISeq next(){
	if(_count == 1)
		return null;
	return (ISeq) _rest;
}

public Object peek(){
	return first();
}

public IPersistentList pop(){
	if(_rest == null)
		return EMPTY.withMeta(_meta);
	return _rest;
}

public int count(){
	return _count;
}

public PersistentList cons(Object o){
	return new PersistentList(_meta, o, this, _count + 1);
}

public IPersistentCollection empty(){
	return EMPTY.withMeta(meta());
}

public PersistentList withMeta(IPersistentMap meta){
	if(meta != _meta)
		return new PersistentList(meta, _first, _rest, _count);
	return this;
}

public Object reduce(IFn f) {
	Object ret = first();
	for(ISeq s = next(); s != null; s = s.next()) {
        ret = f.invoke(ret, s.first());
        if (RT.isReduced(ret)) return ((IDeref)ret).deref();;
    }
	return ret;
}

public Object reduce(IFn f, Object start) {
	Object ret = f.invoke(start, first());
	for(ISeq s = next(); s != null; s = s.next()) {
        if (RT.isReduced(ret)) return ((IDeref)ret).deref();
		ret = f.invoke(ret, s.first());
    }
	if (RT.isReduced(ret)) return ((IDeref)ret).deref();
	return ret;
}


@ValueType
    public static class EmptyList extends Obj implements IPersistentList, List, ISeq, Counted, IHashEq{
	static final int hasheq = Murmur3.hashOrdered(Collections.EMPTY_LIST);

	public int hashCode(){
		return 1;
	}

	public int hasheq(){
		return hasheq;
	}

    public String toString() {
        return "()";
    }

    public boolean equals(Object o) {
        return (o instanceof Sequential || o instanceof List) && RT.seq(o) == null;
    }

	public boolean equiv(Object o){
		return equals(o);
	}
	
    EmptyList(IPersistentMap meta){
		super(meta);
	}

        public Object first() {
            return null;
        }

        public ISeq next() {
            return null;
        }

        public ISeq more() {
            return this;
        }

        public PersistentList cons(Object o){
		return new PersistentList1(meta(), o);
	}

	public IPersistentCollection empty(){
		return this;
	}

	public EmptyList withMeta(IPersistentMap meta){
		if(meta != meta())
			return new EmptyList(meta);
		return this;
	}

	public Object peek(){
		return null;
	}

	public IPersistentList pop(){
		throw new IllegalStateException("Can't pop empty list");
	}

	public int count(){
		return 0;
	}

	public ISeq seq(){
		return null;
	}


	public int size(){
		return 0;
	}

	public boolean isEmpty(){
		return true;
	}

	public boolean contains(Object o){
		return false;
	}

	public Iterator iterator(){
		return new Iterator(){

			public boolean hasNext(){
				return false;
			}

			public Object next(){
				throw new NoSuchElementException();
			}

			public void remove(){
				throw new UnsupportedOperationException();
			}
		};
	}

	public Object[] toArray(){
		return RT.EMPTY_ARRAY;
	}

	public boolean add(Object o){
		throw new UnsupportedOperationException();
	}

	public boolean remove(Object o){
		throw new UnsupportedOperationException();
	}

	public boolean addAll(Collection collection){
		throw new UnsupportedOperationException();
	}

	public void clear(){
		throw new UnsupportedOperationException();
	}

	public boolean retainAll(Collection collection){
		throw new UnsupportedOperationException();
	}

	public boolean removeAll(Collection collection){
		throw new UnsupportedOperationException();
	}

	public boolean containsAll(Collection collection){
		return collection.isEmpty();
	}

	public Object[] toArray(Object[] objects){
		if(objects.length > 0)
			objects[0] = null;
		return objects;
	}

	//////////// List stuff /////////////////
	private List reify(){
		return Collections.unmodifiableList(new ArrayList(this));
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


}

@ValueType
public static final class PersistentList1 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public PersistentList1(IPersistentMap meta, Object e0) {
		super(meta, e0, null, 1);
	}

	@Override
	public int count() {
		return 1;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return null;
	}

	@Override
	public ISeq more() {
		return EMPTY.withMeta(_meta);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return EMPTY.withMeta(_meta);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList2(_meta, o, _first);
	}

	@Override
	public PersistentList1 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList1(meta, _first);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first};
	}

	@Override
	public Object reduce(IFn f) {
		return _first;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList2 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1;

	public PersistentList2(IPersistentMap meta, Object e0, Object e1) {
		super(meta, e0, null, 2);
		this.e1 = e1;
	}

	@Override
	public int count() {
		return 2;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList1(_meta, e1);
	}

	@Override
	public ISeq more() {
		return new PersistentList1(_meta, e1);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList1(_meta, e1);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList3(_meta, o, _first, e1);
	}

	@Override
	public PersistentList2 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList2(meta, _first, e1);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList3 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2;

	public PersistentList3(IPersistentMap meta, Object e0, Object e1, Object e2) {
		super(meta, e0, null, 3);
		this.e1 = e1;
		this.e2 = e2;
	}

	@Override
	public int count() {
		return 3;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList2(_meta, e1, e2);
	}

	@Override
	public ISeq more() {
		return new PersistentList2(_meta, e1, e2);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList2(_meta, e1, e2);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList4(_meta, o, _first, e1, e2);
	}

	@Override
	public PersistentList3 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList3(meta, _first, e1, e2);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList4 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2, e3;

	public PersistentList4(IPersistentMap meta, Object e0, Object e1, Object e2, Object e3) {
		super(meta, e0, null, 4);
		this.e1 = e1;
		this.e2 = e2;
		this.e3 = e3;
	}

	@Override
	public int count() {
		return 4;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList3(_meta, e1, e2, e3);
	}

	@Override
	public ISeq more() {
		return new PersistentList3(_meta, e1, e2, e3);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList3(_meta, e1, e2, e3);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList5(_meta, o, _first, e1, e2, e3);
	}

	@Override
	public PersistentList4 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList4(meta, _first, e1, e2, e3);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		if (index == 3) return e3;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2, e3};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList5 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2, e3, e4;

	public PersistentList5(IPersistentMap meta, Object e0, Object e1, Object e2, Object e3, Object e4) {
		super(meta, e0, null, 5);
		this.e1 = e1;
		this.e2 = e2;
		this.e3 = e3;
		this.e4 = e4;
	}

	@Override
	public int count() {
		return 5;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList4(_meta, e1, e2, e3, e4);
	}

	@Override
	public ISeq more() {
		return new PersistentList4(_meta, e1, e2, e3, e4);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList4(_meta, e1, e2, e3, e4);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList6(_meta, o, _first, e1, e2, e3, e4);
	}

	@Override
	public PersistentList5 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList5(meta, _first, e1, e2, e3, e4);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		if (index == 3) return e3;
		if (index == 4) return e4;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2, e3, e4};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList6 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2, e3, e4, e5;

	public PersistentList6(IPersistentMap meta, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
		super(meta, e0, null, 6);
		this.e1 = e1;
		this.e2 = e2;
		this.e3 = e3;
		this.e4 = e4;
		this.e5 = e5;
	}

	@Override
	public int count() {
		return 6;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList5(_meta, e1, e2, e3, e4, e5);
	}

	@Override
	public ISeq more() {
		return new PersistentList5(_meta, e1, e2, e3, e4, e5);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList5(_meta, e1, e2, e3, e4, e5);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList7(_meta, o, _first, e1, e2, e3, e4, e5);
	}

	@Override
	public PersistentList6 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList6(meta, _first, e1, e2, e3, e4, e5);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		if (index == 3) return e3;
		if (index == 4) return e4;
		if (index == 5) return e5;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2, e3, e4, e5};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList7 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2, e3, e4, e5, e6;

	public PersistentList7(IPersistentMap meta, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6) {
		super(meta, e0, null, 7);
		this.e1 = e1;
		this.e2 = e2;
		this.e3 = e3;
		this.e4 = e4;
		this.e5 = e5;
		this.e6 = e6;
	}

	@Override
	public int count() {
		return 7;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		if (i == 6) return e6;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		if (i == 6) return e6;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList6(_meta, e1, e2, e3, e4, e5, e6);
	}

	@Override
	public ISeq more() {
		return new PersistentList6(_meta, e1, e2, e3, e4, e5, e6);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList6(_meta, e1, e2, e3, e4, e5, e6);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList8(_meta, o, _first, e1, e2, e3, e4, e5, e6);
	}

	@Override
	public PersistentList7 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList7(meta, _first, e1, e2, e3, e4, e5, e6);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		if (index == 3) return e3;
		if (index == 4) return e4;
		if (index == 5) return e5;
		if (index == 6) return e6;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2, e3, e4, e5, e6};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e6);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e6);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

@ValueType
public static final class PersistentList8 extends PersistentList implements Indexed {
	private static final long serialVersionUID = 1L;

	public final Object e1, e2, e3, e4, e5, e6, e7;

	public PersistentList8(IPersistentMap meta, Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6, Object e7) {
		super(meta, e0, null, 8);
		this.e1 = e1;
		this.e2 = e2;
		this.e3 = e3;
		this.e4 = e4;
		this.e5 = e5;
		this.e6 = e6;
		this.e7 = e7;
	}

	@Override
	public int count() {
		return 8;
	}

	@Override
	public Object nth(int i) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		if (i == 6) return e6;
		if (i == 7) return e7;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object nth(int i, Object notFound) {
		if (i == 0) return _first;
		if (i == 1) return e1;
		if (i == 2) return e2;
		if (i == 3) return e3;
		if (i == 4) return e4;
		if (i == 5) return e5;
		if (i == 6) return e6;
		if (i == 7) return e7;
		return notFound;
	}

	@Override
	public Object first() {
		return _first;
	}

	@Override
	public ISeq next() {
		return new PersistentList7(_meta, e1, e2, e3, e4, e5, e6, e7);
	}

	@Override
	public ISeq more() {
		return new PersistentList7(_meta, e1, e2, e3, e4, e5, e6, e7);
	}

	@Override
	public Object peek() {
		return _first;
	}

	@Override
	public IPersistentList pop() {
		return new PersistentList7(_meta, e1, e2, e3, e4, e5, e6, e7);
	}

	@Override
	public PersistentList cons(Object o) {
		return new PersistentList(_meta, o, this, 9);
	}

	@Override
	public PersistentList8 withMeta(IPersistentMap meta) {
		if (meta == _meta) return this;
		return new PersistentList8(meta, _first, e1, e2, e3, e4, e5, e6, e7);
	}

	@Override
	public Object get(int index) {
		if (index == 0) return _first;
		if (index == 1) return e1;
		if (index == 2) return e2;
		if (index == 3) return e3;
		if (index == 4) return e4;
		if (index == 5) return e5;
		if (index == 6) return e6;
		if (index == 7) return e7;
		throw new IndexOutOfBoundsException();
	}

	@Override
	public Object[] toArray() {
		return new Object[]{_first, e1, e2, e3, e4, e5, e6, e7};
	}

	@Override
	public Object reduce(IFn f) {
		Object ret = f.invoke(_first, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e6);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e7);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}

	@Override
	public Object reduce(IFn f, Object start) {
		Object ret = f.invoke(start, _first);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e1);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e2);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e3);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e4);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e5);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e6);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		ret = f.invoke(ret, e7);
		if (RT.isReduced(ret)) return ((IDeref) ret).deref();
		return ret;
	}
}

}
