package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.service.IndexingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ParserTestRunner implements CommandLineRunner {

    private final IndexingService indexingService;

    public ParserTestRunner(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Override
    public void run(String... args) throws Exception {

        String projectPath =
                "C:\\Users\\Shravan\\OneDrive\\Desktop\\jcode-intelligence\\jcode-intelligence\\src\\main\\java";

        System.out.println("\n==============================");
        System.out.println("STARTING INDEXING");
        System.out.println("==============================");

        indexingService.indexProject(projectPath);

        System.out.println("\n==============================");
        System.out.println("INDEXING FINISHED");
        System.out.println("==============================");
    }
}