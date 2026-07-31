package com.shravan.jcode_intelligence.config;

import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class JGitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JGitConfiguration.class);

    @PostConstruct
    public void configureJGit() {
        try {
            String osName = System.getProperty("os.name");
            boolean isWindows = osName != null && osName.toLowerCase().contains("win");
            String mmapStatus = "DEFAULT";
            String reason = "Non-Windows platform";

            WindowCacheConfig config = new WindowCacheConfig();

            if (isWindows) {
                config.setPackedGitMMAP(false);
                mmapStatus = "DISABLED";
                reason = "Prevent Windows MappedByteBuffer locks";
            }

            config.install();

            log.info("\n=========================================================\n" +
                     "ASTra - JGit Runtime Configuration\n" +
                     "---------------------------------------------------------\n" +
                     "Operating System : {}\n" +
                     "JGit Version     : 7.0.0.202409031743-r\n" +
                     "PackedGitMMAP    : {}\n" +
                     "Reason           : {}\n" +
                     "Status           : SUCCESS\n" +
                     "=========================================================", 
                     osName, mmapStatus, reason);

        } catch (Exception e) {
            log.error("\n=========================================================\n" +
                      "ASTra - JGit Runtime Configuration\n" +
                      "---------------------------------------------------------\n" +
                      "Status           : FAILED\n" +
                      "Error            : {}\n" +
                      "=========================================================", 
                      e.getMessage(), e);
            throw new RuntimeException("Failed to initialize JGit configuration", e);
        }
    }
}
