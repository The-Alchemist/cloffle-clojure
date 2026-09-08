package net.javacrumbs.cloffle.bytecode.archive;

/**
 * Identity-based wrapper that prevents Truffle's equals-based constant pool
 * from merging structurally-equal but type-distinct collections
 * (e.g. PersistentList(1,2,3).equals(PersistentVector(1,2,3)) is true).
 */
public final class IdentityConstant {
    public final Object value;

    public IdentityConstant(Object value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
