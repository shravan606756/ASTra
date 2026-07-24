package com.shravan.jcode_intelligence.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PromptTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);
    private static final String DEFAULT_TEMPLATE = "answer-question.st";

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public PromptTemplateLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String getTemplate(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            templateName = DEFAULT_TEMPLATE;
        }

        if (!templateName.endsWith(".st")) {
            templateName = templateName + ".st";
        }

        return templateCache.computeIfAbsent(templateName, this::loadTemplateFromClasspath);
    }

    private String loadTemplateFromClasspath(String templateName) {
        String resourcePath = "classpath:prompts/" + templateName;
        try {
            Resource resource = resourceLoader.getResource(resourcePath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.info("Successfully loaded prompt template: {}", templateName);
                    return content;
                }
            }
            log.warn("Prompt template resource not found: {}. Falling back to {}", resourcePath, DEFAULT_TEMPLATE);
        } catch (Exception e) {
            log.error("Failed to load prompt template {}: {}", templateName, e.getMessage(), e);
        }

        if (!DEFAULT_TEMPLATE.equals(templateName)) {
            return getTemplate(DEFAULT_TEMPLATE);
        }

        return "You are an expert Java developer. Answer using context below:\n{{context}}\nUser Question: {{question}}\nAnswer:";
    }
}
