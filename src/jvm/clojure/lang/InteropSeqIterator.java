package clojure.lang;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Truffle iterator over an {@link ISeq}. Uncounted / infinite seqs export this instead of
 * array interop so {@code getArraySize} never forces the whole collection.
 */
@ExportLibrary(InteropLibrary.class)
public final class InteropSeqIterator implements TruffleObject {

	private ISeq seq;

	public InteropSeqIterator(ISeq seq) {
		this.seq = seq;
	}

	@ExportMessage
	boolean isIterator() {
		return true;
	}

	@ExportMessage
	boolean hasIteratorNextElement() {
		return seq != null;
	}

	@ExportMessage
	Object getIteratorNextElement() throws StopIterationException {
		if (seq == null) {
			throw StopIterationException.create();
		}
		Object first = seq.first();
		seq = seq.next();
		return ClojureInterop.wrapForPolyglot(first);
	}

	@ExportMessage
	String toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
		return "InteropSeqIterator";
	}
}
