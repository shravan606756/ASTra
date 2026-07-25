package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.config.ChunkingConfig;
import com.shravan.jcode_intelligence.parser.spi.ChunkBudgetEstimator;
import org.springframework.stereotype.Component;

/**
 * Character-length based implementation of {@link ChunkBudgetEstimator}.
 * Uses configurable limits from {@link ChunkingConfig}.
 */
@Component
public class CharBasedBudgetEstimator implements ChunkBudgetEstimator {

    private final ChunkingConfig config;

    public CharBasedBudgetEstimator(ChunkingConfig config) {
        this.config = config;
    }

    @Override
    public boolean exceedsMethodBudget(String content) {
        if (content == null) return false;
        return content.length() > config.getMaxMethodChars();
    }

    @Override
    public boolean exceedsFragmentBudget(String content) {
        if (content == null) return false;
        return content.length() > config.getMaxFragmentChars();
    }

    @Override
    public boolean exceedsDocumentBudget(String content) {
        if (content == null) return false;
        return content.length() > config.getMaxDocumentChars();
    }

    @Override
    public int getMaxMethodBudget() {
        return config.getMaxMethodChars();
    }

    @Override
    public int getMaxFragmentBudget() {
        return config.getMaxFragmentChars();
    }

    @Override
    public int getMaxSummaryMethods() {
        return config.getMaxSummaryMethods();
    }

    @Override
    public int getMaxSummaryFields() {
        return config.getMaxSummaryFields();
    }

    @Override
    public int getMaxFieldBudget() {
        return config.getMaxFieldChars();
    }

    @Override
    public int getMaxDocumentBudget() {
        return config.getMaxDocumentChars();
    }
}
