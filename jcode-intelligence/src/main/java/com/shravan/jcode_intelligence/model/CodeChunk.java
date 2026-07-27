package com.shravan.jcode_intelligence.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a single semantic unit extracted from Java source code.
 *
 * <p>Each CodeChunk corresponds to one AST-level element (class summary,
 * method, constructor, field, or method fragment) and carries
 * metadata to reconstruct parent-child relationships for context
 * assembly during retrieval.
 */
public class CodeChunk {

    // ── Identity ──────────────────────────────────────────────

    private String id;
    private String type;
    private String repositoryId;

    // ── Hierarchy & Nesting ───────────────────────────────────

    /**
     * Links this chunk to its parent (e.g., a METHOD chunk points
     * to its enclosing CLASS chunk). Null for top-level type chunks.
     */
    private String parentChunkId;

    /** Zero for top-level types; 1+ for inner/nested classes. */
    private int nestingDepth;

    /** Enclosing outer class name for nested types. Null for top-level. */
    private String outerClassName;

    // ── Location ──────────────────────────────────────────────

    private String packageName;
    private String className;
    private String elementName;
    private String filePath;

    private int startLine;
    private int endLine;

    // ── Source & Signature ────────────────────────────────────

    private String signature;
    private String content;
    private String language;

    // ── Annotations & Modifiers ───────────────────────────────

    private List<String> imports;
    private List<String> annotations;
    private List<String> modifiers;

    // ── Type-declaration metadata ─────────────────────────────

    /** JavaDoc comment, if present on this element. */
    private String javadoc;

    /** Superclass name (for CLASS chunks). */
    private String superClass;

    /** Implemented interface names (for CLASS chunks). */
    private List<String> interfaces;

    // ── Extensible Relationships ──────────────────────────────

    /** Maps relationship type (e.g. extends, implements, belongsTo) to targets. */
    private Map<String, List<String>> relationships;

    // ── Method-fragment metadata ──────────────────────────────

    /**
     * Zero-based ordering index for METHOD_FRAGMENT chunks
     * that belong to the same oversized method. Null for non-fragment chunks.
     */
    private Integer fragmentIndex;

    /**
     * Name of the parent method when this chunk is a METHOD_FRAGMENT.
     * Null for non-fragment chunks.
     */
    private String methodName;

    // ── Diagnostics & Sizing ──────────────────────────────────

    private int contentLength;
    private int originalElementLength;
    private boolean summarized;
    private boolean fragmented;

    // ── Constructors ──────────────────────────────────────────

    public CodeChunk() {
    }

    // ── Getters & Setters ─────────────────────────────────────

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getParentChunkId() {
        return parentChunkId;
    }

    public void setParentChunkId(String parentChunkId) {
        this.parentChunkId = parentChunkId;
    }

    public int getNestingDepth() {
        return nestingDepth;
    }

    public void setNestingDepth(int nestingDepth) {
        this.nestingDepth = nestingDepth;
    }

    public String getOuterClassName() {
        return outerClassName;
    }

    public void setOuterClassName(String outerClassName) {
        this.outerClassName = outerClassName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getElementName() {
        return elementName;
    }

    public void setElementName(String elementName) {
        this.elementName = elementName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        if (content != null) {
            this.contentLength = content.length();
        }
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public List<String> getImports() {
        return imports;
    }

    public void setImports(List<String> imports) {
        this.imports = imports;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    public List<String> getModifiers() {
        return modifiers;
    }

    public void setModifiers(List<String> modifiers) {
        this.modifiers = modifiers;
    }

    public String getJavadoc() {
        return javadoc;
    }

    public void setJavadoc(String javadoc) {
        this.javadoc = javadoc;
    }

    public String getSuperClass() {
        return superClass;
    }

    public void setSuperClass(String superClass) {
        this.superClass = superClass;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(List<String> interfaces) {
        this.interfaces = interfaces;
    }

    public Map<String, List<String>> getRelationships() {
        return relationships;
    }

    public void setRelationships(Map<String, List<String>> relationships) {
        this.relationships = relationships;
    }

    public Integer getFragmentIndex() {
        return fragmentIndex;
    }

    public void setFragmentIndex(Integer fragmentIndex) {
        this.fragmentIndex = fragmentIndex;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public int getOriginalElementLength() {
        return originalElementLength;
    }

    public void setOriginalElementLength(int originalElementLength) {
        this.originalElementLength = originalElementLength;
    }

    public boolean isSummarized() {
        return summarized;
    }

    public void setSummarized(boolean summarized) {
        this.summarized = summarized;
    }

    public boolean isFragmented() {
        return fragmented;
    }

    public void setFragmented(boolean fragmented) {
        this.fragmented = fragmented;
    }
}