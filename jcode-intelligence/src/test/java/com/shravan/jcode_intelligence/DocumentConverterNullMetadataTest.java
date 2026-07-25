package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DocumentConverterNullMetadataTest {

    @Test
    public void testEmptyCodeChunkConversionHasZeroNullMetadataValues() {
        DocumentConverter converter = new DocumentConverter();
        CodeChunk emptyChunk = new CodeChunk();

        Document doc = converter.convert(emptyChunk);

        assertNotNull(doc);
        assertNotNull(doc.getMetadata());

        // Verify NO metadata value is null
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
            assertNotNull(entry.getValue(),
                    "Metadata key '" + entry.getKey() + "' MUST NOT have a null value");
        }
    }

    @Test
    public void testPackageChunkWithNullListsHasZeroNullMetadataValues() {
        DocumentConverter converter = new DocumentConverter();

        CodeChunk pkgChunk = new CodeChunk();
        pkgChunk.setType("PACKAGE");
        pkgChunk.setPackageName("com.example");
        pkgChunk.setElementName("com.example");
        pkgChunk.setContent("Package summary content");
        // imports, annotations, modifiers are left null

        Document doc = converter.convert(pkgChunk);

        assertNotNull(doc);
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
            assertNotNull(entry.getValue(),
                    "Metadata key '" + entry.getKey() + "' MUST NOT have a null value");
        }
    }

    @Test
    public void testChunkWithNestedRelationshipsHasZeroNullMetadataValues() {
        DocumentConverter converter = new DocumentConverter();

        CodeChunk chunk = new CodeChunk();
        chunk.setType("CLASS");
        chunk.setClassName("TestClass");

        Map<String, List<String>> rels = new HashMap<>();
        rels.put("extends", List.of("Base"));
        rels.put("nullRel", null);
        chunk.setRelationships(rels);

        Document doc = converter.convert(chunk);

        assertNotNull(doc);
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
            assertNotNull(entry.getValue(),
                    "Metadata key '" + entry.getKey() + "' MUST NOT have a null value");
        }

        if (doc.getMetadata().containsKey("relationships")) {
            @SuppressWarnings("unchecked")
            Map<String, List<String>> docRels = (Map<String, List<String>>) doc.getMetadata().get("relationships");
            assertFalse(docRels.containsKey("nullRel"), "Null relationships should be filtered out");
        }
    }
}
