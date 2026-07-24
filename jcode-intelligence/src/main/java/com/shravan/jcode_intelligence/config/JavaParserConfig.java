package com.shravan.jcode_intelligence.config;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Configuration for JavaParser.
 *
 * Provides a managed, thread-safe JavaParser bean configured explicitly for Java 21.
 * Eliminates reliance on global mutable StaticJavaParser state.
 */
@Configuration
public class JavaParserConfig {

    private static final Logger log = LoggerFactory.getLogger(JavaParserConfig.class);

    @Bean
    public JavaParser javaParser() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        log.info("Initialized JavaParser bean with LanguageLevel: JAVA_21");
        return new JavaParser(config);
    }
}
