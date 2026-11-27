package com.shravan.jcode_intelligence.sparse.query;

import com.shravan.jcode_intelligence.config.RetrievalConfig;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LuceneQueryBuilderTest {

    @Test
    @DisplayName("Empty or whitespace queries return MatchNoDocsQuery")
    void testEmptyQuery() {
        LuceneQueryBuilder builder = new LuceneQueryBuilder();

        Query q1 = builder.buildQuery(null, "repo-1");
        assertTrue(q1 instanceof MatchNoDocsQuery);

        Query q2 = builder.buildQuery("   ", "repo-1");
        assertTrue(q2 instanceof MatchNoDocsQuery);
    }

    @Test
    @DisplayName("Scoped query includes text query and repository filter clause")
    void testScopedQuery() {
        RetrievalConfig config = new RetrievalConfig();
        LuceneQueryBuilder builder = new LuceneQueryBuilder(config);

        Query query = builder.buildQuery("UserService findById", "my-repo");
        assertTrue(query instanceof BooleanQuery);

        BooleanQuery bq = (BooleanQuery) query;
        assertEquals(2, bq.clauses().size(), "Scoped query should have MUST text and FILTER repo");

        boolean hasFilter = bq.clauses().stream()
                .anyMatch(c -> c.occur() == BooleanClause.Occur.FILTER && c.query() instanceof TermQuery);
        assertTrue(hasFilter, "Should have repositoryId filter clause");

        boolean hasMust = bq.clauses().stream()
                .anyMatch(c -> c.occur() == BooleanClause.Occur.MUST);
        assertTrue(hasMust, "Should have MUST clause for search text");
    }

    @Test
    @DisplayName("Unscoped query does not include repository filter clause")
    void testUnscopedQuery() {
        LuceneQueryBuilder builder = new LuceneQueryBuilder();

        Query query = builder.buildQuery("UserService", null);
        assertTrue(query instanceof BooleanQuery);

        BooleanQuery bq = (BooleanQuery) query;
        boolean hasFilter = bq.clauses().stream()
                .anyMatch(c -> c.occur() == BooleanClause.Occur.FILTER);
        assertFalse(hasFilter, "Unscoped query should not have FILTER clause");
    }
}
