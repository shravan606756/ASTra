package com.shravan.jcode_intelligence.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class JavaProjectParser {

    public List<CompilationUnit> parseProject(Path projectRoot) throws IOException {

        List<CompilationUnit> units = new ArrayList<>();

        Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {

                    try {
                        units.add(StaticJavaParser.parse(path));
                    }
                    catch (Exception e) {
                        System.out.println("Failed to parse : " + path);
                    }

                });

        return units;
    }
}