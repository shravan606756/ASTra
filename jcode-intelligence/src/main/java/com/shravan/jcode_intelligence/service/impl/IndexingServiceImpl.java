package com.shravan.jcode_intelligence.service.impl;

import com.shravan.jcode_intelligence.converter.DocumentConverter;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.parser.JavaProjectParser;
import com.shravan.jcode_intelligence.service.IndexingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class IndexingServiceImpl implements IndexingService {

    private final JavaProjectParser parser;
    private final DocumentConverter converter;
    private final VectorStore vectorStore;

    public IndexingServiceImpl(JavaProjectParser parser,
                               DocumentConverter converter,
                               VectorStore vectorStore) {

        this.parser = parser;
        this.converter = converter;
        this.vectorStore = vectorStore;
    }

    @Override
    public void indexProject(String projectPath) throws IOException {

        System.out.println("Parsing project...");

        List<CodeChunk> chunks = parser.parse(Path.of(projectPath));

        System.out.println("Chunks : " + chunks.size());

        List<Document> documents = converter.convert(chunks);

        System.out.println("Documents : " + documents.size());

        System.out.println("Generating embeddings...");

        vectorStore.add(documents);

        System.out.println("Documents indexed successfully.");
    }

}