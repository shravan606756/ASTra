package com.shravan.jcode_intelligence;

import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.Test;

public class JGitConfigTest {
    @Test
    public void testConfig() {
        WindowCacheConfig config = new WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
        System.out.println("Config installed successfully!");
    }
}
