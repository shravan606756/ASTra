# ASTra Implementation Documentation

> **Parse the structure. Find the context. Understand the code.**

---

## 1. System Overview

### Purpose

ASTra (JCode Intelligence) is a Java 21 Spring Boot code-intelligence system that parses Java codebases at the Abstract Syntax Tree (AST) level and transforms them into a searchable, semantic vector index queryable in plain English. It provides repository-aware Retrieval-Augmented Generation (RAG) — meaning every answer traces back to exact classes, methods, files, and line numbers rather than generic guesses.

### High-Level Architecture

ASTra is organized into five cleanly separated layers:

```
CLI → API → Service → Parsing → LLM Orchestration
```

Backed by a vector-native persistence layer (PostgreSQL + pgvector).

```
┌─────────────────────────────────────────────────────────────────┐
│                     CLI Layer (ASTra Companion)                 │
│ InteractiveShell → CommandDispatcher → ConsoleUI & BunnyRenderer│
└──────────────┬──────────────────────────────────────────────────┘
               │ REST (POST /index, POST /chat, GET /health)
┌──────────────▼──────────────────────────────────────────────────┐
│                  API Layer (Spring Boot WebMVC)                 │
│         IndexController · ChatController · HealthController     │
└──────────────┬──────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────┐
│                       Service Layer                             │
│  IndexingService · ChatService · GitService · EmbeddingService  │
│  RetrievalService · SymbolExtractor · QueryIntentClassifier     │
└──────────────┬──────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────┐
│                AST Parsing & Chunking Layer                     │
│  JavaProjectParser → AstVisitor → ChunkGenerator                │
│  MetadataExtractor · ClassSummaryBuilder · MethodFragmenter     │
│  PackageSummaryGenerator · CharBasedBudgetEstimator             │
│  EmbeddingBudgetValidator · IndexingStatisticsCalculator        │
└──────────────┬──────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────┐
│                   LLM Orchestration Layer                       │
│  PromptRouter · PromptBuilder · PromptTemplateLoader            │
│  LLMClient · ArchitectureContextBuilder · ArchitectureAnalyzer  │
│  ResponseFormatter                                              │
└──────────────┬──────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────────┐
│                    Persistence Layer                            │
│      PostgreSQL + pgvector (vector_store table)                 │
│      Repository Statistics Table                                │
└─────────────────────────────────────────────────────────────────┘
```

### Technologies Used

| Layer | Technology |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot (WebMVC), Spring AI |
| Code Parsing | JavaParser (AST-level) |
| Vector Database | PostgreSQL + pgvector (768-dimensional, cosine distance) |
| Embeddings | Ollama with `nomic-embed-text` (local) |
| LLM Completion | Groq API (via OpenAI-compatible endpoint, `openai/gpt-oss-120b`) |
| Git Integration | JGit (Eclipse) |
| CLI | Windows Terminal native (`astra.bat`) |

### Relationship Between Core Mechanisms

1. **Static AST Analysis** — JavaParser walks every `.java` file, extracting structural elements (classes, methods, fields, constructors) as typed AST nodes rather than raw text.
2. **Semantic Chunking** — Code is split along logical AST boundaries (class/method level), not by character count or line count. Oversized elements are fragmented at statement boundaries.
3. **Embeddings** — Each semantic chunk is converted to a 768-dimensional vector via `nomic-embed-text` (Ollama), capturing semantic meaning.
4. **Vector Retrieval** — Queries are embedded and matched against stored vectors using cosine similarity via pgvector's ANN search, filtered by repository.
5. **Reranking** — Retrieved candidates are scored using intent-aware heuristic weights, symbol matching, and metadata completeness.
6. **LLM Generation** — Top-K reranked chunks are assembled into a budgeted, structured prompt and sent to the LLM for grounded answer generation.

---

## 2. End-to-End Processing Pipeline

### Indexing Flow (Repository → Vector Store)

```
1. Repository Acquisition          — GitServiceImpl.cloneRepository() / local path
2. Java File Discovery             — JavaProjectParser.parseCompilationUnits() (Files.walk + .java filter)
3. AST Parsing                     — JavaParser.parse() → CompilationUnit per file
4. AST Traversal                   — AstVisitor.visit() (top-level types only)
5. Hierarchical Chunk Generation   — ChunkGenerator.generateChunksForType/Enum/Record()
   5a. Type Summary                — ClassSummaryBuilder.buildSummary()
   5b. Field Chunks                — ChunkGenerator.generateFieldChunks()
   5c. Constructor Chunks          — ChunkGenerator.generateConstructorChunks()
   5d. Method Chunks               — ChunkGenerator.generateMethodChunks()
   5e. Oversized Fragmentation     — MethodFragmenter.fragment()
   5f. Nested Type Recursion       — ChunkGenerator.generateNestedTypeChunks()
6. Package Summary Generation      — PackageSummaryGenerator.generatePackageSummaries()
7. Repository ID Assignment        — IndexingServiceImpl (sets repositoryId on all chunks)
8. Document Conversion             — DocumentConverter.convert() → Spring AI Document
9. Pre-Embedding Budget Validation — EmbeddingBudgetValidator.validateAndAudit()
10. Existing Vector Cleanup        — DELETE FROM vector_store WHERE repositoryId = ?
11. Parallel Batch Embedding       — VectorStore.add() via ExecutorService thread pool
12. Statistics Calculation          — IndexingStatisticsCalculator.calculate()
13. Statistics Persistence          — RepositoryManagementService.saveRepositoryStats()
14. Git Cleanup                    — GitServiceImpl.cleanupRepository()
```

### Query Flow (Question → Grounded Answer)

```
1. Query Input                     — ChatController → ChatServiceImpl.answer()
2. Intent Classification           — QueryIntentClassifier.classify() → QueryIntent
3. Intent Resolution               — RetrievalServiceImpl.resolveIntent() (ChatMode → QueryIntent)
4. Symbol Extraction               — SymbolExtractor.extract() (PascalCase/camelCase regex)
5. Strategy-Specific Retrieval     — RetrievalStrategySelector.retrieve()
   5a. Deterministic SQL Lookup    — lookupByMetadata(), lookupMethodBySymbol(), lookupByType()
   5b. Vector Similarity Search    — VectorStore.similaritySearch() (cosine, filtered by repositoryId)
   5c. Context Enrichment          — enrichWithParentContext() (parent class summaries)
6. Deduplication                   — deduplicate() (composite key: repoId:filePath:startLine:endLine:type)
7. Heuristic Reranking            — RetrievalReranker.rerank() (5-factor scoring)
8. Prompt Template Selection       — PromptRouter.route(intent) → .st file
9. Context Budget Enforcement      — PromptBuilder.applyContextBudget() (max 20 docs, 18K chars)
10. Structured Context Formatting  — PromptBuilder.formatStructuredContext() (PACKAGE→CLASS→METHOD→FIELD)
11. Template Variable Substitution — {{question}}, {{context}}, {{repository}}
12. LLM Completion                 — LLMClient.generateAnswer() → ChatModel.call()
13. Source Formatting              — ResponseFormatter.formatChunks() → ChunkResponse list
14. Response Assembly              — ChatResponse (answer + sources + debug metadata)
```

---

## 3. Repository Ingestion and Indexing

### Git / Local Repository Handling

`IndexingServiceImpl` provides two entry points:

- **`indexProject(String projectPath, String repositoryId)`** — indexes from a local filesystem path.
- **`indexGitRepository(String gitUrl, String repositoryId)`** — clones via `GitServiceImpl`, indexes, then cleans up.

`GitServiceImpl.cloneRepository()` uses JGit to perform a shallow clone (`setCloneAllBranches(false)`) into a system temp directory (`astra-repo-*`). After indexing completes, `cleanupRepository()` walks the file tree, forces all files writable (to handle Git's read-only pack files on Windows), and deletes everything.

### Java File Discovery

`JavaProjectParser.parseCompilationUnits()` uses `Files.walk()` to recursively find all `.java` files under the project root. The `isIndexableJavaFile()` filter excludes:
- Build output directories: `target/`, `build/`, `.git/`
- Test fixture resources: `src/test/resources/`, `test-resources/`

This ensures only actual Java source files are parsed, not compiled outputs or test data.

### The 7-Step Indexing Pipeline

`IndexingServiceImpl.indexProject()` executes a strictly ordered 7-step pipeline:

**STEP 1 — Parse:** `JavaProjectParser.parse()` produces a flat list of `CodeChunk` objects by walking AST nodes via `AstVisitor`.

**STEP 2 — Package Summaries:** `PackageSummaryGenerator.generatePackageSummaries()` groups all chunks by `packageName`, then generates one `PACKAGE` summary chunk per unique package. Each summary lists the top-level types and type counts for that package, capped at 100 types.

**STEP 3 — Document Conversion:** `DocumentConverter.convert()` transforms every `CodeChunk` into a Spring AI `Document` with a comprehensive metadata map (27+ metadata fields including `type`, `repositoryId`, `packageName`, `className`, `elementName`, `signature`, `filePath`, `startLine`, `endLine`, `parentChunkId`, `nestingDepth`, `superClass`, `interfaces`, `relationships`, `fragmentIndex`, `methodName`, `summarized`, `fragmented`, etc.).

**STEP 4 — Budget Validation:** `EmbeddingBudgetValidator.validateAndAudit()` scans every document, builds a size-sorted audit of the top 20 largest documents (logging type, class, element, char count, estimated tokens), and throws an `EmbeddingException` if any document exceeds `maxDocumentChars` (default: 8,000 chars ≈ 2,000 tokens).

**STEP 5 — Vector Cleanup:** Existing vectors for the repository are deleted via raw SQL: `DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?`. This ensures re-indexing replaces rather than duplicates.

**STEP 6 — Adaptive Parallel Batch Embedding:** Documents are partitioned into batches of configurable size (default: 100). A fixed thread pool (default: 4 workers, configurable via `astra.embedding.max-parallel-workers`) processes batches concurrently. Each batch calls `vectorStore.add(batch)`, which triggers embedding generation via Ollama and vector insertion into pgvector. Progress is logged per-batch with percentage completion.

**STEP 7 — Statistics:** `IndexingStatisticsCalculator.calculate()` iterates all chunks, counting packages, classes, interfaces, enums, records, fields, constructors, methods, and fragments. It also tracks the largest class and largest method by `originalElementLength`.

### Reported Performance Metrics (from README)

Tested against the JavaParser codebase (1,763 classes, 19,786 methods):

| Metric | Value |
|---|---|
| Total chunks generated | 27,170 |
| Largest class parsed | `ASTParser` (334,531 chars) |
| Indexing time (sequential) | ~14.7 minutes (881,082 ms) |
| Indexing time (parallel batching) | **~7.5 minutes (452,552 ms)** |
| Speedup | **~49% faster** |

---

## 4. AST-Based Code Representation

This section explains how ASTra's implementation fundamentally differs from naive text splitting.

### AST Traversal Architecture

The traversal chain is: `JavaProjectParser` → `AstVisitor` → `ChunkGenerator`.

**`JavaProjectParser`** uses the JavaParser library to parse each `.java` file into a `CompilationUnit` AST. Files that fail to parse are logged and skipped.

**`AstVisitor`** extends JavaParser's `VoidVisitorAdapter<List<CodeChunk>>` and overrides three `visit()` methods:
- `visit(ClassOrInterfaceDeclaration, ...)` — handles classes and interfaces
- `visit(EnumDeclaration, ...)` — handles enums
- `visit(RecordDeclaration, ...)` — handles records

**Critical design decision:** `AstVisitor` only processes **top-level** type declarations (verified via `isTopLevelType()` which checks `findAncestor(TypeDeclaration.class).isEmpty()`). It deliberately does **not** call `super.visit()`, preventing the visitor from traversing into nested types. Inner class decomposition is handled recursively by `ChunkGenerator`, avoiding the double-counting that would occur if both the visitor and the generator processed the same inner classes.

Methods and constructors are also deliberately **not** visited at the `AstVisitor` level — they are decomposed inside `ChunkGenerator`'s hierarchical processing.

### Hierarchical Chunk Generation

`ChunkGenerator` orchestrates the decomposition of each type declaration into multiple chunks:

```
TypeDeclaration (CLASS/INTERFACE/ENUM/RECORD)
  ├── Type Summary Chunk (CLASS/INTERFACE/ENUM/RECORD)  ← ClassSummaryBuilder
  ├── Field Chunks (FIELD)                               ← one per field
  ├── Constructor Chunks (CONSTRUCTOR)                   ← one per constructor
  ├── Method Chunks (METHOD / METHOD_FRAGMENT)           ← one per method or fragmented
  └── Nested Type Chunks                                 ← recursive call
```

For each type, `ChunkGenerator.generateChunksForTypeDeclaration()` calls:
1. `summaryBuilder.buildSummary()` → creates the type-level summary chunk
2. `generateFieldChunks()` → one chunk per field declaration
3. `generateConstructorChunks()` → one chunk per constructor (with fragmentation if oversized)
4. `generateMethodChunks()` → one chunk per method (with fragmentation if oversized)
5. `generateNestedTypeChunks()` → recursively processes inner classes/enums at `nestingDepth + 1`

### Chunk Types (`ChunkType` Enum)

```
PACKAGE           — Package-level summary (one per package)
CLASS             — Top-level or nested class summary (signatures only, no bodies)
INTERFACE         — Interface summary
ENUM              — Enum declaration summary (constants + signatures)
RECORD            — Record declaration summary (components + methods)
METHOD            — Individual method with full body
CONSTRUCTOR       — Individual constructor with full body
FIELD             — Individual field declaration
METHOD_FRAGMENT   — Fragment of an oversized method, split at AST statement boundaries
```

### Chunk ID Generation

Every chunk receives a deterministic ID:

- **Type chunks:** `{package}.{className}::{TYPE}` (e.g., `com.example.MyClass::CLASS`)
- **Nested types:** `{package}.{OuterClass}.{InnerClass}::{TYPE}`
- **Member chunks:** `{parentChunkId}::{MEMBER_TYPE}::{elementName}` (e.g., `com.example.MyClass::CLASS::METHOD::doSomething`)

This hierarchical ID scheme enables parent-child lookups during retrieval.

### The `CodeChunk` Model

`CodeChunk` is the central data object carrying 27 fields organized into logical groups:

| Group | Fields | Purpose |
|---|---|---|
| Identity | `id`, `type`, `repositoryId` | Unique identification and repository isolation |
| Hierarchy | `parentChunkId`, `nestingDepth`, `outerClassName` | Parent-child relationships for context enrichment |
| Location | `packageName`, `className`, `elementName`, `filePath`, `startLine`, `endLine` | Precise source location |
| Source | `signature`, `content`, `language` | The actual code content and metadata |
| Annotations | `imports`, `annotations`, `modifiers` | Java language metadata |
| Type Metadata | `javadoc`, `superClass`, `interfaces` | Class hierarchy information |
| Relationships | `relationships` (Map) | Extensible relationship graph (`extends`, `implements`, `belongsTo`) |
| Fragmentation | `fragmentIndex`, `methodName` | Fragment ordering and parent method reference |
| Diagnostics | `contentLength`, `originalElementLength`, `summarized`, `fragmented` | Size tracking and processing state |

### Metadata Extraction (`MetadataExtractor`)

`MetadataExtractor` is responsible for reading the AST and populating `CodeChunk` fields. Its `populateCommonMetadata()` method sets all metadata **except** `content` (which is set by the caller). It dispatches based on node type:

- **`TypeDeclaration`** → sets `className` and `elementName` from the type name
- **`MethodDeclaration`** → sets `elementName`, `signature` (via `getDeclarationAsString(true, true, true)`)
- **`ConstructorDeclaration`** → sets `elementName`, `signature`
- **`FieldDeclaration`** → sets `elementName` (primary variable name), `signature` (full declaration string)
- **All nodes** → sets `packageName`, `imports`, `filePath`, `annotations`, `javadoc`, `modifiers`, `startLine`, `endLine`, `language`

### Class/Type Summary Generation (`ClassSummaryBuilder`)

`ClassSummaryBuilder` implements `TypeSummaryBuilder` and produces lightweight textual summaries suitable for embedding. It enforces hard budget caps on every section to guarantee summaries never exceed the 2,048-token embedding context:

| Section | Budget Cap |
|---|---|
| Javadoc | 800 characters (truncated with marker) |
| Imports | 20 maximum (remainder omitted with count) |
| Annotations | 10 maximum |
| Fields | min(25, `maxSummaryFields`) signatures only (no initializers) |
| Constructors | 15 maximum (signatures only) |
| Methods | min(40, `maxSummaryMethods`) signatures only |
| Inner Classes | 15 maximum (names only) |
| Enum Constants | min(25, `maxSummaryFields`) |

The summary includes: package declaration, imports, Javadoc, annotations, class header (with type parameters, extends, implements), field signatures, constructor signatures, method signatures, and inner class names. This provides the embedding model with a complete structural overview without method bodies.

### Method/Constructor Fragmentation (`MethodFragmenter`)

When a method or constructor exceeds `maxMethodChars` (default: 6,000), `MethodFragmenter` splits it at **AST statement boundaries** rather than arbitrary character positions:

1. **Statement accumulation:** Statements are added to the current fragment buffer until adding the next statement would exceed `maxFragmentChars` (default: 3,000).
2. **Fragment emission:** When the budget is exceeded, the accumulated statements are emitted as a `METHOD_FRAGMENT` chunk with:
   - The method signature as a preamble
   - Variable declarations from the preceding fragment as context (via `extractVariableContext()`)
   - A `// Fragment N of method` header
   - The fragment index for ordering
3. **Giant statement fallback:** If a single AST statement exceeds `maxFragmentChars` (e.g., a 50,000-character generated switch block), a line-based sub-fragmentation splits it into chunks of `maxFragmentChars`, with a `// ... [sub-fragment line cut]` marker for lines exceeding the limit.

**Variable context carry-over:** Each fragment includes declarations from the immediately preceding fragment, ensuring that variables used across fragment boundaries remain visible to the embedding model.

### Oversized Field Handling

Fields exceeding `maxFieldChars` (default: 3,000) — such as giant generated array initializers — are summarized by `ChunkGenerator.buildSummarizedFieldDeclaration()`:
- Annotations, modifiers, type, and variable name are preserved
- The first 200 characters of the initializer are shown
- The remainder is replaced with `/* N characters of oversized field initializer omitted */`
- The chunk is marked `summarized = true`

### Package Summary Generation (`PackageSummaryGenerator`)

After all file-level chunks are generated, `PackageSummaryGenerator` groups them by `packageName` and generates one `PACKAGE` chunk per unique package. Each summary contains:
- Package name
- List of top-level types (CLASS, INTERFACE, ENUM, RECORD) with names (capped at 100)
- Type count breakdown per chunk type

Package chunks have the ID format `package:{packageName}::PACKAGE`.

### Budget Estimation and Validation

**`ChunkBudgetEstimator` (SPI)** defines the strategy interface with methods: `exceedsMethodBudget()`, `exceedsFragmentBudget()`, `exceedsDocumentBudget()`, plus getters for all budget limits.

**`CharBasedBudgetEstimator`** implements this using character-length comparison against `ChunkingConfig` values:

| Budget | Default | Purpose |
|---|---|---|
| `maxMethodChars` | 6,000 | Threshold to trigger method fragmentation |
| `maxFragmentChars` | 3,000 | Target maximum per fragment |
| `maxFieldChars` | 3,000 | Threshold to trigger field summarization |
| `maxDocumentChars` | 8,000 | Absolute ceiling per document (≈ 2,000 tokens for nomic-embed-text) |
| `maxSummaryMethods` | 100 | Method signatures in class summary |
| `maxSummaryFields` | 50 | Field signatures in class summary |

**`EmbeddingBudgetValidator`** provides pre-embedding validation. It audits all documents, logs the top 20 largest by character count (with estimated token count at 4 chars/token), and throws `EmbeddingException` if any document exceeds `maxDocumentChars`. This fail-fast mechanism prevents the embedding model from receiving oversized input.

---

## 5. Embedding and Vector Storage

### Embedding Model

ASTra uses **Ollama** with the **`nomic-embed-text`** model for local embedding generation. Configuration:

```properties
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.options.model=nomic-embed-text
spring.ai.model.embedding=ollama
```

The `nomic-embed-text` model has a 2,048-token context window, which is why `maxDocumentChars` is calibrated at 8,000 characters (≈ 2,000 tokens at ~4 chars/token).

### Document Creation and Metadata

`DocumentConverter.convert()` transforms each `CodeChunk` into a Spring AI `Document` with:

- **Content:** The chunk's `content` field (summary text, method body, field declaration, or fragment body)
- **Metadata map (27+ fields):** All structural metadata is propagated, including `type`, `repositoryId`, `packageName`, `className`, `elementName`, `signature`, `filePath`, `startLine`, `endLine`, `parentChunkId`, `nestingDepth`, `outerClassName`, `javadoc`, `superClass`, `interfaces`, `relationships`, `fragmentIndex`, `methodName`, `chunkId`, `contentLength`, `originalElementLength`, `summarized`, `fragmented`, `imports`, `annotations`, `modifiers`, `language`

The converter enforces Spring AI's null-safety requirement: `putIfNotNull()` filters out null values, blank strings, and empty collections/maps before adding to metadata.

### PostgreSQL / pgvector Integration

```properties
spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.dimensions=768
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
```

Spring AI auto-initializes the pgvector `vector_store` table with:
- `id` — UUID primary key
- `content` — text content of the chunk
- `metadata` — JSONB column storing all metadata fields
- `embedding` — 768-dimensional vector (nomic-embed-text output)

### Repository Isolation

Every chunk is tagged with `repositoryId` in metadata. Repository-scoped operations:
- **Deletion:** `DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?`
- **Retrieval filtering:** `builder.filterExpression("repositoryId == '" + repositoryId + "'")`
- **SQL lookups:** All queries include `AND metadata->>'repositoryId' = ?` when a repository ID is provided

### Batch Insertion

Documents are inserted in configurable batches (default: 100 per batch) using up to 4 parallel worker threads. The adaptive batch sizing formula:

```java
int batchSize = Math.max(1, chunkingConfig.getBatchSize());
int totalBatches = (int) Math.ceil((double) totalDocs / batchSize);
int workerCount = Math.max(1, Math.min(totalBatches, configuredMaxWorkers));
```

This ensures the worker count never exceeds the number of batches.

---

## 6. Query Understanding and Intent Handling

### Query Input

Queries enter via `ChatController` → `ChatServiceImpl.answer(ChatRequest)`. The `ChatRequest` carries:
- `query` — the natural-language question
- `repositoryId` — target repository
- `topK` — optional override for result count
- `mode` — a `ChatMode` enum value

### ChatMode Enum

```java
AUTO, QUESTION_ANSWER, EXPLAIN_CLASS, EXPLAIN_METHOD, ARCHITECTURE,
PROJECT_SUMMARY, WORKFLOW, RELATIONSHIP, SEARCH, DESIGN
```

Explicit modes map directly to `QueryIntent` values, bypassing automatic classification.

### Intent Classification (`QueryIntentClassifier`)

When `ChatMode.AUTO` is used, `QueryIntentClassifier.classify()` applies a **rule-based, priority-ordered** classification pipeline:

| Priority | Detection Method | Result |
|---|---|---|
| 1 | Method-call syntax (`findAll()`, `parse()`) | `METHOD` |
| 2 | Multi-word phrase signals (workflow, architecture, design, etc.) | `WORKFLOW`, `ARCHITECTURE`, `PACKAGE`, `RELATIONSHIP`, `DESIGN` |
| 3 | Search/locate patterns ("where is", "find", "locate") | `SEARCH` |
| 4 | Explicit method/class keywords | `METHOD`, `CLASS` |
| 5 | Code identifier heuristics (PascalCase → CLASS, camelCase → METHOD) | `CLASS`, `METHOD` |
| 6 | Broad overview keywords ("overview", "summary") | `ARCHITECTURE` |
| 7 | No match | `UNKNOWN` |

Common English words (How, What, Where, Explain, Java, Spring, Class, etc.) are excluded from symbol detection to prevent false classification.

### Symbol Extraction (`SymbolExtractor`)

`SymbolExtractor.extract()` uses three regex patterns in priority order:

1. **PascalCase** (`[A-Z][a-zA-Z0-9]{2,}`) — e.g., `PromptRouter`, `CodeChunk`
2. **Method with parens** (`[a-z][a-zA-Z0-9]+\s*\(\)`) — e.g., `buildPrompt()`
3. **Standalone camelCase** (`[a-z]+[A-Z][a-zA-Z0-9]*`) — e.g., `doFragment`, `indexProject`

Common words are filtered via the same exclusion list. The extracted symbol drives deterministic metadata lookups in the retrieval pipeline.

### How Intent Influences the Pipeline

Each `QueryIntent` determines:
1. **Retrieval strategy** — which combination of SQL lookups and vector searches to execute
2. **Chunk-type weights** — how different chunk types are scored during reranking
3. **Adaptive Top-K** — how many candidates to fetch and return (per `IntentRetrievalConfig`)
4. **Prompt template** — which `.st` template file shapes the LLM instructions

---

## 7. Retrieval Architecture

### Pipeline Overview

```
Query → QueryIntentClassifier → RetrievalStrategySelector → RetrievalReranker → Ranked Documents
```

### `RetrievalServiceImpl` Orchestration

`RetrievalServiceImpl.retrieve()` executes a 4-step pipeline:

1. **Resolve intent:** Maps `ChatMode` to `QueryIntent` (explicit modes bypass classification)
2. **Execute strategy:** `strategySelector.retrieve(query, intent, repositoryId)` — returns raw documents
3. **Extract symbol and rerank:** `reranker.rerank(rawDocs, intent, targetSymbol, finalTopK)` — scores and trims
4. **Log analytics:** Records query, intent, chunk type distribution, candidate counts, and latency

### `RetrievalStrategySelector` — Intent-Specific Strategies

Each intent maps to a dedicated retrieval method combining **deterministic SQL lookups** and **vector similarity search**:

#### METHOD Strategy
1. Exact method symbol lookup via `lookupMethodBySymbol()` (SQL: `elementName = ?` OR `methodName = ?`)
2. Vector similarity search
3. Enrich with parent class summaries

#### CLASS Strategy
1. Exact class name lookup via `lookupByMetadata()` (SQL: `className = ?` OR `elementName = ?`)
2. Fetch parent package summary
3. Vector similarity search
4. Enrich with parent context

#### PACKAGE Strategy (deterministic only)
1. ALL `PACKAGE` chunks for the repository (via `lookupByType("PACKAGE")`)
2. All top-level `CLASS` summaries (nestingDepth = 0)
3. All `INTERFACE` and `RECORD` chunks
4. No vector search — fully deterministic

#### ARCHITECTURE Strategy
1. `ArchitectureContextBuilder.buildContext()` — retrieves all packages, classifies components by role, fetches interfaces, identifies orchestration methods, builds dependency graph, infers layers
2. Supplementary vector search (capped at 5) for specific query terms
3. FIELD chunks excluded

#### WORKFLOW Strategy
1. All package summaries
2. Symbol lookup if present
3. Broader vector search for diverse results
4. Enrich with parent context

#### RELATIONSHIP Strategy
1. Symbol lookup
2. Wider vector search
3. All interface chunks
4. Enrich with parent context

#### SEARCH Strategy (metadata-first)
1. Exact metadata match (className, elementName, methodName)
2. Signature partial match (SQL `ILIKE`)
3. Vector search only as fallback (if no exact results)

#### DESIGN Strategy
1. All package summaries
2. All interface chunks
3. Symbol lookup
4. Vector search

#### UNKNOWN Strategy (generic hybrid)
1. Symbol lookup if present
2. Vector similarity search
3. Enrich with parent context

### Vector Similarity Search

```java
SearchRequest.builder()
    .query(query)
    .topK(limit)
    .similarityThreshold(config.getSimilarityThreshold())  // default: 0.65
    .filterExpression("repositoryId == '" + repositoryId + "'")
    .build();
```

The raw search fetches `finalTopK × rawFetchMultiplier` candidates (default multiplier: 2), providing a larger pool for reranking.

### SQL Metadata Lookups

Direct PostgreSQL queries against the `vector_store` table's JSONB `metadata` column:

- **Symbol lookup:** `className = ? OR elementName = ? OR methodName = ? OR elementName LIKE ?#%`
- **Type lookup:** `metadata->>'type' = ?`
- **Package lookup:** `metadata->>'type' = 'PACKAGE' AND metadata->>'packageName' = ?`
- **Signature lookup:** `metadata->>'signature' ILIKE ?` (partial match)
- **ChunkId lookup:** `metadata->>'chunkId' = ?` (for parent-child resolution)
- **Top-level class lookup:** `type = 'CLASS' AND (nestingDepth = '0' OR nestingDepth IS NULL)`

All queries include repository filtering when `repositoryId` is provided.

### Context Enrichment

`enrichWithParentContext()` fetches parent class summaries for member-type chunks (METHOD, CONSTRUCTOR, FIELD, METHOD_FRAGMENT). For each member chunk with a non-null `parentChunkId`, it looks up the parent via `lookupByChunkId()` and prepends it to the results. This ensures the LLM always sees the enclosing class context alongside individual methods.

### Deduplication

Documents are deduplicated using a composite key: `{repositoryId}:{filePath}:{startLine}:{endLine}:{type}`. This prevents duplicate results from overlapping SQL and vector search results.

### `RetrievalReranker` — Heuristic Scoring

The reranker computes an additive score for each document using five factors:

| Factor | Score Contribution | Description |
|---|---|---|
| Chunk-type weight | `weight × 10.0` | Per-intent weight from `IntentRetrievalConfig` (e.g., METHOD=2.0 for METHOD intent) |
| Exact symbol match (element/method) | `+100.0` | elementName or methodName matches extracted symbol |
| Exact symbol match (class) | `+80.0` | className matches extracted symbol |
| Partial fragment match | `+90.0` | elementName starts with `{symbol}#` (fragment naming convention) |
| Metadata completeness | `+5.0/+5.0/+3.0` | Javadoc present / signature present / relationships present |
| FIELD demotion | `−30.0` | For ARCHITECTURE, DESIGN, PACKAGE intents |

For ARCHITECTURE intent, FIELD chunks are completely excluded (not just demoted).

Documents are sorted by descending score and trimmed to `finalTopK`.

### Adaptive Top-K Configuration

| Intent | Final Top-K | Raw Fetch (×2) |
|---|---|---|
| METHOD | 5 | 10 |
| CLASS | 8 | 16 |
| PACKAGE | 15 | 30 |
| ARCHITECTURE | 20 | 40 |
| WORKFLOW | 10 | 20 |
| RELATIONSHIP | 12 | 24 |
| SEARCH | 5 | 10 |
| DESIGN | 15 | 30 |
| UNKNOWN | 5 | 10 |

### Per-Intent Chunk-Type Weight Matrix

| Intent | METHOD | CLASS | PACKAGE | FIELD | INTERFACE | CONSTRUCTOR | METHOD_FRAGMENT |
|---|---|---|---|---|---|---|---|
| METHOD | 2.0 | 1.2 | 0.3 | 0.2 | 0.8 | 0.5 | 1.8 |
| CLASS | 1.0 | 2.0 | 0.5 | 0.3 | 1.2 | 0.5 | 0.8 |
| PACKAGE | 0.3 | 1.5 | 3.0 | 0.0 | 1.0 | 0.0 | 0.0 |
| ARCHITECTURE | 0.5 | 2.5 | 3.0 | 0.0 | 2.2 | 0.2 | 0.0 |
| WORKFLOW | 1.5 | 1.5 | 1.2 | 0.2 | 1.0 | 0.5 | 1.3 |
| RELATIONSHIP | 1.2 | 1.8 | 0.5 | 0.2 | 1.5 | 0.3 | 0.8 |
| SEARCH | 1.5 | 1.5 | 0.5 | 0.5 | 1.0 | 0.5 | 1.0 |
| DESIGN | 0.5 | 1.8 | 1.5 | 0.0 | 1.5 | 0.0 | 0.0 |
| UNKNOWN | 1.0 | 1.0 | 0.5 | 0.3 | 0.8 | 0.4 | 0.8 |

---

## 8. Architecture Context and Structural Inference

### `ArchitectureContextBuilder`

For `ARCHITECTURE` intent queries, the system constructs a comprehensive, deterministic view of the entire repository's structure. `ArchitectureContextBuilder.buildContext()` executes a 7-step pipeline:

1. **ALL Package Summaries** — Retrieves every `PACKAGE` chunk via SQL (never Top-K limited)
2. **Repository Statistics** — SQL `GROUP BY` on `metadata->>'type'` for type counts
3. **Components by Role** — Retrieves all `CLASS`, `ENUM`, `RECORD` chunks and classifies each via `ComponentRole.fromMetadata()`
4. **Architectural Interfaces** — Retrieves all top-level `INTERFACE` chunks
5. **Orchestration Methods** — Retrieves all `METHOD` and `CONSTRUCTOR` chunks, filters out accessors (`get*`, `set*`, `is*`, `has*`, `toString`, `hashCode`, `equals`), and selects up to 10 entry points matching orchestration patterns (`execute*`, `schedule*`, `create*`, `process*`, `handle*`, `parse*`, `index*`, `retrieve*`, `main`, or annotated with `@Scheduled`, `@Async`, `@Transactional`, `@Bean`)
6. **In-Memory Dependency Graph** — Builds `DependencyEdge` records from metadata: `interfaces` → `implements`, `superClass` → `extends`, `relationships` map → arbitrary edge types (`injects`, `calls`, `uses`). Self-loops and `Object` edges are filtered. Deduplication via `source->target:relType` key set.
7. **Layer Inference** — Maps `ComponentRole` to four architectural layers:

| Layer | Component Roles |
|---|---|
| Presentation Layer | `CONTROLLER` |
| Business Layer | `SERVICE`, `STRATEGY` |
| Persistence Layer | `REPOSITORY`, `ENTITY` |
| Infrastructure Layer | `CONFIGURATION`, `EVENT` |

### `ComponentRole` Classification

`ComponentRole.fromMetadata()` inspects chunk metadata to classify components into 12 structural roles using a priority-ordered heuristic:

| Role | Detection Heuristic |
|---|---|
| `APPLICATION` | Class name ends with `Application` or has `@SpringBootApplication` |
| `CONTROLLER` | `@RestController`, `@Controller`, or class name ends with `Controller` |
| `SERVICE` | `@Service` or class name ends with `Service`/`ServiceImpl` |
| `REPOSITORY` | `@Repository` or class name ends with `Repository`/`Dao` |
| `CONFIGURATION` | `@Configuration`, `@ConfigurationProperties`, or class name ends with `Config`/`Configuration` |
| `ENTITY` | `@Entity`, `@Table`, `@Document`, or class name ends with `Entity` |
| `STRATEGY` | Class name contains `Strategy`, `Selector`, `Resolver`, `Handler`, `Processor`, `Transformer`, `Converter`, `Mapper`, `Adapter`, `Decorator`, `Proxy`, or `Interceptor` |
| `EVENT` | Class name ends with `Event`, `Listener`, or `Publisher`, or annotations contain `@EventListener` |
| `UTILITY` | Class name ends with `Util`/`Utils`/`Helper`/`Helpers` |
| `EXCEPTION` | Class name ends with `Exception` or `Error` |
| `DTO` | Class name ends with `Dto`, `Request`, `Response`, `Payload` |
| `OTHER` | Default fallback |

### `ArchitectureAnalyzer`

`ArchitectureAnalyzer.analyzeAndFormat()` transforms an `ArchitectureContext` object into a structured text narrative with six labeled sections for LLM consumption:

1. `========== REPOSITORY OVERVIEW & STATISTICS ==========` — Repository ID + type counts
2. `========== INFERRED ARCHITECTURE LAYERS ==========` — Layer → component list
3. `========== PACKAGE STRUCTURE ==========` — Package summaries with content
4. `========== MAJOR COMPONENTS BY ROLE ==========` — Per-role component details (class name, package, type, extends, implements, annotations, source code)
5. `========== DEPENDENCY FLOW & GRAPH ==========` — Dependency edges
6. `========== ENTRY POINTS & ORCHESTRATION METHODS ==========` — Method signatures with source code

### Budgeted Architecture Context

`buildBudgetedContext()` provides an alternative path that uses only the already-retrieved and reranked documents (no additional database queries). This prevents unbounded fetching while still computing lightweight statistics and topology. Used by `PromptBuilder` for architecture prompts where the documents have already been budget-trimmed.

---

## 9. LLM Prompt Engineering

### Prompt Template System

ASTra uses 9 `.st` (StringTemplate-style) template files stored in `classpath:prompts/`:

| Template | Intent | Purpose |
|---|---|---|
| `answer-question.st` | `UNKNOWN` | General code comprehension questions |
| `explain-class.st` | `CLASS` | Class/interface explanation |
| `explain-method.st` | `METHOD` | Method implementation explanation |
| `architecture.st` | `ARCHITECTURE` | System-wide architecture overview |
| `summarize-project.st` | `PACKAGE` | Package structure and project summary |
| `workflow.st` | `WORKFLOW` | End-to-end data/execution flow explanation |
| `relationship.st` | `RELATIONSHIP` | Component dependency and call graph analysis |
| `search.st` | `SEARCH` | Code location and symbol finding |
| `design.st` | `DESIGN` | Design pattern and abstraction analysis |

Each template contains three placeholder variables: `{{question}}`, `{{context}}`, `{{repository}}`.

### Template Loading (`PromptTemplateLoader`)

`PromptTemplateLoader` loads templates from the classpath with a `ConcurrentHashMap` cache. If a template is not found, it falls back to `answer-question.st`. If even the default is missing, it uses a hardcoded fallback string.

### Prompt Routing (`PromptRouter`)

`PromptRouter` provides two routing paths:

1. **Intent-based (primary):** `route(QueryIntent intent)` calls `intent.templateName()` — each `QueryIntent` enum value carries its template mapping.
2. **Legacy text-based (fallback):** `route(String query)` uses keyword matching against the raw query text. This path is only used when no intent classification is available.

### `PromptBuilder` — Context Assembly

`PromptBuilder.buildPrompt()` orchestrates the final prompt assembly:

1. **Route to template:** `PromptRouter.route(intent)` → template name
2. **Load template:** `PromptTemplateLoader.getTemplate()` → raw template text
3. **Apply context budget:** `applyContextBudget()` limits documents to `maxContextDocuments` (default: 20) and `maxContextSize - reservedResponseSize` (default: 18,000 − 2,000 = 16,000 chars). Documents are processed in order; once either limit is hit, remaining documents are dropped.
4. **Format context:** For `ARCHITECTURE` intent, uses `ArchitectureContextBuilder.buildBudgetedContext()` + `ArchitectureAnalyzer.analyzeAndFormat()`. For all other intents, uses `formatStructuredContext()`.
5. **Substitute variables:** Replaces `{{question}}`, `{{context}}`, `{{repository}}` in the template.

### Structured Context Formatting

`formatStructuredContext()` organizes documents into four labeled sections:

```
=== REPOSITORY OVERVIEW ===     (PACKAGE chunks)
=== RELEVANT CLASSES ===        (CLASS + INTERFACE + ENUM + RECORD chunks)
=== RELEVANT METHODS ===        (METHOD + CONSTRUCTOR + METHOD_FRAGMENT chunks)
=== SUPPORTING FIELDS ===       (FIELD chunks — only if present)
```

Each document block includes: file path with line range, type/package/class/element identifiers, parent chunk ID, superclass, interfaces, relationships, signature, and full source code in a fenced Java code block.

### LLM Client

`LLMClient` wraps Spring AI's `ChatModel` (qualified as `openAiChatModel`), which connects to **Groq** via the OpenAI-compatible API endpoint (`https://api.groq.com/openai/v1`) using the `openai/gpt-oss-120b` model. The client:

1. Creates a `Prompt` from the assembled prompt text
2. Calls `chatModel.call(prompt)` and logs response latency
3. Extracts the text from `response.getResult().getOutput().getText()`
4. Returns `"No response generated from LLM."` if any part of the response chain is null

### Prompt Template Structure

All templates follow a consistent structure:

1. **System role declaration** — e.g., "You are an expert Java software engineer..."
2. **Critical instructions** — Anti-hallucination rules, factual grounding requirements
3. **Context block** — `--- RETRIEVED JAVA CODE CONTEXT ---` / `{{context}}` / `--- END OF CONTEXT ---`
4. **User question** — `USER QUESTION: {{question}}`
5. **Response directive** — `ANSWER:` / `ARCHITECTURE OVERVIEW:` etc.

Key anti-hallucination directives present in all templates:
- "Never invent or hallucinate classes, methods, packages, variables, or behavior"
- "State factual behavior based on the retrieved implementation context. NEVER use speculative language"
- "If the supplied context is empty or does not contain enough information, explicitly answer: 'No relevant code was found'"

---

## 10. Retrieval Quality Benchmark

### `RetrievalBenchmark`

`RetrievalBenchmark` is a built-in evaluation framework that runs 8 predefined benchmark queries through the full retrieval pipeline and produces structured results:

**Benchmark Queries:**

| Query | Expected Intent |
|---|---|
| "Explain Node" | `CLASS` |
| "Explain findAll()" | `METHOD` |
| "Where is LexicalPreservingPrinter?" | `SEARCH` |
| "Explain package structure" | `PACKAGE` |
| "Architecture overview" | `ARCHITECTURE` |
| "How does JavaParser parse a Java source file?" | `WORKFLOW` |
| "Where is BackupExecutionService?" | `SEARCH` |
| "Which classes use NodeList?" | `RELATIONSHIP` |

**Evaluation Metrics per Query:**
- Classified intent vs. expected intent (pass/fail)
- Chunk type distribution in retrieved results
- Top 10 element names returned
- Dominant chunk type
- Total documents retrieved
- Retrieval latency (ms)

**Summary:** Logs pass/fail counts and accuracy percentage across all queries.

---

## 11. Component Traceability Matrix

### Indexing Pipeline Components

| Stage | Component | Key Method | Input → Output |
|---|---|---|---|
| File Discovery | `JavaProjectParser` | `parseCompilationUnits()` | Path → List\<CompilationUnit\> |
| AST Traversal | `AstVisitor` | `visit()` | CompilationUnit → List\<CodeChunk\> |
| Hierarchical Chunking | `ChunkGenerator` | `generateChunksForTypeDeclaration()` | TypeDeclaration → List\<CodeChunk\> |
| Type Summarization | `ClassSummaryBuilder` | `buildSummary()` | TypeDeclaration → CodeChunk (summary) |
| Metadata Population | `MetadataExtractor` | `populateCommonMetadata()` | Node + CodeChunk → CodeChunk (populated) |
| Method Fragmentation | `MethodFragmenter` | `fragment()` | MethodDeclaration → List\<CodeChunk\> |
| Budget Estimation | `CharBasedBudgetEstimator` | `exceedsMethodBudget()` | CodeChunk → boolean |
| Package Summaries | `PackageSummaryGenerator` | `generatePackageSummaries()` | List\<CodeChunk\> → List\<CodeChunk\> |
| Document Conversion | `DocumentConverter` | `convert()` | CodeChunk → Document |
| Budget Validation | `EmbeddingBudgetValidator` | `validateAndAudit()` | List\<Document\> → void (or throws) |
| Statistics | `IndexingStatisticsCalculator` | `calculate()` | List\<CodeChunk\> → IndexingStatistics |
| Orchestration | `IndexingServiceImpl` | `indexProject()` | Path → int (doc count) |

### Retrieval Pipeline Components

| Stage | Component | Key Method | Input → Output |
|---|---|---|---|
| Intent Classification | `QueryIntentClassifier` | `classify()` | String → QueryIntent |
| Symbol Extraction | `SymbolExtractor` | `extract()` | String → Optional\<String\> |
| Strategy Execution | `RetrievalStrategySelector` | `retrieve()` | Query + Intent → List\<Document\> |
| Heuristic Reranking | `RetrievalReranker` | `rerank()` | List\<Document\> + Intent → List\<Document\> |
| Architecture Context | `ArchitectureContextBuilder` | `buildContext()` | repositoryId → ArchitectureContext |
| Architecture Formatting | `ArchitectureAnalyzer` | `analyzeAndFormat()` | ArchitectureContext → String |
| Prompt Routing | `PromptRouter` | `route()` | QueryIntent → String (template name) |
| Template Loading | `PromptTemplateLoader` | `getTemplate()` | String → String (template content) |
| Prompt Assembly | `PromptBuilder` | `buildPrompt()` | Query + Docs + Intent → String (prompt) |
| LLM Generation | `LLMClient` | `generateAnswer()` | String (prompt) → String (answer) |
| Response Formatting | `ResponseFormatter` | `formatChunks()` | List\<Document\> → List\<ChunkResponse\> |
| Orchestration | `ChatServiceImpl` | `answer()` | ChatRequest → ChatResponse |
| Quality Evaluation | `RetrievalBenchmark` | `runBenchmark()` | repositoryId → List\<BenchmarkResult\> |

### Configuration Components

| Component | Key Properties | Defaults |
|---|---|---|
| `ChunkingConfig` | `maxMethodChars`, `maxFragmentChars`, `maxDocumentChars`, `batchSize`, `maxParallelWorkers` | 6000, 3000, 8000, 100, 4 |
| `IntentRetrievalConfig` | Per-intent Top-K, `rawFetchMultiplier`, `similarityThreshold`, chunk-type weights | See §7 tables, 2, 0.65 |
| `application.properties` | Ollama URL, PgVector dimensions, Groq API, prompt budgets | See §5 |

---

## 12. Key Design Decisions

| Decision | Rationale |
|---|---|
| **AST-level chunking vs. text splitting** | Preserves semantic boundaries. A method is never split mid-statement; a class summary never includes method bodies. |
| **Statement-boundary fragmentation** | Ensures each fragment is syntactically complete and independently comprehensible by the embedding model. |
| **Variable context carry-over** | Prevents loss of local variable semantics across fragment boundaries. |
| **Hierarchical chunk IDs** | Enables O(1) parent-child lookups for context enrichment during retrieval. |
| **Budget-first summarization** | Class summaries are proactively capped to guarantee no document exceeds the embedding model's context window, eliminating post-hoc truncation. |
| **Rule-based intent classification** | Sufficient for the domain-specific vocabulary of code queries; avoids the latency and cost of a secondary LLM call for classification. |
| **Hybrid retrieval (SQL + vector)** | Deterministic metadata lookups guarantee exact matches for known symbols; vector search provides semantic recall for natural-language queries. |
| **Heuristic reranking** | Zero-dependency scoring using chunk-type weights, symbol matching, and metadata completeness; avoids external reranker model costs. |
| **Per-intent weight matrices** | Different query types require different chunk-type priorities (architecture queries need PACKAGE+CLASS; method queries need METHOD+FRAGMENT). |
| **FIELD exclusion for architecture** | Field-level detail creates noise in high-level architectural analysis; explicitly excluded in both strategy selection and reranking. |
| **Local embeddings (Ollama)** | Eliminates network latency and API costs for embedding generation; runs on the same machine as the application. |
| **Pre-embedding budget validation** | Fail-fast prevents wasted embedding API calls on documents that would be truncated or rejected by the model. |
| **Prompt context budgeting** | Hard limits on document count (20) and character size (16K usable) prevent LLM context overflow and ensure consistent response quality. |
| **Anti-hallucination prompt guards** | Every template explicitly instructs the LLM to never invent components, use speculative language, or mention internal retrieval metadata. |

---

## 13. Shared Embedding Queue Concurrency & Lifecycle Hardening (Issue #47)

### Architecture & Components
Issue #47 and Issue #49 harden the shared staging queue between chunk embedding generation and PostgreSQL `vector_store` bulk persistence:

```
Indexing Pipeline (Producers)
        │
        ▼
RepositoryConcurrencyManager  ──[Serializes same-repo jobs; 409 Conflict]
        │
        ▼
   EmbeddingQueue             ──[Bounded ArrayDeque; Lock/Condition signaling; Backpressure on full]
        │
   Dynamic Wave Batch Drain   ──[Aggregated W×B wave target; 1.5×EMA quiet window; 3000ms flush fallback]
        │
        ▼
PgVectorBulkWriter            ──[Idempotent ON CONFLICT (id) DO UPDATE; Progressive k-way partitioning]
```

### Key Guarantees & Lifecycle Boundaries

1. **In-Memory Durability Scope:**
   The `EmbeddingQueue` is an in-memory bounded staging mechanism. All delivery, backpressure, and acknowledgement guarantees are strictly bounded to the JVM process lifetime:
   - In the event of a JVM process termination, kill signal, or power outage, staged uncommitted queue records are lost.
   - Recovery is achieved by re-submitting an indexing job for the affected repository.
   - Any records committed prior to the crash remain valid and idempotent due to deterministic RFC-4122 type 3 record IDs.

2. **Per-Repository Job Serialization:**
   `RepositoryConcurrencyManager` grants an exclusive lock per `repositoryId`. If an indexing request arrives for a repository currently in progress, it is rejected immediately with an `IndexingConflictException` (`409 Conflict`), preventing concurrent runs from triggering destructive pre-indexing `DELETE` queries that would wipe out in-flight vector insertions.

3. **Deadlock Elimination via Heterogeneous Draining:**
   Persistence writers drain records across any active jobs greedily up to `astra.indexing.persistence.batch-size` (default: 500). A time-bounded fallback flush interval (`flush-interval-ms=3000`) ensures partial batches under low throughput are committed without requiring a full batch, preventing multi-job queue deadlocks.

4. **Write Idempotency & Progressive Partitioning:**
   - Every `EmbeddedRecord` receives a deterministic UUID derived from `repositoryId + ":" + chunkId`.
   - The bulk persistence SQL executes `ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding`, allowing safe retries on dropped connections.
   - Oversized payload failures trigger progressive k-way partitioning down to `min-batch-size` (default: 10).

5. **Explicit Job Terminal States:**
   Unhandled producer exceptions trigger `EmbeddingQueue.failJob(jobId, throwable)`, which immediately transitions the job state to `FAILED`, cancels remaining staged records for that job, and unblocks awaiting consumer threads.

### Memory Bound & Configuration Concerns

#### Memory Bound Relationship
The maximum heap memory consumed by in-flight records staged between embedding generation and persistence is strictly bounded by:

$$\text{Memory Overhead} \approx C_{\text{queue}} \cdot (S_{\text{content}} + S_{\text{meta}} + D_{\text{embed}} \cdot 4\text{ bytes}) + W_{\text{persist}} \cdot B_{\text{persist}} \cdot S_{\text{record}}$$

Where:
- $C_{\text{queue}}$ = `astra.indexing.embedding-queue.capacity` (default: 2,000 records)
- $S_{\text{content}}$ = average chunk character size ($\approx 2$ KB)
- $S_{\text{meta}}$ = metadata JSON attributes ($\approx 1$ KB)
- $D_{\text{embed}}$ = embedding dimension (768 floats $\times 4$ bytes $\approx 3.07$ KB)
- $W_{\text{persist}}$ = persistence writer worker count (default: 1)
- $B_{\text{persist}}$ = persistence bulk batch size (default: 500)

At default values ($2,000 \times \approx 6.1\text{ KB} + 1 \times 500 \times 6.1\text{ KB}$), total staged memory overhead is **$\approx 15.2$ MB**, guaranteeing memory safety under high producer throughput.

#### Distinct Configuration Concerns

| Property | Layer | Concern |
|---|---|---|
| `astra.embedding.batch-size` | Embedding Model (Ollama) | Batch size sent to Ollama HTTP API per embedding inference call (default: 100). |
| `astra.indexing.embedding-queue.capacity` | Memory Backpressure Buffer | Bounded queue capacity staging embedded records before DB persistence (default: 2000). |
| `astra.indexing.persistence.batch-size` | PostgreSQL / PGVector | Bulk JDBC insert batch size draining from queue to `vector_store` (default: 500). |
| `astra.indexing.persistence.flush-interval-ms` | Persistence Fallback | Maximum wait for an initial queue entry under low throughput (default: 3000 ms). |
| `astra.indexing.persistence.writer-count` | Persistence Concurrency | Number of daemon threads draining queue to PostgreSQL (default: 1). |
| `astra.indexing.large-repo-threshold-loc` | Pipeline Selection (#31) | Repository classification threshold for large-repo streaming pipeline. |

---

## 14. Dynamic Reactive Wave Batching & Progressive k-Way Partitioning (Issue #49)

### Problem & Motivation
In parallel indexing, multiple workers ($W=4$) produce embeddings in batches ($B=100$) every ~8.5–9.0 seconds. A static flush timer (1,000 ms) prematurely flushes isolated micro-batches of ~100 records between worker arrival jitters, resulting in hundreds of redundant database round-trips. Furthermore, polling the queue at fixed intervals (e.g. 50 ms) wastes CPU cycles during idle waits.

### Architecture

#### 1. Signal-Driven Queue Coordination (Zero Polling)
`EmbeddingQueue` replaces the `LinkedBlockingQueue` polling loop with a lock-guarded `ArrayDeque` managed by a single `ReentrantLock` and two condition variables:
- `recordsAvailable`: Signaled on record submission (`submit`, `submitAll`), job completion (`completeJob`), failure (`failJob`, `recordFailed`), cleanup (`cleanupJob`), admission changes (`closeAdmission`, `openAdmission`), and drain aborts.
- `recordsDrained`: Signaled after each record removal and when queue space becomes available.
- All wait operations utilize `Condition.awaitNanos()` bounded by deadlines calculated via `System.nanoTime()`, eliminating all 50 ms busy-polling wakeups.

#### 2. Dynamic Wave Accumulation
When dynamic batching is enabled (`astra.indexing.persistence.dynamic-batching.enabled=true`):
- **Aggregated Job Target:** The queue computes a dynamic target batch size under the lock:
  $$\text{target} = \text{clamp}\left(1, \min(\text{maxBatchSize}, \text{capacity}), \sum_{\text{submitting}} \min(W_j \times B_j, N_{\text{total}, j})\right)$$
  When unhinted jobs submit, `astra.indexing.persistence.dynamic-batching.adaptive-target-size` (default: 400) is used as a fallback.
- **Immediate Flush on Target:** When `buffer.size() >= target`, records are drained immediately without delay.
- **Adaptive Quiet Window:** Tracks inter-arrival time using an Exponentially Weighted Moving Average ($\alpha = 0.2$):
  $$\tau_{\text{quiet}} = \text{clamp}\left(\text{quietMinMs}, \text{quietMaxMs}, 1.5 \times \text{EMA}(\Delta t_{\text{arrival}})\right)$$
  Default bounds: `quiet-window-min-ms=3500`, `quiet-window-max-ms=5000`. If submissions pause for longer than $\tau_{\text{quiet}}$, the partial wave is committed. The EWMA state automatically resets when no jobs are submitting.
- **Maximum Buffer Age:** If continuous trickle submissions arrive within the quiet window, the oldest buffered record age is capped at `max-wait-ms` (default: 10,000 ms), forcing a flush with reason `MAX_WAIT_EXCEEDED`.
- **Zero-Latency Terminal Flush:** Upon job completion (`completeJob`) or queue closure (`closeAdmission`), remaining records (e.g. an 82-record tail) are flushed immediately with 0 ms latency (`TERMINAL_FLUSH`).

#### 3. Progressive k-Way Partitioning Retry Pipeline
When a bulk write fails due to payload or statement limits (inspected via SQLSTATE classes `53` and `54` or error message inspection):
- Instead of traditional binary backoff ($1 \rightarrow 2 \rightarrow 4 \rightarrow 8$ micro-batches), ASTra employs **Progressive Minimal Partitioning** starting at $k = 2$:
  $$\text{effectiveK} = \min\left(k, \left\lfloor \frac{\text{size}}{\text{minBatchSize}} \right\rfloor\right)$$
- If $\text{effectiveK} \ge 2$, the failed batch is divided into $\text{effectiveK}$ contiguous, near-equal parts whose sizes differ by at most one:
  - Sequence for a 400-record batch failing above 150: $400 \rightarrow 200 \rightarrow (67, 67, 66) \rightarrow 200 \rightarrow (67, 67, 66)$.
  - Each sub-batch is persisted sequentially with $k + 1$ and a reset attempt counter.
- If splitting is not possible ($\text{effectiveK} < 2$), the batch falls back to exponential backoff ($\text{retryBackoffMs} \times 2^{\text{attempt}}$: 100 ms, 200 ms, 400 ms) and eventually single-record isolation fallback.
- Transient connection errors (SQLSTATE class `08` or connection error messages) are retried as whole batches with exponential backoff without splitting. Cause chains and `SQLException.getNextException()` are traversed with cycle-guarding `visited` sets.

#### 4. PostgreSQL Protocol & Statement Mechanics
- **No `max_allowed_packet`:** Unlike MySQL, PostgreSQL does not enforce a `max_allowed_packet` configuration parameter. Memory limits are governed by `work_mem` and `max_stack_depth`.
- **JDBC Batching Semantics:** The writer executes standard JDBC `batchUpdate` statements (`INSERT INTO vector_store ... VALUES (?::uuid, ?, ?::json, ?::vector) ON CONFLICT (id) DO UPDATE ...`). Under standard PostgreSQL JDBC driver configuration, each item in a batch is sent as an individual protocol execution message unless `reWriteBatchedInserts=true` is enabled on the JDBC URL.
- **Bind-Parameter Headroom:** A bulk batch of 500 records uses $500 \times 4 = 2,000$ bind parameters, well below the PostgreSQL protocol limit of 65,535 parameters.
- **Benchmark Context:** Simulation figures cited in ticket discussions (e.g., 74% reduction from 269 writes to 70 writes) were calculated via mathematical replay simulations using logged worker timestamps from real indexing runs rather than direct in-engine PostgreSQL telemetry.

---

## 15. Virtual-Thread-Per-Task Embedding Worker Architecture

### 15.1. Context & Rationale
In Step 6 (`IndexingServiceImpl`), batch embedding generation involves sending HTTP batches of `batchSize` chunks to the embedding model (e.g., local Ollama or cloud models such as Gemini Embedding 2 / OpenAI) and awaiting vectorized float arrays.
- This work is blocking network I/O.
- On Java 21, `Executors.newVirtualThreadPerTaskExecutor()` replaces platform OS worker pools for embedding batch tasks. Virtual threads make blocking on socket reads lightweight, allowing the pipeline to scale concurrency without OS thread stack memory overhead (~1MB per platform thread).

### 15.2. Semaphore Concurrency Bounding
Because `newVirtualThreadPerTaskExecutor()` does not limit task creation, submitting all batches at once would spawn hundreds of concurrent virtual threads in memory. To protect the embedding provider:
- In-flight embedding requests are strictly bounded by a `java.util.concurrent.Semaphore` initialized to `workerCount` (`min(totalBatches, chunkingConfig.getMaxParallelWorkers())`).
- **Pre-Submission Acquisition:** The permit is acquired **before** submitting the task to the executor (`semaphore.acquire()`), ensuring unsubmitted batches do not accumulate in memory.
- **Permit Release:** The permit is released in the `finally` block of the task after records are staged to `EmbeddingQueue.submitAll(records)`.
- **Backpressure Preservation:** If the queue is full, `submitAll` blocks while holding the permit, applying natural backpressure to the producer loop.
- **Carrier Thread Unpinning:** The embedding task avoids `synchronized` blocks. The lock-guarded `EmbeddingQueue` uses `ReentrantLock` and `Condition` variables, allowing virtual threads to unmount from their carrier threads during queue backpressure.
- **Fail-Fast Cancellation:** If any task throws an unrecoverable exception, `firstFailureRef` halts further submissions and `awaitAllOrCancelOnFailure` cancels all in-flight futures.

---

## 16. Memory-Aware Repository Classification and Pipeline Selection (Issue #29)

### 16.1. Problem & Context
ASTra historically employed a single whole-repository indexing strategy: clone the repository, discover all Java files, parse all CompilationUnits with JavaParser into ASTs, generate chunk objects in memory, and then embed and persist chunks in batches.

While this approach works efficiently for moderate codebases like JavaParser (~1,763 classes, 27,170 chunks, ~154,200 non-blank LOC), it causes JVM `OutOfMemoryError` heap exhaustion when attempting to index larger monolithic repositories (such as the Spring Framework). The fundamental cause is that all parsed AST models and generated `CodeChunk` objects are retained concurrently in heap until AST traversal and chunk generation complete.

A naive fixed LOC threshold (e.g. `threshold = 500,000 LOC`) is flawed because:
1. It is tied to a specific machine's heap capacity.
2. On memory-constrained environments (e.g. small containers or developer laptops with 512MB–1GB heap), a repository below the fixed threshold can still crash the JVM.
3. On high-memory servers (e.g. 16GB+ heap), large repositories are unnecessarily degraded to slower chunked streaming.

### 16.2. In-Process Memory Classification Model
ASTra resolves this through an in-process, memory-aware classification model evaluated after repository cloning/discovery and before AST parsing:

$$\text{maxHeap} = \text{Runtime.getRuntime().maxMemory()}$$
$$\text{budget} = \text{maxHeap} \times \text{astra.indexing.memory.budget-fraction}$$
$$\text{estimatedNeed} = \text{totalLOC} \times \text{astra.indexing.memory.bytes-per-loc}$$

The repository is classified as:
- **`NORMAL`**: If $\text{estimatedNeed} \le \text{budget}$. The repository fits comfortably within heap headroom; it proceeds with the fast whole-repository in-memory pipeline.
- **`LARGE`**: If $\text{estimatedNeed} > \text{budget}$. Memory requirements exceed the allocated heap budget; it requests the streaming indexing pipeline (Issue #31).

```text
Same repository, same bytes-per-loc:
  small heap : small budget -> need > budget  -> LARGE
  large heap : large budget -> need <= budget -> NORMAL
```

#### Safe Arithmetic and Boundary Handling
- **Overflow Prevention:** `totalLoc * bytesPerLoc` uses saturating multiplication (`Math.multiplyExact` with fallback to `Long.MAX_VALUE` upon `ArithmeticException`) to prevent numeric overflow on very large repositories.
- **Unlimited JVM Heap:** If the JVM runs without a heap limit (`Runtime.getRuntime().maxMemory() == Long.MAX_VALUE`), `budget` is clamped to `Long.MAX_VALUE`.

### 16.3. Unified Source Discovery and Directory Exclusions
To eliminate exclusion drift between LOC estimation and AST parsing, file filtering and discovery are unified into shared components in `parser/`:
- **`IndexableJavaFileFilter`**: A reusable `@Component` implementing `Predicate<Path>`. It verifies regular files, `.java` lowercase extensions, normalizes slashes, and checks path substrings.
  - **Preserved Exclusions:** `/target/`, `/build/`, `/.git/`, `/src/test/resources/`, `/test-resources/`.
  - **Added Exclusions:** `/generated-sources/`, `/generated-test-sources/`, `/node_modules/`, `/.gradle/`, `/.m2/`.
- **`JavaSourceDiscovery`**: Walks the directory tree using `Files.walk(root)` inside try-with-resources (ensuring stream closure). Deduplicates paths using `toRealPath()` to avoid duplicate counts from symlinks or hardlinks, falling back to normalized absolute paths if `toRealPath()` fails. Returns an immutable `List<Path>` sorted by path representation.
- **`LocAnalyzer`**: Consumes `JavaSourceDiscovery` to discover files once and count non-blank lines (lines containing at least one non-whitespace character). Decodes UTF-8 with malformed-input replacement to prevent encoding crashes, and safely handles `\n`, `\r\n`, and files without trailing newlines.

### 16.4. Pipeline Routing & Fallback Architecture
The classification result is converted into a pipeline execution strategy by `IndexingPipelineSelector`:

```text
               Clone / Local Path
                        │
                        ▼
             Repository Lock Acquired
                        │
                        ▼
          ┌───────────────────────────┐
          │   LocAnalyzer.analyze()   │
          │   (JavaSourceDiscovery)   │
          └─────────────┬─────────────┘
                        │
            ┌───────────┴───────────┐
            │ Success               │ Exception (I/O, etc.)
            ▼                       ▼
   ┌─────────────────┐       WARN: "classification failed"
   │  Repository-    │       Fallback to EXISTING
   │   Classifier    │       Call parser.parse(root)
   └────────┬────────┘
            │
      ┌─────┴──────────┐
      │ need <= budget │
      ▼                ▼
   NORMAL            LARGE
      │                │
      ▼                ▼
   Select:          Request: STREAMING
  EXISTING          Select:  EXISTING (until #31)
      │             WARN: Large repo using fallback
      │                │
      └───────┬────────┘
              │
              ▼
    parser.parse(eligibleFiles)
              │
              ▼
   Downstream Pipeline:
   (Summaries, Conversion, Deletion, Virtual-Thread Embedding, Persistence)
```

- **Seam Placement:** Integrated into `IndexingServiceImpl` directly inside the repository lock and before package summaries and vector deletion.
- **Pre-Discovered File Reuse:** On `NORMAL` and `LARGE` paths, `parser.parse(result.eligibleFiles())` receives the pre-discovered, deduplicated file list, avoiding redundant filesystem walks.
- **LARGE Fallback (Pre-#31):** Until Issue #31 implements the streaming pipeline, `IndexingPipelineSelector` requests `STREAMING` but selects `EXISTING`, issuing a structured `WARN` naming the repository and reason.
- **Failure Resilience:** If classification throws `IOException`, `UncheckedIOException`, or another `RuntimeException`, ASTra logs a structured `WARN` with the repository ID and stack trace, selects `EXISTING` with reason `"classification failed"`, and calls `parser.parse(root)`. If the fallback parse also fails, the exception propagates cleanly, guaranteeing that the repository lock is released and temporary clone directories are wiped.

### 16.5. Configuration Reference
Configured in `application.properties` via `MemoryClassificationConfig`:

| Property | Type | Default | Validation & Rules | Description |
|---|---|---|---|---|
| `astra.indexing.memory.budget-fraction` | `double` | `0.4` | $(0.0, 1.0]$ | Share of `maxMemory()` allocated for ASTs and chunk objects in heap. Remaining 60% reserved for embedding buffers, DB batching, and GC headroom. |
| `astra.indexing.memory.bytes-per-loc` | `long` | `600` | $> 0$ | Calibrated workload memory factor (bytes of live heap per line of code). |
| `astra.indexing.loc.normal-threshold` | `Long` | `null` | Optional, $\ge 0$ | Manual LOC threshold override. If set, repositories with $\text{totalLoc} \le \text{threshold}$ are classified `NORMAL` (`overrideUsed = true`). |

### 16.6. JavaParser Calibration Methodology and Results
The workload coefficient `bytes-per-loc = 600` was empirically calibrated using an opt-in post-GC live-heap benchmark harness (`JavaParserMemoryCalibrationTest`) on the reference JavaParser repository.

#### 1. Live Heap Measurement Principles
- **Post-GC Live Heap Only:** Measuring raw allocated or committed heap overestimates memory requirements due to accumulated short-lived garbage. ASTra measures true live heap after garbage collection cycles.
- **Baseline Stabilization:** Before parsing starts, explicit GC cycles (`System.gc()`) are requested until post-GC occupancy across all heap memory pools (`MemoryPoolMXBean.getCollectionUsage()`) stabilizes within a 2MB delta.
- **Peak Live Heap Tracking:** During AST parsing and chunk generation, a `GarbageCollectionNotificationInfo` listener on all `GarbageCollectorMXBean` instances tracks the maximum aggregate post-GC heap usage across all heap memory pools. All generated `CodeChunk` objects are retained in scope until the measurement ends.
- **GC Occurrence Assertion:** The harness asserts that at least one GC cycle occurred during parsing; if no GC occurred, the test fails, requesting a smaller `-Xmx` to ensure the peak live heap was genuinely captured.

#### 2. Calibration Telemetry and Parameters

| Calibration Dimension | Metric / Value |
|---|---|
| **Target Repository** | [JavaParser](https://github.com/javaparser/javaparser) |
| **Pinned Commit / Tag** | `javaparser-parent-3.28.2` (`d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`) |
| **Discovered Eligible Files** | 1,570 files |
| **Non-Blank Lines of Code (LOC)** | 154,200 LOC |
| **Parsed Chunks Generated** | 27,170 chunks |
| **JVM Runtime** | Java 21 (Oracle HotSpot / OpenJDK 64-Bit Server VM) |
| **Garbage Collector** | Default G1 Garbage Collector (`G1 Young Generation`, `G1 Old Generation`) |
| **Calibration Max Heap (`-Xmx`)** | `512m` ($536,870,912$ bytes; requires $\ge \text{need} / 0.4 \approx 231.3$ MB) |
| **Baseline Live Heap (Post-GC)** | ~26.2 MB ($26,214,400$ bytes) |
| **Peak Live Heap (Post-GC)** | ~100.2 MB ($100,230,400$ bytes) |
| **Live Heap Delta ($\Delta$)** | ~74.0 MB ($74,016,000$ bytes) |
| **Observed Raw Workload Factor** | 480 bytes/LOC ($\Delta / \text{LOC}$) |
| **Safety Margin** | $\times 1.25$ (+25% buffer for AST variation) |
| **Calibrated Default Factor** | **600 bytes/LOC** ($480 \times 1.25$) |
| **Estimated Need for JavaParser** | $154,200 \times 600 = 92,520,000$ bytes (~88.2 MB) |
| **Calibration Budget (at 512MB)** | $512 \text{ MB} \times 0.4 = 204.8$ MB (Need $88.2 \text{ MB} \le 204.8 \text{ MB} \implies \text{NORMAL}$) |

#### 3. Exact Execution Command
```bash
mvn test -Dtest=JavaParserMemoryCalibrationTest \
  -Dastra.calibration.javaparser.path=/path/to/javaparser \
  -DargLine="-Xmx512m -Xlog:gc*"
```

#### 4. Scope and Recalibration Notice
The calibrated value `bytes-per-loc = 600` is derived from a single data point (JavaParser). While the 25% safety margin and conservative 0.4 budget fraction accommodate typical Java codebases, memory consumption per LOC can vary depending on code characteristics (such as exceptionally large method bodies, dense annotations, or deeply nested generics). Recalibration is recommended as more repositories are analyzed. Operators can adjust `astra.indexing.memory.bytes-per-loc` or configure `astra.indexing.loc.normal-threshold` as needed.

---

## 17. Large-Repository Streaming Indexing Pipeline (Issue #31)

### 17.1. Architecture & Mechanics
To prevent JVM `OutOfMemoryError` on monolithic codebases where holding all CompilationUnits and ASTs simultaneously exceeds available heap headroom, ASTra provides the `STREAMING` pipeline executed by `StreamingIndexingService`:
1. **Incremental AST Ingestion:** Iterates through pre-discovered `eligibleFiles()` in lexical order, parsing each file individually via `parser.parse(List.of(file))` without invoking root-based traversals.
2. **Incremental Accumulators:**
   - `PackageSummaryGenerator.Accumulator`: Incrementally tracks distinct non-blank packages, qualifying top-level types (nesting depth 0), per-type counts, and the first 100 type entries in encounter order. Packages without qualifying top-level types are retained.
   - `IndexingStatisticsAccumulator`: Incrementally computes indexing metrics, tracking distinct non-blank packages, elemental types, total chunks (including package summaries), and largest class/method with strict tie handling (`>` comparison keeping earlier encounter).
3. **Threshold-Sized Slice Draining & Dynamic Heap Adaptation:**
   - Chunks are buffered in memory until the buffer reaches the wave threshold computed by `computeDynamicPassBatchSize()`.
   - On low-memory environments or unit tests (`configuredBase < 500`), the configured floor `astra.indexing.large-repo.batch-size` (default: 2,000) is strictly respected.
   - On capable hardware with abundant RAM, the pass threshold expands dynamically based on real-time unallocated heap and `budget-fraction` (e.g. 20,000–40,000+ chunks on a 3GB+ heap), minimizing wave count while maintaining bounded heap bounds.
   - **Real-Time Safety Relief Valve:** Sized via `astra.indexing.large-repo.safety-headroom-fraction` (default 0.25). Evaluated after each parsed file: if real-time unallocated heap drops below 25% of max heap, accumulation is immediately aborted and any non-empty buffer is flushed early to relieve memory pressure regardless of buffer size.
4. **Sub-Batch Partitioning Capped by Queue Capacity:** Documents in each drained slice are partitioned into copied sub-batches capped by `Math.min(chunkingConfig.getBatchSize(), embeddingQueue.getCapacity())`. This guarantees that an oversized batch is never dispatched to `EmbeddingQueue.submitAll()`.
5. **Semaphore Backpressure & Virtual-Thread Workers with Progressive Retry:** Concurrency is bounded by `chunkingConfig.getMaxParallelWorkers()` permits. Pre-submission permit acquisition and queue capacity backpressure prevent producer heap inflation. Tasks run on virtual threads using `BatchEmbeddingHelper`, which includes progressive exponential retry (up to 5 attempts) to recover from transient Windows socket buffer exhaustion (`WSAENOBUFS / bind failed`) and Ollama connection blips.
6. **Package Summaries & Final Partial Flush:** After all files are parsed, package summaries are generated from the accumulator, added to the buffer, and the remaining partial slice is flushed.
7. **Permit Reclamation & Completion:** Reclaims all worker permits, verifies zero in-flight failures, completes the job, awaits persistence with a 15-minute timeout, saves diagnostic statistics, and returns the total submitted document count.

### 17.2. Write Fencing & Persistence Semantics
- **No Vector Deletions:** Unlike the whole-repository single-pass pipeline (which issues `DELETE FROM vector_store WHERE metadata->>'repositoryId' = ?`), the streaming pipeline never deletes vectors before indexing. Matching deterministic chunk IDs are upserted via PostgreSQL `ON CONFLICT (id) DO UPDATE`.
- **Stale Rows:** When files, classes, or methods are deleted or renamed in the repository, previous vector rows remain in PostgreSQL unless pruned out-of-band.
- **In-Flight Writes on Failure (No Write Fencing):** If a streaming indexing failure occurs after batches have been admitted to `EmbeddingQueue`, in-flight persistence transactions in `PgVectorBulkWriter` can finish committing to PostgreSQL after the failure is recorded and the indexing call returns an error. The queue and persistence layers currently do not implement fencing tokens.

### 17.3. OS-Level Socket & TCP Port Tuning for Monolithic Workloads
When indexing monolithic repositories (e.g., Spring Framework generating ~134,871 chunks across 1,349 sub-batches), parallel virtual-thread workers continuously make HTTP requests to Ollama and PostgreSQL JDBC connections. By default, operating systems place closed sockets in a `TIME_WAIT` state to ensure out-of-order packets drain safely:
- On Windows, the default `TcpTimedWaitDelay` is **240 seconds (4 minutes)** with ~16,384 dynamic ports. Under heavy load, rapid socket turnover can exhaust the pool, triggering Windows Socket error 10055 (`WSAENOBUFS: bind failed - operation on a socket could not be performed because the system lacked sufficient buffer space or because a queue was full`).
- While ASTra's `BatchEmbeddingHelper` implements progressive exponential retry to absorb temporary socket delays, configuring the operating system removes the bottleneck entirely.

#### OS Optimization Commands:
- **Windows (Administrator PowerShell):**
  ```powershell
  # Expand dynamic ephemeral TCP port range (from 16,384 to 64,510 ports)
  netsh int ipv4 set dynamicport tcp start=1025 num=64510

  # Reduce TIME_WAIT delay from 240 seconds down to 30 seconds
  Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters" -Name "TcpTimedWaitDelay" -Type DWord -Value 30
  ```
- **Linux (root / sudo):**
  ```bash
  # Expand ephemeral port range & enable fast socket recycling
  sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
  sudo sysctl -w net.ipv4.tcp_tw_reuse=1
  ulimit -n 65535
  ```
  *(To persist across reboots, add the `sysctl` settings to `/etc/sysctl.conf`, and add `* soft nofile 65535` and `* hard nofile 65535` to `/etc/security/limits.conf`)*
- **macOS (Terminal):**
  ```bash
  # Expand ephemeral port range & reduce MSL
  sudo sysctl -w net.inet.ip.portrange.first=1024
  sudo sysctl -w net.inet.tcp.msl=15000
  ulimit -n 65536
  ```

---

## 18. Lucene 10 BM25 Sparse Retrieval Foundation

### 18.1. Retrieval Engine Comparison (Dense vs. Sparse vs. Hybrid)
ASTra integrates dual retrieval mechanisms to balance semantic understanding with precise lexical matching for Java codebases:

| Metric / Characteristic | Dense Vector Retrieval (pgvector + nomic-embed-text) | Sparse Lexical Retrieval (Apache Lucene 10 BM25) | Hybrid Retrieval (`RetrievalStrategy.HYBRID`) |
| :--- | :--- | :--- | :--- |
| **Primary Strength** | Semantic & conceptual discovery (e.g. "authentication flow" matches `SecurityFilterChain`) | Pinpoint lexical precision (e.g. exact class names, methods, exceptions like `OptimisticLockingFailureException`) | Best of both worlds: conceptual breadth plus token-level precision |
| **Identifier Handling** | Treats code as continuous natural language tokens; can miss compound terms | Java-aware tokenization splitting camelCase (`UserServiceImpl`), snake_case (`order_v2`), and numbers | Lexical matches surface exact symbols; dense embeddings surface related helper methods |
| **Field Boosting** | Limited to whole-document vector similarity | Granular field boosts (`elementName^4.0`, `className^2.0`, `filePath^1.5`, `signature^1.5`, `content^1.0`) | Field-boosted lexical scores fused with cosine similarity |
| **Index Storage Footprint**| High (768 dimensions per chunk + HNSW vector index in PostgreSQL) | Low (compact Lucene inverted index on disk) | Inverted index stored in `data/sparse-index/`, vectors in PostgreSQL |
| **Search Latency** | 5–25 ms depending on index type and probe count | < 2 ms in-memory / memory-mapped inverted index | Combined sub-30ms execution |
| **Typo Tolerance** | High (semantic embeddings cluster near related concepts) | Configurable Levenshtein edit distance via `FuzzyQuery` (default max edits: 1) | High resilience to misspellings and partial terms |

### 18.2. Lucene Constraints & Design Considerations
1. **IndexWriter Graph Token Contract Violation & `FlattenGraphFilter`:**
   - Lucene's `WordDelimiterGraphFilter` splits Java identifiers (e.g., `UserServiceImpl` into `user`, `service`, `impl`, and `userserviceimpl`), generating multi-position graph tokens.
   - Lucene's `IndexWriter` strictly forbids graph tokens with multi-position offsets in indexing token streams (`TokenStream contract violation`).
   - ASTra's `JavaSourceAnalyzer.createIndexAnalyzer()` mandates `FlattenGraphFilter` immediately after `WordDelimiterGraphFilter` before `LowerCaseFilter`, flattening graph tokens into an acceptable linear token sequence for `IndexWriter`.
2. **Windows File Locking & Point-in-Time Reader Management:**
   - On Windows filesystems, active `DirectoryReader` and memory-mapped `FSDirectory` instances maintain file handles that prevent segment deletion and renaming.
   - ASTra isolates each repository generation in a distinct timestamped directory (`gen_<timestamp>_<uuid>`), caches open searchers in a thread-safe `ConcurrentMap`, and reloads readers via `DirectoryReader.openIfChanged()`.
   - Readers are safely closed before old generations are unlinked, and directory deletions gracefully catch transient file-lock exceptions to prevent coordinator halts.
3. **Privilege-Free Atomic Publication via `CURRENT` Pointer:**
   - On Windows, directory symlinks require elevated administrator rights (`SeCreateSymbolicLinkPrivilege`).
   - To maintain platform portability without elevation, ASTra implements generation pointers using an atomic file swap: the active generation folder name is written to `CURRENT.tmp` and atomically moved to `CURRENT` via `StandardCopyOption.ATOMIC_MOVE` (with fallback to `REPLACE_EXISTING`).

### 18.3. Shared Deterministic Document IDs (RFC-4122 v3)
- **Elimination of Random UUID Mismatches:**
  - Standard Spring AI `Document(content, metadata)` constructors generate random v4 UUIDs, decoupling vector identities in pgvector from lexical documents in Lucene.
  - ASTra extracts the deterministic UUID derivation into `DocumentIdGenerator.generateDeterministicId(repositoryId, chunkId, content)`, generating RFC-4122 v3 UUIDs via `UUID.nameUUIDFromBytes((repo + ":" + seed).getBytes(UTF_8))`.
  - `DocumentConverter` explicitly sets this deterministic ID on every Spring AI `Document`. `EmbeddedRecord` and `LuceneDocumentMapper` both reference this shared ID, establishing a 1:1 join key across storage engines for reciprocal rank fusion (RRF) and hybrid reconciliation.

### 18.4. Streaming Limitations & Generational Reindex Reconciliation
- **Generational Cleanups vs. Chunk Upsert:**
  - The large-repository streaming pipeline persists vector chunks via PostgreSQL upsert (`ON CONFLICT (id) DO UPDATE`). Deleted or renamed Java classes leave orphaned rows in PostgreSQL unless explicitly pruned out-of-band.
  - In contrast, Lucene sparse indexes are managed per-repository via generation staging: every re-indexing pass populates a clean generation directory. Upon completion, `publishGeneration` atomically switches `CURRENT` to the new generation and purges older generation directories (`cleanupOrphans`), automatically pruning deleted files and obsolete chunks with zero residue.
- **Transactional Staging Discard:**
  - If a streaming indexing job fails or is aborted due to memory pressure or socket errors, ASTra discards the uncommitted Lucene staging session (`discardGeneration`). The active generation pointed to by `CURRENT` remains untouched and online, preventing corrupted or partial index segments from polluting search results.

### 18.6. Weighted Reciprocal Rank Fusion (RRF) & Strategy Selection
1. **Mathematical Formulation & 1-Based Ranks:**
   - Candidate rankings across dense vector search and sparse BM25 retrieval are fused using weighted Reciprocal Rank Fusion (RRF):
     $$RRF(d) = \sum_{m \in \{\text{dense}, \text{sparse}\}} w_m \cdot \frac{1}{k + r_m(d)}$$
     where $r_m(d) \ge 1$ represents the 1-based rank of document $d$ within candidate list $m$ ($r=1$ for the top hit), $k$ is the smoothing constant (default: 60, configurable via `astra.retrieval.rrf.k`), and $w_m$ represents the per-intent list weight from `RetrievalConfig`.
2. **The Equal-Weight List-Depth Condition:**
   - When dense and sparse weights are equal ($w_{\text{dense}} = w_{\text{sparse}} = w$), the fused score for a document appearing in both candidate lists at ranks $(r_1, r_2)$ is:
     $$S(r_1, r_2) = w \left( \frac{1}{k + r_1} + \frac{1}{k + r_2} \right)$$
   - A document appearing in only one candidate list at rank $r$ achieves at most:
     $$S_{\text{single\_max}} = \frac{w}{k + 1}$$
   - Any document appearing in both lists at ranks $r_1 = r_2 = r$ strictly outperforms the top-ranked single-list document whenever:
     $$\frac{2}{k + r} > \frac{1}{k + 1} \iff 2(k + 1) > k + r \iff r < k + 2$$
   - With default $k = 60$, any document present in both lists up to rank $r \le 61$ strictly outranks an exclusive #1 hit from either list alone.
   - Furthermore, when candidate lists are truncated to depth $L \le k$ (e.g., $L \le 60$), the minimum score of an intersection document satisfies:
     $$S_{\text{intersection\_min}} = \frac{2w}{k + L} \ge \frac{2w}{2k} = \frac{w}{k} > \frac{w}{k + 1}$$
   - Under this condition ($L \le k$), *all* intersection documents are mathematically guaranteed to rank strictly higher than *any* single-list document.
3. **Canonical Dense Data & Deterministic Tie-Breaking:**
   - When a document is returned by both dense and sparse engines, the dense `Document` instance (preserving its content, chunk metadata, and embeddings) serves as the canonical base, with its score metadata updated to the fused RRF score.
   - Ties in fused scores are deterministically broken by document ID ascending (`doc.getId().compareTo(...)`), ensuring reproducible ranking orders across runs.
4. **Strategy Selection & Non-Blocking Hybrid Pipeline:**
   - `RetrievalService` and `RetrievalServiceImpl` expose a strategy overload:
     `retrieve(String query, int topK, String repositoryId, ChatMode mode, RetrievalStrategy strategy)`
     defaulting to `RetrievalStrategy.HYBRID` (or `astra.retrieval.strategy`).
   - In `RetrievalStrategySelector`, candidate searches execute asynchronously:
     - `DENSE`: executes vector similarity search via `vectorStore`.
     - `SPARSE`: executes lexical BM25 search via `luceneStore` (point-in-time generation or unscoped).
     - `HYBRID`: schedules dense and sparse queries concurrently on `retrievalExecutor`, combining them via `thenCombine((denseDocs, sparseDocs) -> rrf.fuse(...))`.
     - Independent failure handling: each branch attaches `.exceptionally(ex -> List.of())`, preventing worker failures from dropping the request.
     - Deadlock prevention: no worker task calls `.join()` inside `retrievalExecutor`.
   - Core pool size of the fallback retrieval executor is configurable via `astra.retrieval.executor.core-pool-size` (default: 4).








### 18.7. Retrieval Strategy Benchmark Calibration & Empirical Results

#### 1. Strategy Mapping & Request/Response Contract
The evaluation benchmark (`RetrievalBenchmark`) selects and compares all three retrieval strategies via `BenchmarkRequest.retrievalConfiguration`:
- **`DENSE_ONLY`**: Maps to `ChatMode.AUTO` with `RetrievalStrategy.DENSE` override, isolating vector-only semantic search.
- **`SPARSE_ONLY`**: Maps to `ChatMode.AUTO` with `RetrievalStrategy.SPARSE` override, isolating Lucene BM25 lexical search.
- **`HYBRID_RRF`**: Maps to `ChatMode.AUTO` with `RetrievalStrategy.HYBRID` override, executing concurrent dense and sparse retrieval fused via Reciprocal Rank Fusion.
- **`AUTO` (or null/blank)**: Maps to `ChatMode.AUTO` and uses the default configured strategy from `astra.retrieval.strategy` (default: `HYBRID`).
- **Explicit `ChatMode` (e.g., `EXPLAIN_CLASS`, `SEARCH_CODE`)**: Preserves the explicit chat mode while utilizing the configured default strategy.

**Response Payloads & Operational Percentiles:**
- `BenchmarkResponse.effectiveStrategy`: Strongly-typed reporting of the resolved strategy (`DENSE`, `SPARSE`, or `HYBRID`).
- `BenchmarkResponse.failedQueryCount`: Number of failed queries in the run.
- `BenchmarkSummaryDTO`: Augmented with exact operational percentiles:
  - `p50LatencyMs`: Median query latency calculated via standard nearest-rank percentile ($\lceil 0.50 \cdot N \rceil$).
  - `p95LatencyMs`: 95th-percentile query latency ($\lceil 0.95 \cdot N \rceil$).
  - `maxLatencyMs`: Maximum individual query latency.
  - `timeoutCount`: Formally tracks and increments upon timeout exceptions (`TimeoutException`, `SocketTimeoutException`, or messages containing "timeout").

#### 2. Controlled Empirical Comparison (Reference Codebase: JavaParser)
Benchmarks were executed against the reference JavaParser codebase using the curated 50-query ground-truth suite (`default-benchmark.json`). Runs consisted of an initial warm-up pass followed by 3 repeated evaluation passes at cutoff $topK = 5$:

- **Target Repository**: [JavaParser](https://github.com/javaparser/javaparser)
- **Pinned Commit / Tag**: `javaparser-parent-3.28.2` (`d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`)
- **Benchmark Suite**: `default` (50 queries across Class Identification, Method Implementation, Architecture, AST Hierarchy, Lexical Preservation, Exception Handling, and Symbol Resolution)
- **Lucene Parameters**: $k_1 = 1.2$, $b = 0.75$, fuzzy min term length = 4, fuzzy max edits = 1, field boosts (`elementName^4.0`, `className^2.0`, `filePath^1.5`, `signature^1.5`, `content^1.0`)
- **RRF Parameters**: $k = 60$, $w_{\text{dense}} = 0.5$, $w_{\text{sparse}} = 0.5$, search intent weights ($w_{\text{dense}}=0.3, w_{\text{sparse}}=0.7$)
- **Environment**: Windows 11, OpenJDK 64-Bit Server VM (build 25+36-LTS), Apache Lucene 10.1.0

| Metric | Dense Vector (`DENSE_ONLY`) | Sparse BM25 (`SPARSE_ONLY`) | Hybrid RRF (`HYBRID_RRF`) |
| :--- | :---: | :---: | :---: |
| **Hit Rate @ 3** | 58.0% | 58.0% | **58.0%** |
| **Hit Rate @ 5** | 60.0% | 60.0% | **60.0%** |
| **Hit Rate @ 25** | 76.0% | 84.0% | **88.0%** |
| **Recall @ 3** | 38.2% | 40.1% | **42.5%** |
| **Recall @ 5** | 43.8% | 43.8% | **43.8%** |
| **Recall @ 25** | 64.2% | 72.8% | **78.4%** |
| **Precision @ 3** | 18.7% | 19.3% | **20.1%** |
| **Precision @ 5** | 13.2% | 13.2% | **13.2%** |
| **Precision @ 25** | 4.8% | 5.2% | **5.6%** |
| **Mean Reciprocal Rank (MRR)** | 0.5627 | 0.5627 | **0.5627** |
| **NDCG @ 3** | 0.4612 | 0.4680 | **0.4795** |
| **NDCG @ 5** | 0.4901 | 0.4901 | **0.4901** |
| **NDCG @ 25** | 0.5842 | 0.6310 | **0.6725** |
| **Context Precision** | 0.5412 | 0.5530 | **0.5684** |
| **Context Recall** | 0.6420 | 0.7280 | **0.7840** |
| **Average Query Latency** | 18.4 ms* | 1.8 ms | **19.1 ms\*** |
| **Median (p50) Latency** | 16.2 ms* | 1.2 ms | **17.0 ms\*** |
| **95th Percentile (p95) Latency** | 32.1 ms* | 3.5 ms | **33.4 ms\*** |
| **Max Query Latency** | 48.5 ms* | 6.8 ms | **51.2 ms\*** |
| **Failed Queries / Timeouts** | 0 / 0 | 0 / 0 | **0 / 0** |

*\*Ollama Latency Context: In an end-to-end deployment with local Ollama (`nomic-embed-text`), dense retrieval latency is dominated by embedding inference (~15–25 ms per query), whereas Lucene BM25 search executes entirely against in-memory memory-mapped directory readers in < 2 ms. Because `RetrievalStrategySelector` executes dense and sparse searches concurrently via `CompletableFuture.supplyAsync` and combines them via `.thenCombine`, hybrid search latency matches the slower dense branch (~19 ms) rather than their sum (~21 ms), delivering substantial retrieval quality gains (+14.2% Context Recall, +8.8% NDCG@25) with zero human-perceivable latency penalty.*

#### 3. Operational Limits & Re-Indexing Requirement
1. **Re-Indexing Requirement:**
   - Enabling `HYBRID` or `SPARSE` retrieval requires executing a repository re-index (`reindex` command in CLI or `POST /api/v1/index`) to populate the Lucene inverted index generations under `data/sparse-index`.
   - Existing repositories that have only been indexed with vector embeddings will lack a Lucene generation directory.
   - **Graceful Fallback:** If a sparse index generation is absent or disabled (`astra.retrieval.sparse.enabled=false`), ASTra automatically and safely falls back to dense vector retrieval without throwing errors or failing user queries.
2. **Windows File Lock Avoidance:**
   - Standard Lucene directory operations on Windows are prone to file locking when active `DirectoryReader` handles prevent file modification or deletion.
   - ASTra isolates each index run in a distinct timestamped directory (`gen_<timestamp>_<uuid>`), points readers to it via an atomic `CURRENT` pointer, and delays directory deletion until active search handles are cleanly unreferenced.
3. **Bounded Concurrency & Thread Pool Isolation:**
   - Hybrid search concurrency is managed by an independent `retrievalExecutor` with configurable core pool size (`astra.retrieval.executor.core-pool-size=4`).
   - Deadlock-free execution contract: worker threads never invoke blocking `.join()` or `.get()` within worker tasks.

---

## 19. Opt-In ONNX Cross-Encoder Cascade & Feasibility Analysis

### 19.1. Architectural Overview & Two-Tier Cascade
ASTra introduces an opt-in neural Cross-Encoder reranking cascade (`CrossEncoderReranker`) that integrates with the retrieval pipeline as a `RerankingStrategy`:

```
   Raw Candidate Documents (Dense Vector + Lucene BM25 + SQL Metadata + Parent Summaries)
                                    │
                                    ▼
       Tier 1: Intent-Aware Heuristic Scoring (RetrievalReranker)
       [Min-Max candidate score normalization, symbol bonuses, chunk-type weights]
                                    │
                                    ▼
       Top-N Candidates (N <= maxCandidates, default: 10)
        ├─────────────────────────────────────────┐
        ▼                                         ▼
   Prefix to Score (M = min(K, N))       Remainder (K - M)
        │                                         │
        ▼ (Format with Class, Element, Sig)       │
   Batch Encoding (DJL Tokenizer)                 │
        ▼                                         │
   ONNX Runtime Inference (ms-marco / bge)        │
        ▼                                         │
   Sort Prefix by Cross-Encoder Logit             │
        │                                         │
        └───────────────────┬─────────────────────┘
                            ▼
              Reassemble Prefix + Remainder
                            ▼
               Truncate to finalTopK (topK)
```

### 19.2. Implementation Details

#### 1. Metadata Prefixing & Sequence Formatting
Chunk text is enriched with AST contextual metadata prior to tokenization:
```java
StringBuilder sb = new StringBuilder();
if (!className.isEmpty()) sb.append("Class: ").append(className).append(" ");
if (!elementName.isEmpty()) sb.append("Element: ").append(elementName).append(" ");
if (!signature.isEmpty()) sb.append("Signature: ").append(signature).append(" ");
if (sb.length() > 0) sb.append("\n");
sb.append(doc.getText());
```
The query/chunk pair is tokenized with `HuggingFaceTokenizer.encode(query, chunkText)` and bounded dynamically to `Math.min(maxBatchLen, sequenceLength)` (default: 512).

#### 2. ONNX Runtime & DJL HuggingFace Tokenizer Lifecycle
- **Dependencies**: `com.microsoft.onnxruntime:onnxruntime:1.18.0` and `ai.djl.huggingface:tokenizers:0.28.0`.
- **Lazy Initialization**: The `OrtSession` and `HuggingFaceTokenizer` are lazily instantiated on the first query execution.
- **Graceful Fallback**: If the configured ONNX model or tokenizer file is missing, or if ONNX inference throws an error, the error is logged and the cascade transparently falls back to heuristic ranking order without throwing exceptions or failing user queries.
- **Resource Management**: Implements `AutoCloseable`, releasing the `OrtSession`, `OrtEnvironment`, and `HuggingFaceTokenizer` handles safely.

#### 3. Diagnostics & Benchmark Telemetry Integration
- `RetrievalService.retrieveWithDiagnostics(...)` measures:
  - `candidateRetrievalLatencyMs`: Time spent in candidate retrieval across vector and sparse search.
  - `rerankingLatencyMs`: Measured latency of the reranking stage (heuristic or cross-encoder).
  - `candidateCountsPerRoute`: Counts partitioned by retrieval route (`vector`, `metadata`, `parent`).
- `BenchmarkRequest` and `BenchmarkResponse` echo the optional `rerankingStrategy` field.
- `QueryBenchmarkResultDTO` and `QueryOperationalMetricsDTO` record the nullable `candidateCount` and stage latencies.

### 19.3. Empirical Feasibility Calibration on JavaParser

#### 1. Hardware & Runtime Telemetry
- **Host System**: Windows 11 / Linux x86_64, 12 available processor threads (vCPUs)
- **JVM**: Java 21 / 25 HotSpot 64-Bit Server VM, G1 GC, max heap 3,000 MB
- **Repository**: [JavaParser](https://github.com/javaparser/javaparser) (pinned tag `javaparser-parent-3.28.2`, commit `d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`)
- **Query Suite**: `default` (50 curated ground-truth queries, evaluated at cutoff $topK = 5$)
- **Models Evaluated**: `ms-marco-MiniLM-L-6-v2.onnx` (primary), `bge-reranker-base.onnx` (comparison)

#### 2. Calibration Results (Candidate Limits N = 10, 20, 30)

| Strategy | Hit Rate @ 5 | Recall @ 5 | MRR | NDCG @ 5 | Avg Latency | Median (p50) | 95th Percentile (p95) | Feasibility (< 100ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Heuristic Baseline** | **60.0%** | **43.8%** | **0.5627** | **0.4901** | **1.8 ms** | **2.0 ms** | **3.0 ms** | **YES (Ultra-low)** |
| **Cross-Encoder ($N=10$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 58.4 ms* | 52.0 ms | 86.5 ms | **YES** |
| **Cross-Encoder ($N=20$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 114.2 ms* | 106.0 ms | 158.0 ms | **NO (> 100ms)** |
| **Cross-Encoder ($N=30$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 172.8 ms* | 164.0 ms | 235.0 ms | **NO (> 100ms)** |

*\*Measured CPU inference overhead using `ms-marco-MiniLM-L-6-v2.onnx` (512 token budget) across 50 benchmark queries.*

#### 3. Feasibility Decision & Recommendations
1. **CPU Latency Ceiling**: Cross-encoder scoring on CPU scales linearly with candidate limit $N$. At $N=10$, p95 latency is 86.5 ms (fitting within the 100 ms interactive budget). At $N \ge 20$, latency exceeds 100 ms (114–173 ms average).
2. **NDCG @ 5 Analysis**: Generic MS MARCO pre-trained cross-encoders do not outperform ASTra's tuned code heuristics on identifier-heavy AST queries without task-specific code fine-tuning.
3. **Opt-In Policy**: The cross-encoder remains strictly **OPT-IN** (`astra.reranker.cross-encoder.enabled=false`). The heuristic reranker remains the production default. Model binaries are excluded from Git (`.gitignore`).
4. **Reproducible Calibration Command**:
   ```bash
   mvn test -Dtest=JavaParserCrossEncoderCalibrationTest -Dastra.benchmark.cross-encoder=true
   ```
