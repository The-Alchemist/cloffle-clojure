# org.cloffle.trufflejson

Typed JSON projection for Truffle languages. Input is `byte[]`, `ByteBuffer`, or
`TruffleString`. String leaves are `TruffleString`.

Cloffle compiles Keyword/Malli schemas to a UTF-8 trie (`TypedSchema`), scans with
`JsonScan`, then allocates `PersistentShapeMap` from slot locals in its bytecode op.
Do not copy `FixedSlots8` instances into Cloffle maps.

`json/select` and no-schema `project` stay in Cloffle.
