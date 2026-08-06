package com.shravan.jcode_intelligence.dto.response;

import java.util.Map;

/**
 * Data Transfer Object for presenting repository indexing statistics.
 */
public class RepositoryStatsDTO {
    
    private int packages;
    private int classes;
    private int interfaces;
    private int enums;
    private int records;
    private int fields;
    private int constructors;
    private int methods;
    private int fragments;
    private int totalChunks;
    
    private String largestClass;
    private String largestMethod;
    private long indexingTimeMs;

    public RepositoryStatsDTO() {
    }

    public RepositoryStatsDTO(int packages, int classes, int interfaces, int enums, int records,
                              int fields, int constructors, int methods, int fragments, int totalChunks,
                              String largestClass, String largestMethod, long indexingTimeMs) {
        this.packages = packages;
        this.classes = classes;
        this.interfaces = interfaces;
        this.enums = enums;
        this.records = records;
        this.fields = fields;
        this.constructors = constructors;
        this.methods = methods;
        this.fragments = fragments;
        this.totalChunks = totalChunks;
        this.largestClass = largestClass;
        this.largestMethod = largestMethod;
        this.indexingTimeMs = indexingTimeMs;
    }

    public int getPackages() { return packages; }
    public void setPackages(int packages) { this.packages = packages; }

    public int getClasses() { return classes; }
    public void setClasses(int classes) { this.classes = classes; }

    public int getInterfaces() { return interfaces; }
    public void setInterfaces(int interfaces) { this.interfaces = interfaces; }

    public int getEnums() { return enums; }
    public void setEnums(int enums) { this.enums = enums; }

    public int getRecords() { return records; }
    public void setRecords(int records) { this.records = records; }

    public int getFields() { return fields; }
    public void setFields(int fields) { this.fields = fields; }

    public int getConstructors() { return constructors; }
    public void setConstructors(int constructors) { this.constructors = constructors; }

    public int getMethods() { return methods; }
    public void setMethods(int methods) { this.methods = methods; }

    public int getFragments() { return fragments; }
    public void setFragments(int fragments) { this.fragments = fragments; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public String getLargestClass() { return largestClass; }
    public void setLargestClass(String largestClass) { this.largestClass = largestClass; }

    public String getLargestMethod() { return largestMethod; }
    public void setLargestMethod(String largestMethod) { this.largestMethod = largestMethod; }

    public long getIndexingTimeMs() { return indexingTimeMs; }
    public void setIndexingTimeMs(long indexingTimeMs) { this.indexingTimeMs = indexingTimeMs; }
}
