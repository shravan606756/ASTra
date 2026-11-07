package com.shravan.jcode_intelligence;

import com.shravan.jcode_intelligence.dto.request.IndexRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests verifying {@link IndexRequest} edge cases, constructors, and field accessors.
 */
public class IndexRequestTest {

    @Test
    public void testDefaultConstructor() {
        IndexRequest req = new IndexRequest();
        assertNull(req.getProjectPath());
        assertNull(req.getGitUrl());
        assertNull(req.getRepositoryId());
        assertNull(req.getBranch());
    }

    @Test
    public void testThreeArgConstructorDefaultsBranchToNull() {
        IndexRequest req = new IndexRequest("/path/to/project", "https://github.com/example/repo.git", "repo-1");
        assertEquals("/path/to/project", req.getProjectPath());
        assertEquals("https://github.com/example/repo.git", req.getGitUrl());
        assertEquals("repo-1", req.getRepositoryId());
        assertNull(req.getBranch(), "Branch should default to null in 3-argument constructor");
    }

    @Test
    public void testFourArgConstructor() {
        IndexRequest req = new IndexRequest(
                "/path/to/project",
                "https://github.com/example/repo.git",
                "repo-1",
                "develop"
        );
        assertEquals("/path/to/project", req.getProjectPath());
        assertEquals("https://github.com/example/repo.git", req.getGitUrl());
        assertEquals("repo-1", req.getRepositoryId());
        assertEquals("develop", req.getBranch());
    }

    @Test
    public void testSettersAndGetters() {
        IndexRequest req = new IndexRequest();
        req.setProjectPath("/custom/path");
        req.setGitUrl("https://github.com/user/project.git");
        req.setRepositoryId("custom-repo");
        req.setBranch("feature/shallow-clone");

        assertEquals("/custom/path", req.getProjectPath());
        assertEquals("https://github.com/user/project.git", req.getGitUrl());
        assertEquals("custom-repo", req.getRepositoryId());
        assertEquals("feature/shallow-clone", req.getBranch());
    }
}

