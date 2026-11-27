package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.model.EmbeddedRecord;
import com.shravan.jcode_intelligence.util.DocumentIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class BatchEmbeddingHelperTest {

    private EmbeddingQueue mockQueue;
    private EmbeddingModel mockModel;
    private BatchEmbeddingHelper helper;

    @BeforeEach
    void setUp() {
        mockQueue = mock(EmbeddingQueue.class);
        mockModel = mock(EmbeddingModel.class);
        helper = new BatchEmbeddingHelper(mockQueue, mockModel);
    }

    @Test
    @DisplayName("Constructor requires non-null dependencies")
    void testConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new BatchEmbeddingHelper(null, mockModel));
        assertThrows(NullPointerException.class, () -> new BatchEmbeddingHelper(mockQueue, null));
    }

    @Test
    @DisplayName("embedAndStage produces deterministic IDs and stages records to queue")
    void testEmbedAndStageSuccess() throws Exception {
        String id1 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-1", "public class A {}");
        String id2 = DocumentIdGenerator.generateDeterministicId("repo-1", "chunk-2", "public class B {}");
        Document doc1 = new Document(id1, "public class A {}", Map.of("chunkId", "chunk-1"));
        Document doc2 = new Document(id2, "public class B {}", Map.of("chunkId", "chunk-2"));
        List<Document> batch = List.of(doc1, doc2);

        float[] emb1 = new float[]{0.1f, 0.2f};
        float[] emb2 = new float[]{0.3f, 0.4f};
        when(mockModel.embed(List.of("public class A {}", "public class B {}")))
                .thenReturn(List.of(emb1, emb2));

        List<EmbeddedRecord> staged = helper.embedAndStage("repo-1", "job-1", batch);

        assertEquals(2, staged.size());

        EmbeddedRecord rec1 = staged.get(0);
        assertEquals("repo-1", rec1.repositoryId());
        assertEquals("job-1", rec1.jobId());
        assertEquals("public class A {}", rec1.content());
        assertEquals(EmbeddedRecord.generateDeterministicId("repo-1", "chunk-1", "public class A {}"), rec1.id());
        assertArrayEquals(emb1, rec1.embedding());

        EmbeddedRecord rec2 = staged.get(1);
        assertEquals("repo-1", rec2.repositoryId());
        assertEquals("job-1", rec2.jobId());
        assertEquals("public class B {}", rec2.content());
        assertEquals(EmbeddedRecord.generateDeterministicId("repo-1", "chunk-2", "public class B {}"), rec2.id());
        assertArrayEquals(emb2, rec2.embedding());

        verify(mockQueue).submitAll(staged);
    }

    @Test
    @DisplayName("Mismatched or empty embeddings throw IllegalStateException and do not stage records")
    void testMismatchedOrEmptyEmbeddingsThrows() {
        Document doc = new Document("class C {}", Map.of("chunkId", "chunk-3"));
        when(mockModel.embed(anyList())).thenReturn(List.of()); // Empty response

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                helper.embedAndStage("repo-1", "job-1", List.of(doc)));

        assertTrue(ex.getMessage().contains("returned 0 embedding(s) for 1 input text(s)"));
        verifyNoInteractions(mockQueue);
    }

    @Test
    @DisplayName("Zero-length embedding vector throws IllegalStateException and does not stage records")
    void testEmptyVectorThrows() {
        Document doc = new Document("class C {}", Map.of("chunkId", "chunk-3"));
        when(mockModel.embed(anyList())).thenReturn(List.of(new float[0])); // Zero-length vector

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                helper.embedAndStage("repo-1", "job-1", List.of(doc)));

        assertTrue(ex.getMessage().contains("empty or null vector"));
        verifyNoInteractions(mockQueue);
    }

    @Test
    @DisplayName("InterruptedException during submitAll re-interrupts and wraps in RuntimeException")
    void testInterruptedExceptionHandling() throws Exception {
        Document doc = new Document("class D {}", Map.of("chunkId", "chunk-4"));
        when(mockModel.embed(anyList())).thenReturn(List.of(new float[]{0.5f}));
        doThrow(new InterruptedException("Queue full"))
                .when(mockQueue).submitAll(anyList());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                helper.embedAndStage("repo-1", "job-1", List.of(doc)));

        assertTrue(ex.getMessage().contains("Interrupted while staging records to embedding queue"));
        assertTrue(Thread.currentThread().isInterrupted());
        // Clean up interrupt flag
        Thread.interrupted();
    }

    @Test
    @DisplayName("Empty or null batch returns empty list without calling model")
    void testEmptyBatch() {
        assertTrue(helper.embedAndStage("repo", "job", null).isEmpty());
        assertTrue(helper.embedAndStage("repo", "job", List.of()).isEmpty());
        verifyNoInteractions(mockModel);
        verifyNoInteractions(mockQueue);
    }

    @Test
    @DisplayName("BatchEmbeddingWorker alias behaves identically")
    void testWorkerAlias() {
        BatchEmbeddingWorker worker = new BatchEmbeddingWorker(mockQueue, mockModel);
        assertSame(mockQueue, worker.getEmbeddingQueue());
        assertSame(mockModel, worker.getEmbeddingModel());
    }

    @Test
    @DisplayName("Progressive retry recovers from transient Windows socket buffer/bind error")
    void testTransientSocketErrorRetrySuccess() throws Exception {
        BatchEmbeddingHelper retryHelper = new BatchEmbeddingHelper(mockQueue, mockModel, 3, 50L);
        Document doc = new Document("class RetryTest {}", Map.of("chunkId", "chunk-r"));

        String socketErrorMsg = "HTTP 400 - {\"error\":\"Post \\\"http://127.0.0.1:59038/tokenize\\\": dial tcp 127.0.0.1:59038: bind: An operation on a socket could not be performed because the system lacked sufficient buffer space or because a queue was full.\"}";
        float[] validEmb = new float[]{0.1f, 0.2f};

        when(mockModel.embed(anyList()))
                .thenThrow(new RuntimeException(socketErrorMsg))
                .thenReturn(List.of(validEmb));

        List<EmbeddedRecord> staged = retryHelper.embedAndStage("repo-retry", "job-retry", List.of(doc));

        assertEquals(1, staged.size());
        assertArrayEquals(validEmb, staged.get(0).embedding());
        verify(mockModel, times(2)).embed(anyList());
        verify(mockQueue, times(1)).submitAll(anyList());
    }

    @Test
    @DisplayName("Non-transient error fails immediately without retrying")
    void testNonTransientErrorFailsImmediately() {
        BatchEmbeddingHelper retryHelper = new BatchEmbeddingHelper(mockQueue, mockModel, 3, 50L);
        Document doc = new Document("class Bad {}", Map.of("chunkId", "chunk-bad"));

        when(mockModel.embed(anyList()))
                .thenThrow(new IllegalArgumentException("Invalid input token length"));

        assertThrows(IllegalArgumentException.class, () ->
                retryHelper.embedAndStage("repo-err", "job-err", List.of(doc)));

        verify(mockModel, times(1)).embed(anyList());
    }
}
