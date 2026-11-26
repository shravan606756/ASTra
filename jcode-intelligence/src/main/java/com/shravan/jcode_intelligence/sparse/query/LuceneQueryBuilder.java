package com.shravan.jcode_intelligence.sparse.query;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import com.shravan.jcode_intelligence.sparse.analyzer.JavaSourceAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Constructs field-boosted Boolean and fuzzy Lucene queries for Java-aware BM25 search.
 */
@Component
public class LuceneQueryBuilder {

    private final RetrievalConfig retrievalConfig;
    private final Analyzer searchAnalyzer;

    public LuceneQueryBuilder() {
        this(new RetrievalConfig());
    }

    @Autowired
    public LuceneQueryBuilder(RetrievalConfig retrievalConfig) {
        this.retrievalConfig = retrievalConfig != null ? retrievalConfig : new RetrievalConfig();
        this.searchAnalyzer = JavaSourceAnalyzer.createSearchAnalyzer();
    }

    /**
     * Builds a field-boosted Boolean and fuzzy query.
     *
     * @param queryText    user search query
     * @param repositoryId target repository ID (null or blank for unscoped search)
     * @return constructed Lucene Query
     */
    public Query buildQuery(String queryText, String repositoryId) {
        if (queryText == null || queryText.isBlank()) {
            return new MatchNoDocsQuery();
        }

        List<String> tokens = JavaSourceAnalyzer.extractTokens(searchAnalyzer, "content", queryText);
        if (tokens.isEmpty()) {
            String cleaned = queryText.trim().toLowerCase();
            if (!cleaned.isEmpty()) {
                tokens = List.of(cleaned.split("\\s+"));
            }
        }

        if (tokens.isEmpty()) {
            return new MatchNoDocsQuery();
        }

        BooleanQuery.Builder textQueryBuilder = new BooleanQuery.Builder();

        for (String token : tokens) {
            BooleanQuery.Builder tokenClauses = new BooleanQuery.Builder();

            addFieldQuery(tokenClauses, "elementName", token, retrievalConfig.getElementNameBoost());
            addFieldQuery(tokenClauses, "className", token, retrievalConfig.getClassNameBoost());
            addFieldQuery(tokenClauses, "filePath", token, retrievalConfig.getFilePathBoost());
            addFieldQuery(tokenClauses, "signature", token, retrievalConfig.getSignatureBoost());
            addFieldQuery(tokenClauses, "content", token, retrievalConfig.getContentBoost());

            BooleanQuery tokenQuery = tokenClauses.build();
            if (!tokenQuery.clauses().isEmpty()) {
                textQueryBuilder.add(tokenQuery, BooleanClause.Occur.SHOULD);
            }
        }

        BooleanQuery textQuery = textQueryBuilder.build();
        if (textQuery.clauses().isEmpty()) {
            return new MatchNoDocsQuery();
        }

        // Apply repository filtering if scoped
        if (repositoryId != null && !repositoryId.isBlank()) {
            return new BooleanQuery.Builder()
                    .add(textQuery, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term("repositoryId", repositoryId)), BooleanClause.Occur.FILTER)
                    .build();
        }

        return textQuery;
    }

    private void addFieldQuery(BooleanQuery.Builder builder, String fieldName, String termText, float boost) {
        if (boost <= 0.0f) {
            return;
        }

        Term term = new Term(fieldName, termText);
        Query termQuery = new BoostQuery(new TermQuery(term), boost);
        builder.add(termQuery, BooleanClause.Occur.SHOULD);

        if (termText.length() >= retrievalConfig.getFuzzyMinTermLength()
                && retrievalConfig.getFuzzyMaxEdits() > 0) {
            Query fuzzyQuery = new BoostQuery(
                    new FuzzyQuery(term, retrievalConfig.getFuzzyMaxEdits()),
                    boost * 0.8f);
            builder.add(fuzzyQuery, BooleanClause.Occur.SHOULD);
        }
    }
}
