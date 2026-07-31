package com.shravan.jcode_intelligence;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

public class JGitWindowCacheTest {
    @Test
    public void checkWindowCache() throws Exception {
        try {
            Class<?> configClass = Class.forName("org.eclipse.jgit.storage.file.WindowCacheConfig");
            System.out.println("Available methods in WindowCacheConfig:");
            for (Method m : configClass.getDeclaredMethods()) {
                System.out.println(" - " + m.toString());
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
        }
    }
}
