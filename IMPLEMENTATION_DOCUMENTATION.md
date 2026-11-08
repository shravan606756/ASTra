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
Issue #47 hardens the shared staging queue between chunk embedding generation and PostgreSQL `vector_store` bulk persistence:

```
Indexing Pipeline (Producers)
        │
        ▼
RepositoryConcurrencyManager  ──[Serializes same-repo jobs; 409 Conflict]
        │
        ▼
   EmbeddingQueue             ──[Bounded capacity; Backpressure on full]
        │
   Heterogeneous Batch Drain  ──[Drains mixed-job batches; 200ms flush timeout]
        │
        ▼
PgVectorBulkWriter            ──[Idempotent ON CONFLICT (id) DO UPDATE; Adaptive splitting]
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
   Persistence writers drain records across any active jobs greedily up to `astra.indexing.persistence.batch-size`. A time-bounded flush interval (`flush-interval-ms=200`) ensures partial batches under backpressure are committed without requiring a full batch, preventing multi-job queue deadlocks.

4. **Write Idempotency & Adaptive Splitting:**
   - Every `EmbeddedRecord` receives a deterministic UUID derived from `repositoryId + ":" + chunkId`.
   - The bulk persistence SQL executes `ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding`, allowing safe retries on dropped connections.
   - Oversized payload failures trigger recursive adaptive batch splitting down to `min-batch-size`.

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
- $W_{\text{persist}}$ = persistence writer worker count (default: 2)
- $B_{\text{persist}}$ = persistence bulk batch size (default: 100)

At default values ($2,000 \times \approx 6.1\text{ KB} + 2 \times 100 \times 6.1\text{ KB}$), total staged memory overhead is **$\approx 13.4$ MB**, guaranteeing memory safety under high producer throughput.

#### Distinct Configuration Concerns

| Property | Layer | Concern |
|---|---|---|
| `astra.embedding.batch-size` | Embedding Model (Ollama) | Batch size sent to Ollama HTTP API per embedding inference call. |
| `astra.indexing.embedding-queue.capacity` | Memory Backpressure Buffer | Bounded queue capacity staging embedded records before DB persistence. |
| `astra.indexing.persistence.batch-size` | PostgreSQL / PGVector | Bulk JDBC insert batch size draining from queue to `vector_store`. |
| `astra.indexing.large-repo-threshold-loc` | Pipeline Selection (#31) | Repository classification threshold for large-repo streaming pipeline. |


