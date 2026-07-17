package com.shravan.jcode_intelligence.model;

import java.util.List;

public class CodeChunk {

    private String id;
    private String type;

    private String packageName;
    private String className;
    private String elementName;

    private String filePath;

    private List<String> imports;
    private List<String> annotations;
    private List<String> modifiers;

    private String signature;
    private String content;

    private int startLine;
    private int endLine;

    private String language;

    public CodeChunk() {
    }

    public CodeChunk(String id,
                     String type,
                     String packageName,
                     String className,
                     String memberName,
                     String filePath,
                     List<String> imports,
                     List<String> annotations,
                     List<String> modifiers,
                     String signature,
                     String content,
                     int startLine,
                     int endLine,
                     String language) {
        this.id = id;
        this.type = type;
        this.packageName = packageName;
        this.className = className;
        this.elementName = elementName;
        this.filePath = filePath;
        this.imports = imports;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.signature = signature;
        this.content = content;
        this.startLine = startLine;
        this.endLine = endLine;
        this.language = language;
    }

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

    public String getMemberName() {
        return elementName;
    }

    public void setMemberName(String memberName) {
        this.elementName = memberName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}