package com.shravan.jcode_intelligence.model;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Generic, framework-agnostic classification of top-level Java components.
 *
 * <p>Used by the architecture retrieval pipeline to organize code components
 * into structural roles (Controllers, Services, Repositories, Entities, etc.)
 * regardless of the underlying framework (Spring Boot, Jakarta EE, Quarkus, or plain Java).
 */
public enum ComponentRole {

    /** Application entry point or main bootstrapper class. */
    APPLICATION,

    /** HTTP REST / MVC Controller or web resource. */
    CONTROLLER,

    /** Business logic service or domain use case. */
    SERVICE,

    /** Data access repository, DAO, or persistence mapper. */
    REPOSITORY,

    /** Domain entity or persistence data model. */
    ENTITY,

    /** System contract or interface abstraction. */
    INTERFACE,

    /** Extension point or design pattern strategy/plugin. */
    STRATEGY,

    /** System configuration class. */
    CONFIGURATION,

    /** Event declaration or event listener. */
    EVENT,

    /** General entry point or runner. */
    ENTRY_POINT,

    /** Helper or utility class. */
    UTILITY,

    /** Unclassified / general component. */
    UNKNOWN;

    /**
     * Infer the component role from chunk metadata.
     * Checks annotations, class name, package name, interfaces, and type.
     */
    @SuppressWarnings("unchecked")
    public static ComponentRole fromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return UNKNOWN;
        }

        String type = String.valueOf(metadata.getOrDefault("type", ""));
        String className = String.valueOf(metadata.getOrDefault("className", ""));
        String packageName = String.valueOf(metadata.getOrDefault("packageName", "")).toLowerCase(Locale.ROOT);
        Object annotationsObj = metadata.get("annotations");
        String annotationsStr = annotationsObj != null ? annotationsObj.toString().toLowerCase(Locale.ROOT) : "";

        if ("INTERFACE".equalsIgnoreCase(type)) {
            if (className.endsWith("Repository") || className.endsWith("Dao")) {
                return REPOSITORY;
            }
            if (className.endsWith("Strategy") || className.endsWith("Handler") || className.endsWith("Plugin") || className.endsWith("Provider")) {
                return STRATEGY;
            }
            if (className.endsWith("Service")) {
                return SERVICE;
            }
            return INTERFACE;
        }

        // Check annotations & class names for APPLICATION
        if (annotationsStr.contains("springbootapplication") || className.endsWith("Application") || className.endsWith("Main")) {
            return APPLICATION;
        }

        // Check CONTROLLER
        if (annotationsStr.contains("controller") || annotationsStr.contains("restcontroller") || annotationsStr.contains("path")
                || className.endsWith("Controller") || className.endsWith("Resource") || className.endsWith("Endpoint")) {
            return CONTROLLER;
        }

        // Check SERVICE
        if (annotationsStr.contains("service") || annotationsStr.contains("component") || annotationsStr.contains("usecase")
                || className.endsWith("Service") || className.endsWith("ServiceImpl") || className.endsWith("UseCase") || className.endsWith("Handler")) {
            return SERVICE;
        }

        // Check REPOSITORY
        if (annotationsStr.contains("repository") || className.endsWith("Repository") || className.endsWith("Dao")) {
            return REPOSITORY;
        }

        // Check ENTITY
        if (annotationsStr.contains("entity") || annotationsStr.contains("table") || annotationsStr.contains("document")
                || packageName.contains(".entity") || packageName.contains(".domain") || packageName.contains(".model")
                || className.endsWith("Entity") || className.endsWith("Model")) {
            return ENTITY;
        }

        // Check CONFIGURATION
        if (annotationsStr.contains("configuration") || className.endsWith("Config") || className.endsWith("Configuration")) {
            return CONFIGURATION;
        }

        // Check STRATEGY
        if (className.endsWith("Strategy") || className.endsWith("Plugin") || className.endsWith("Provider")) {
            return STRATEGY;
        }

        // Check EVENT
        if (annotationsStr.contains("eventlistener") || className.endsWith("Event") || className.endsWith("Listener")) {
            return EVENT;
        }

        // Check UTILITY
        if (className.endsWith("Utils") || className.endsWith("Util") || className.endsWith("Helper")) {
            return UTILITY;
        }

        return UNKNOWN;
    }
}
