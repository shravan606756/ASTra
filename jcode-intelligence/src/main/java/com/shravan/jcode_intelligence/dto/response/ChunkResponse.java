package com.shravan.jcode_intelligence.dto.response;

public class ChunkResponse {

    private String type;
    private String repositoryId;
    private String packageName;
    private String className;
    private String elementName;
    private String signature;
    private String filePath;
    private int startLine;
    private int endLine;
    private String content;

    public ChunkResponse() {
    }

    public ChunkResponse(String type, String repositoryId, String packageName, String className,
                         String elementName, String signature, String filePath,
                         int startLine, int endLine, String content) {
        this.type = type;
        this.repositoryId = repositoryId;
        this.packageName = packageName;
        this.className = className;
        this.elementName = elementName;
        this.signature = signature;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content;
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

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}

