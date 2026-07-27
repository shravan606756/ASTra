package com.shravan.jcode_intelligence.parser.spi;

/**
 * Strategy interface for evaluating whether source code content fits
 * within an embedding model's capacity limit.
 */
public interface ChunkBudgetEstimator {

    /**
     * Returns true if the provided text exceeds the maximum allowable method budget.
     */
    boolean exceedsMethodBudget(String content);

    /**
     * Returns true if the provided text exceeds the maximum fragment budget.
     */
    boolean exceedsFragmentBudget(String content);

    /**
     * Returns true if the provided text exceeds the absolute maximum document embedding limit.
     */
    boolean exceedsDocumentBudget(String content);

    int getMaxMethodBudget();

    int getMaxFragmentBudget();

    int getMaxSummaryMethods();

    int getMaxSummaryFields();

    int getMaxFieldBudget();

    int getMaxDocumentBudget();
}
