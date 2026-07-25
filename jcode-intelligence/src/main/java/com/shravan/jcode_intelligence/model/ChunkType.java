package com.shravan.jcode_intelligence.model;

/**
 * Defines the types of semantic units that ASTra extracts from Java source code.
 *
 * <p>Each chunk type represents a single meaningful program element
 * within the hierarchical AST decomposition:
 *
 * <pre>
 *   Repository → Package → Class/Interface/Enum/Record
 *                            ├── Field
 *                            ├── Constructor
 *                            ├── Method
 *                            │    └── MethodFragment (only if oversized)
 *                            └── Nested Class/Interface (decomposed as CLASS/INTERFACE with nesting metadata)
 * </pre>
 */
public enum ChunkType {

    /** Package-level summary. */
    PACKAGE,

    /** Top-level or nested class summary (signatures only, no method bodies). */
    CLASS,

    /** Top-level or nested interface summary. */
    INTERFACE,

    /** Enum declaration summary. */
    ENUM,

    /** Record declaration summary. */
    RECORD,

    /** Individual method with full body. */
    METHOD,

    /** Individual constructor with full body. */
    CONSTRUCTOR,

    /** Individual field declaration. */
    FIELD,

    /**
     * Fragment of an oversized method, split at AST statement boundaries.
     * Preserves execution order via {@code fragmentIndex}.
     */
    METHOD_FRAGMENT;

    /**
     * Returns true if this chunk type represents a type declaration
     * (class, interface, enum, or record).
     */
    public boolean isTypeDeclaration() {
        return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD;
    }
}
