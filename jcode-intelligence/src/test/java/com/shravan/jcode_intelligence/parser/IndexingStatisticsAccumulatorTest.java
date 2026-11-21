package com.shravan.jcode_intelligence.parser;

import com.shravan.jcode_intelligence.model.ChunkType;
import com.shravan.jcode_intelligence.model.CodeChunk;
import com.shravan.jcode_intelligence.model.IndexingStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexingStatisticsAccumulatorTest {

    @Test
    @DisplayName("Accumulates distinct nonblank packages, elemental types, and total chunks")
    void testBasicAccumulation() {
        IndexingStatisticsAccumulator acc = new IndexingStatisticsAccumulator();

        acc.accumulate(createChunk("com.example.a", ChunkType.CLASS.name(), "ClassA", null, 100));
        acc.accumulate(createChunk("com.example.a", ChunkType.INTERFACE.name(), "InterfaceA", null, 50));
        acc.accumulate(createChunk("com.example.b", ChunkType.ENUM.name(), "EnumB", null, 30));
        acc.accumulate(createChunk("com.example.b", ChunkType.RECORD.name(), "RecordB", null, 40));
        acc.accumulate(createChunk("com.example.b", ChunkType.FIELD.name(), "fieldB", null, 10));
        acc.accumulate(createChunk("com.example.b", ChunkType.CONSTRUCTOR.name(), "RecordB", null, 25));
        acc.accumulate(createChunk("com.example.c", ChunkType.METHOD.name(), "doWork", null, 80));
        acc.accumulate(createChunk("com.example.c", ChunkType.METHOD_FRAGMENT.name(), "frag1", "doWork", 120));
        acc.accumulate(createChunk("com.example.c", ChunkType.PACKAGE.name(), "com.example.c", null, 200));

        IndexingStatistics stats = acc.build(500L);

        assertEquals(500L, stats.getIndexingDurationMs());
        assertEquals(3, stats.getPackages());
        assertEquals(1, stats.getClasses());
        assertEquals(1, stats.getInterfaces());
        assertEquals(1, stats.getEnums());
        assertEquals(1, stats.getRecords());
        assertEquals(1, stats.getFields());
        assertEquals(1, stats.getConstructors());
        assertEquals(1, stats.getMethods());
        assertEquals(1, stats.getFragments());
        assertEquals(9, stats.getTotalChunks());
    }

    @Test
    @DisplayName("Tracks largest class and method using originalElementLength with strict tie handling")
    void testStrictTieHandling() {
        IndexingStatisticsAccumulator acc = new IndexingStatisticsAccumulator();

        // Classes: first seen with 200 should win tie over second seen with 200
        CodeChunk class1 = createChunk("pkg", ChunkType.CLASS.name(), "FirstClass", null, 200);
        class1.setClassName("FirstClass");
        CodeChunk class2 = createChunk("pkg", ChunkType.CLASS.name(), "SecondClass", null, 200);
        class2.setClassName("SecondClass");

        acc.accumulate(List.of(class1, class2));

        IndexingStatistics stats1 = acc.build(10L);
        assertEquals("FirstClass", stats1.getLargestClassName(), "Earlier encounter must win on tie");
        assertEquals(200, stats1.getLargestClassSize());

        // Strictly larger class updates largest
        CodeChunk class3 = createChunk("pkg", ChunkType.CLASS.name(), "ThirdClass", null, 250);
        class3.setClassName("ThirdClass");
        acc.accumulate(class3);

        IndexingStatistics stats2 = acc.build(10L);
        assertEquals("ThirdClass", stats2.getLargestClassName());
        assertEquals(250, stats2.getLargestClassSize());

        // Methods: Class#method formatting
        CodeChunk method1 = createChunk("pkg", ChunkType.METHOD.name(), "methodA", null, 150);
        method1.setClassName("MyService");
        method1.setElementName("methodA");

        CodeChunk method2 = createChunk("pkg", ChunkType.METHOD.name(), "methodB", null, 150);
        method2.setClassName("MyService");
        method2.setElementName("methodB");

        acc.accumulate(List.of(method1, method2));

        IndexingStatistics stats3 = acc.build(10L);
        assertEquals("MyService#methodA", stats3.getLargestMethodName(), "Earlier encounter must win on tie");
        assertEquals(150, stats3.getLargestMethodSize());

        // Method fragment with larger length
        CodeChunk frag = createChunk("pkg", ChunkType.METHOD_FRAGMENT.name(), "frag", "largeMethod", 300);
        frag.setClassName("MyService");
        frag.setMethodName("largeMethod");

        acc.accumulate(frag);

        IndexingStatistics stats4 = acc.build(10L);
        assertEquals("MyService#largeMethod", stats4.getLargestMethodName());
        assertEquals(300, stats4.getLargestMethodSize());
    }

    @Test
    @DisplayName("Empty accumulator returns default N/A names and zeroes")
    void testEmptyAccumulator() {
        IndexingStatisticsAccumulator acc = new IndexingStatisticsAccumulator();
        IndexingStatistics stats = acc.build(0L);

        assertEquals(0, stats.getTotalChunks());
        assertEquals(0, stats.getPackages());
        assertEquals("N/A", stats.getLargestClassName());
        assertEquals(0, stats.getLargestClassSize());
        assertEquals("N/A", stats.getLargestMethodName());
        assertEquals(0, stats.getLargestMethodSize());
    }

    private CodeChunk createChunk(String pkg, String type, String elementName, String methodName, int length) {
        CodeChunk chunk = new CodeChunk();
        chunk.setId("id-" + elementName);
        chunk.setPackageName(pkg);
        chunk.setType(type);
        chunk.setElementName(elementName);
        chunk.setMethodName(methodName);
        chunk.setOriginalElementLength(length);
        return chunk;
    }
}
