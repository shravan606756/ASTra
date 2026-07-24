package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-runner")
public class ParserTestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ParserTestRunner.class);
    private final IndexingService indexingService;

    public ParserTestRunner(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @Override
    public void run(String... args) throws Exception {
        String projectPath =
                "C:\\Users\\Shravan\\OneDrive\\Desktop\\jcode-intelligence\\jcode-intelligence\\src\\main\\java";

        log.info("STARTING DEV RUNNER INDEXING for path: {}", projectPath);
        int count = indexingService.indexProject(projectPath);
        log.info("DEV RUNNER INDEXING FINISHED. Indexed {} documents.", count);
    }
}