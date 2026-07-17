package com.shravan.jcode_intelligence.model;

public class CodeChunk {

    private String id; //Unique identifier for the chunk.
    private String filePath; //Allows locating the source file.
    private String packageName; //Improves semantic filtering.
    private String className; //Useful for class-level retrieval.
    private String methodName; //Enables method-specific searches.
    private String type; //Indicates whether the chunk is a CLASS, METHOD, INTERFACE, ENUM, etc.
    private String language; //Keeps the model extensible for future support of Kotlin, Python, etc.
    private String content; //The actual source code that will be embedded.

    public CodeChunk() {
    }

    public CodeChunk(String id,
                     String filePath,
                     String packageName,
                     String className,
                     String methodName,
                     String type,
                     String language,
                     String content) {

        this.id = id;
        this.filePath = filePath;
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.type = type;
        this.language = language;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
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

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}