package com.shravan.jcode_intelligence.sparse.mapper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps between Spring AI {@link org.springframework.ai.document.Document} and Lucene {@link Document}.
 *
 * <p>Preserves all metadata faithfully across serialization roundtrips by persisting the entire
 * metadata map into a dedicated stored JSON field, while simultaneously exposing key textual and
 * identity fields for BM25 indexing and filtering.
 */
@Component
public class LuceneDocumentMapper {

    private final Gson gson = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /**
     * Converts a Spring AI Document into a Lucene Document.
     */
    public Document toLuceneDocument(org.springframework.ai.document.Document springDoc) {
        Document luceneDoc = new Document();

        String id = springDoc.getId() != null ? springDoc.getId() : "";
        luceneDoc.add(new StringField("id", id, Field.Store.YES));

        Map<String, Object> meta = springDoc.getMetadata() != null ? springDoc.getMetadata() : Collections.emptyMap();

        // Exact identity fields
        String repoId = meta.containsKey("repositoryId") && meta.get("repositoryId") != null
                ? meta.get("repositoryId").toString() : "default";
        luceneDoc.add(new StringField("repositoryId", repoId, Field.Store.YES));

        if (meta.containsKey("chunkId") && meta.get("chunkId") != null) {
            luceneDoc.add(new StringField("chunkId", meta.get("chunkId").toString(), Field.Store.YES));
        }
        if (meta.containsKey("type") && meta.get("type") != null) {
            luceneDoc.add(new StringField("type", meta.get("type").toString(), Field.Store.YES));
        }

        // Textual / search-boosted fields
        String content = springDoc.getText() != null ? springDoc.getText() : "";
        luceneDoc.add(new TextField("content", content, Field.Store.YES));

        addTextFieldIfPresent(luceneDoc, "elementName", meta.get("elementName"));
        addTextFieldIfPresent(luceneDoc, "className", meta.get("className"));
        addTextFieldIfPresent(luceneDoc, "filePath", meta.get("filePath"));
        addTextFieldIfPresent(luceneDoc, "packageName", meta.get("packageName"));
        addTextFieldIfPresent(luceneDoc, "signature", meta.get("signature"));

        // Full metadata preservation
        String metadataJson = gson.toJson(meta);
        luceneDoc.add(new StoredField("_metadata_json", metadataJson));

        return luceneDoc;
    }

    /**
     * Converts a Lucene Document back into a Spring AI Document with score.
     */
    public org.springframework.ai.document.Document toSpringAiDocument(Document luceneDoc, float score) {
        String id = luceneDoc.get("id");
        String content = luceneDoc.get("content");
        String metaJson = luceneDoc.get("_metadata_json");

        Map<String, Object> metadata;
        if (metaJson != null && !metaJson.isBlank()) {
            metadata = gson.fromJson(metaJson, MAP_TYPE);
        } else {
            metadata = new HashMap<>();
        }

        metadata.put("score", (double) score);
        metadata.put("sparse_score", (double) score);

        return new org.springframework.ai.document.Document(id, content, metadata);
    }

    private void addTextFieldIfPresent(Document doc, String fieldName, Object val) {
        if (val != null) {
            String str = val.toString().trim();
            if (!str.isEmpty()) {
                doc.add(new TextField(fieldName, str, Field.Store.YES));
            }
        }
    }
}
