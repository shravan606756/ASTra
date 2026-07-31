package com.shravan.jcode_intelligence;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class CloneInstrumentTest {

    @Test
    public void instrumentClone() throws Exception {
        Path tempDir = Files.createTempDirectory("astra-repo-");
        System.out.println("Cloning to: " + tempDir);
        
        Git git = Git.cloneRepository()
                .setURI("https://github.com/shravan606756/ASTra.git")
                .setDirectory(tempDir.toFile())
                .setCloneAllBranches(false)
                .call();
                
        Repository repo = git.getRepository();
        System.out.println("=== AFTER CLONE, BEFORE CLOSE ===");
        inspectState(repo);
        
        git.close();
        
        System.out.println("=== AFTER CLOSE ===");
        inspectState(repo);
        
        try {
            FileSystemUtils.deleteRecursively(tempDir);
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }
    }
    
    private void inspectState(Repository repo) {
        System.out.println("Repository: " + repo.getClass().getName());
        System.out.println("ObjectDatabase: " + repo.getObjectDatabase().getClass().getName());
        
        try {
            Class<?> wcClass = Class.forName("org.eclipse.jgit.internal.storage.file.WindowCache");
            Method getInstance = wcClass.getMethod("getInstance");
            Object cacheInstance = getInstance.invoke(null);
            System.out.println("WindowCache instance: " + (cacheInstance != null));
            
            // Try to dump open files in cache
            try {
                Method getOpenFiles = cacheInstance.getClass().getMethod("getOpenFiles");
                System.out.println("Open files in cache: " + getOpenFiles.invoke(cacheInstance));
            } catch (Exception e) {
                System.out.println("Could not get open files: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("Could not access WindowCache: " + e.getMessage());
        }
    }
}
