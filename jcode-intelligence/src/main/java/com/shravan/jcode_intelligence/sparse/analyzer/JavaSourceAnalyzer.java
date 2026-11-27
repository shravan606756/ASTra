package com.shravan.jcode_intelligence.sparse.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.*;

/**
 * Java-aware analyzer for Lucene BM25 indexing and searching.
 *
 * <p>Splits camelCase, snake_case, and alphanumeric tokens into constituent subwords
 * while preserving original tokens and lowercasing.
 *
 * <p>At index time, a {@link FlattenGraphFilter} is required to convert the graph
 * produced by {@link WordDelimiterGraphFilter} into a linear token stream acceptable
 * to Lucene's {@code IndexWriter}.
 */
public class JavaSourceAnalyzer {

    public static final int WORD_DELIMITER_FLAGS =
            GENERATE_WORD_PARTS |
            GENERATE_NUMBER_PARTS |
            SPLIT_ON_CASE_CHANGE |
            SPLIT_ON_NUMERICS |
            PRESERVE_ORIGINAL;

    /**
     * Creates an index-time analyzer suitable for Java source code.
     * Token chain: StandardTokenizer -> WordDelimiterGraphFilter -> FlattenGraphFilter -> LowerCaseFilter.
     */
    public static Analyzer createIndexAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();
                TokenStream filter = new WordDelimiterGraphFilter(tokenizer, WORD_DELIMITER_FLAGS, null);
                filter = new FlattenGraphFilter(filter);
                filter = new LowerCaseFilter(filter);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
    }

    /**
     * Creates a search-time analyzer suitable for Java source code queries.
     * Token chain: StandardTokenizer -> WordDelimiterGraphFilter -> LowerCaseFilter.
     */
    public static Analyzer createSearchAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();
                TokenStream filter = new WordDelimiterGraphFilter(tokenizer, WORD_DELIMITER_FLAGS, null);
                filter = new LowerCaseFilter(filter);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
    }

    /**
     * Creates a per-field index analyzer with keyword overrides for identity fields
     * ("id", "chunkId", "repositoryId", "type").
     */
    public static Analyzer createPerFieldIndexAnalyzer() {
        Map<String, Analyzer> fieldAnalyzers = new HashMap<>();
        KeywordAnalyzer keywordAnalyzer = new KeywordAnalyzer();
        fieldAnalyzers.put("id", keywordAnalyzer);
        fieldAnalyzers.put("chunkId", keywordAnalyzer);
        fieldAnalyzers.put("repositoryId", keywordAnalyzer);
        fieldAnalyzers.put("type", keywordAnalyzer);

        return new PerFieldAnalyzerWrapper(createIndexAnalyzer(), fieldAnalyzers);
    }

    /**
     * Creates a per-field search analyzer with keyword overrides for identity fields.
     */
    public static Analyzer createPerFieldSearchAnalyzer() {
        Map<String, Analyzer> fieldAnalyzers = new HashMap<>();
        KeywordAnalyzer keywordAnalyzer = new KeywordAnalyzer();
        fieldAnalyzers.put("id", keywordAnalyzer);
        fieldAnalyzers.put("chunkId", keywordAnalyzer);
        fieldAnalyzers.put("repositoryId", keywordAnalyzer);
        fieldAnalyzers.put("type", keywordAnalyzer);

        return new PerFieldAnalyzerWrapper(createSearchAnalyzer(), fieldAnalyzers);
    }

    /**
     * Tokenizes the given text using the provided analyzer and extracts all token strings.
     */
    public static List<String> extractTokens(Analyzer analyzer, String fieldName, String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        try (TokenStream stream = analyzer.tokenStream(fieldName, text)) {
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String term = termAtt.toString();
                if (!term.isBlank()) {
                    tokens.add(term);
                }
            }
            stream.end();
        } catch (IOException e) {
            throw new RuntimeException("Failed to tokenize text for field " + fieldName, e);
        }
        return tokens;
    }
}
