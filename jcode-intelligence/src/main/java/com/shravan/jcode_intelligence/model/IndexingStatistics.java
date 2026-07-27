package com.shravan.jcode_intelligence.model;

/**
 * Value object summarizing diagnostic metrics for an indexing run.
 */
public class IndexingStatistics {

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

    private String largestClassName = "N/A";
    private int largestClassSize;

    private String largestMethodName = "N/A";
    private int largestMethodSize;

    private long indexingDurationMs;

    public IndexingStatistics() {
    }

    public int getPackages() {
        return packages;
    }

    public void setPackages(int packages) {
        this.packages = packages;
    }

    public int getClasses() {
        return classes;
    }

    public void setClasses(int classes) {
        this.classes = classes;
    }

    public int getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(int interfaces) {
        this.interfaces = interfaces;
    }

    public int getEnums() {
        return enums;
    }

    public void setEnums(int enums) {
        this.enums = enums;
    }

    public int getRecords() {
        return records;
    }

    public void setRecords(int records) {
        this.records = records;
    }

    public int getFields() {
        return fields;
    }

    public void setFields(int fields) {
        this.fields = fields;
    }

    public int getConstructors() {
        return constructors;
    }

    public void setConstructors(int constructors) {
        this.constructors = constructors;
    }

    public int getMethods() {
        return methods;
    }

    public void setMethods(int methods) {
        this.methods = methods;
    }

    public int getFragments() {
        return fragments;
    }

    public void setFragments(int fragments) {
        this.fragments = fragments;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getLargestClassName() {
        return largestClassName;
    }

    public void setLargestClassName(String largestClassName) {
        this.largestClassName = largestClassName;
    }

    public int getLargestClassSize() {
        return largestClassSize;
    }

    public void setLargestClassSize(int largestClassSize) {
        this.largestClassSize = largestClassSize;
    }

    public String getLargestMethodName() {
        return largestMethodName;
    }

    public void setLargestMethodName(String largestMethodName) {
        this.largestMethodName = largestMethodName;
    }

    public int getLargestMethodSize() {
        return largestMethodSize;
    }

    public void setLargestMethodSize(int largestMethodSize) {
        this.largestMethodSize = largestMethodSize;
    }

    public long getIndexingDurationMs() {
        return indexingDurationMs;
    }

    public void setIndexingDurationMs(long indexingDurationMs) {
        this.indexingDurationMs = indexingDurationMs;
    }

    @Override
    public String toString() {
        return String.format(
            """
            === INDEXING STATISTICS ===
            Packages:           %d
            Classes:            %d
            Interfaces:         %d
            Enums:              %d
            Records:            %d
            Fields:             %d
            Constructors:       %d
            Methods:            %d
            Method Fragments:   %d
            Total Chunks:       %d
            Largest Class:      %s (%d chars original)
            Largest Method:     %s (%d chars original)
            Indexing Time:      %d ms
            ===========================
            """,
            packages, classes, interfaces, enums, records, fields,
            constructors, methods, fragments, totalChunks,
            largestClassName, largestClassSize,
            largestMethodName, largestMethodSize,
            indexingDurationMs
        );
    }
}
