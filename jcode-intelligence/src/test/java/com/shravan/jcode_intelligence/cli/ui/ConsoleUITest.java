package com.shravan.jcode_intelligence.cli.ui;

import com.shravan.jcode_intelligence.dto.response.ChatResponse;
import com.shravan.jcode_intelligence.dto.response.ChunkResponse;
import com.shravan.jcode_intelligence.dto.response.GroupedSourceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying CLI rendering of chat responses, consolidated grouped source headers,
 * line ranges, fallback formatting, and attribution counts in {@link ConsoleUI}.
 */
class ConsoleUITest {

    private final ConsoleUI consoleUI = new ConsoleUI();
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    @DisplayName("printChatResponse displays consolidated file source headers with chunk counts and combined line ranges")
    void testPrintChatResponse_groupedSources() {
        GroupedSourceResponse g1 = new GroupedSourceResponse();
        g1.setFilePath("src/OrderService.java");
        g1.setClassName("OrderService");
        g1.setLineRanges(List.of("10-25", "40-65"));

        GroupedSourceResponse g2 = new GroupedSourceResponse();
        g2.setFilePath("src/PaymentService.java");
        g2.setClassName("PaymentService");
        g2.setLineRanges(List.of("1-50"));

        ChatResponse response = new ChatResponse("What is the order workflow?", "Here is the explanation.", List.of());
        response.setGroupedSources(List.of(g1, g2));
        response.setSuppliedChunkCount(3);
        response.setRetrievedChunkCount(8);

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("Sources (3 chunks supplied / 8 chunks retrieved)"), "Must display attribution chunk count in header");
        assertTrue(output.contains("OrderService.java (lines 10-25, 40-65)"), "Must display consolidated file and line ranges");
        assertTrue(output.contains("PaymentService.java (lines 1-50)"), "Must display payment service line range");
    }

    @Test
    @DisplayName("printChatResponse renders files without line range suffix when lineRanges is null or empty")
    void testPrintChatResponse_groupedSources_nullAndEmptyLineRanges() {
        GroupedSourceResponse g1 = new GroupedSourceResponse();
        g1.setFilePath("src/OrderService.java");
        g1.setClassName("OrderService");
        g1.setLineRanges(null);

        GroupedSourceResponse g2 = new GroupedSourceResponse();
        g2.setFilePath("src/PaymentService.java");
        g2.setClassName("PaymentService");
        g2.setLineRanges(List.of());

        ChatResponse response = new ChatResponse("Query", "Answer", List.of());
        response.setGroupedSources(List.of(g1, g2));
        response.setSuppliedChunkCount(2);
        response.setRetrievedChunkCount(4);

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("Sources (2 chunks supplied / 4 chunks retrieved)"));
        assertTrue(output.contains("OrderService.java"));
        assertFalse(output.contains("OrderService.java (lines"));
        assertTrue(output.contains("PaymentService.java"));
        assertFalse(output.contains("PaymentService.java (lines"));
    }

    @Test
    @DisplayName("printChatResponse renders class name or package summary when file path is blank")
    void testPrintChatResponse_groupedSources_blankFilePaths() {
        GroupedSourceResponse gWithClass = new GroupedSourceResponse();
        gWithClass.setFilePath("   ");
        gWithClass.setClassName("OrderService");
        gWithClass.setLineRanges(List.of("10-20"));

        GroupedSourceResponse gWithoutClass = new GroupedSourceResponse();
        gWithoutClass.setFilePath("");
        gWithoutClass.setClassName(null);
        gWithoutClass.setLineRanges(null);

        ChatResponse response = new ChatResponse("Query", "Answer", List.of());
        response.setGroupedSources(List.of(gWithClass, gWithoutClass));

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("OrderService (lines 10-20)"), "Must fallback to class name when file path is blank");
        assertTrue(output.contains("Package Summary"), "Must fallback to Package Summary when file path and class name are both absent");
    }

    @Test
    @DisplayName("printChatResponse displays unadorned Sources header when attribution counts are missing")
    void testPrintChatResponse_groupedSources_missingAttributionCounts() {
        GroupedSourceResponse g = new GroupedSourceResponse();
        g.setFilePath("src/OrderService.java");
        g.setClassName("OrderService");
        g.setLineRanges(List.of("1-10"));

        ChatResponse response = new ChatResponse("Query", "Answer", List.of());
        response.setGroupedSources(List.of(g));
        response.setSuppliedChunkCount(null);
        response.setRetrievedChunkCount(null);

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("Sources"));
        assertFalse(output.contains("chunks supplied"), "Must not display chunk count template when counts are missing");
        assertTrue(output.contains("OrderService.java (lines 1-10)"));
    }

    @Test
    @DisplayName("printChatResponse gracefully falls back to flat sources when groupedSources is empty")
    void testPrintChatResponse_flatSourcesFallback() {
        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "OrderService",
                "createOrder", "createOrder()", "src/OrderService.java", 15, 30, "code");

        ChatResponse response = new ChatResponse("Query", "Answer", List.of(chunk));

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("Sources"));
        assertTrue(output.contains("OrderService.java:15"));
    }

    @Test
    @DisplayName("printChatResponse displays chunk counts with flat sources fallback when counts are present")
    void testPrintChatResponse_flatSourcesWithChunkCounts() {
        ChunkResponse chunk = new ChunkResponse("METHOD", "repo-1", "com.example", "OrderService",
                "createOrder", "createOrder()", "src/OrderService.java", 15, 30, "code");

        ChatResponse response = new ChatResponse("Query", "Answer", List.of(chunk));
        response.setSuppliedChunkCount(1);
        response.setRetrievedChunkCount(5);

        consoleUI.printChatResponse(response);

        String output = outContent.toString();
        assertTrue(output.contains("Sources (1 chunks supplied / 5 chunks retrieved)"));
        assertTrue(output.contains("OrderService.java:15"));
    }
}

