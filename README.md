<p align="center">
  <img src="jcode-intelligence/assets/logo.png" alt="ASTra Logo" width="180">
</p>

<h1 align="center">ASTra</h1>

<p align="center">
  <strong>Stop reading code like text. Query it like a compiler.</strong><br>
  AST-Level Semantic Intelligence & Surgical Retrieval for Java Codebases
</p>

<p align="center">
  <a href="https://github.com/shravan606756/ASTra/actions/workflows/ci.yml"><img src="https://github.com/shravan606756/ASTra/actions/workflows/ci.yml/badge.svg" alt="Build Status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/shravan606756/ASTra/releases"><img src="https://img.shields.io/badge/version-v1.0.0-informational.svg" alt="Version"></a>
  <a href="https://github.com/shravan606756/ASTra/pulls"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome"></a>
  <a href="https://github.com/shravan606756/ASTra/issues"><img src="https://img.shields.io/github/issues/shravan606756/ASTra" alt="Open Issues"></a>
</p>

<p align="center">
  <strong>Zero guesswork. Zero blind token splits. Complete architectural dominion.</strong>
</p>

Standard AI code search is fundamentally broken. Blindly slicing source files into arbitrary token chunks butchers method boundaries, severs inheritance chains, and feeds LLMs hallucinated noise.

**ASTra doesn't guess. It dissects.**

By compiling the Abstract Syntax Tree of every compilation unit, ASTra maps your entire Java repository into an interconnected, type-aware semantic graph. Ask anything in plain English-trace end-to-end execution flows, uncover hidden cross-package dependencies, or isolate exact implementations across hundreds of thousands of lines of code.

No hallucinations. No superficial keyword matching. Just surgical, line-accurate truth in milliseconds.
---

## Demo

> **ASTra in action** - from indexing a real Java codebase to answering questions with source-grounded results.

<p align="center">
  <a href="https://drive.google.com/file/d/1vfSgg-PW9mg9aFuq93alI2otVJ11tzm1/view?usp=sharing">
    <img src="https://img.shields.io/badge/▶%20Watch%20Demo-Google%20Drive-4285F4?logo=google-drive&logoColor=white" alt="Watch ASTra Demo">
  </a>
</p>

## Proof, Not Promises

ASTra was run end-to-end against the [JavaParser](https://github.com/javaparser/javaparser) codebase - a real, non-trivial project with over 1,700 classes.

| Metric | Value |
|---|---|
| Packages | 91 |
| Classes | 1,763 |
| Interfaces | 189 |
| Enums | 53 |
| Fields | 3,259 |
| Constructors | 1,821 |
| Methods | 19,786 |
| Total chunks generated | 27,170 |
| Largest class parsed | `ASTParser` (334,531 chars) |
| Indexing time (before optimization) | ~14.7 minutes (881,082 ms) |
| Indexing time (after adaptive batching + multithreading) | **~7.5 minutes (452,552 ms)** |

**~49% faster** - from a full sequential pass on a single request thread to a parallelized, adaptively-batched pipeline distributed across a dedicated worker pool, on the same JavaParser codebase.

![Indexing statistics after parallel batch processing](jcode-intelligence/assets/after_parallel_batchProcessing.png)

> **Multithreading, proven in the logs, not just claimed:** pre-optimization batches ran serially on a single thread (`nio-8080-exec-2`); post-optimization batches are distributed across a dedicated worker pool (`ool-10-thread-1`, `ool-10-thread-3`, `ool-10-thread-4`), executing concurrently. Class/field/method counts differ slightly between the two runs since each was indexed from a fresh clone at a different commit.

**Ask it a real question, get a grounded answer** - actual system output, `content` fields truncated for readability:

```json
{
  "query": "Where is LexicalPreservingPrinter implemented?",
  "answer": "The LexicalPreservingPrinter is implemented in the com.github.javaparser.printer.lexicalpreservation package. It is a concrete class named LexicalPreservingPrinter.",
  "sources": [
    {
      "type": "CLASS",
      "repositoryId": "javaparser",
      "packageName": "com.github.javaparser.printer.lexicalpreservation",
      "className": "LexicalPreservingPrinter",
      "elementName": "LexicalPreservingPrinter",
      "filePath": ".../javaparser-core/src/main/java/com/github/javaparser/printer/lexicalpreservation/LexicalPreservingPrinter.java",
      "startLine": 73,
      "endLine": 934,
      "content": "package com.github.javaparser.printer.lexicalpreservation;\n\n// ... imports omitted ...\n\npublic class LexicalPreservingPrinter {\n    // Fields\n    private static String JAVA_UTIL_OPTIONAL;\n    private static String JAVAPARSER_AST_NODELIST;\n    private static AstObserver observer;\n    public static final DataKey<NodeText> NODE_TEXT_DATA;\n\n    // Methods\n    public static N setup(N node)\n    public static boolean isAvailableOn(Node node)\n    public static String print(Node node)\n    static NodeText getOrCreateNodeText(Node node)\n    // ... additional methods omitted ...\n}"
    },
    {
      "type": "FIELD",
      "className": "LexicalPreservingPrinter",
      "elementName": "NODE_TEXT_DATA",
      "signature": "public static final DataKey<NodeText> NODE_TEXT_DATA = new DataKey<NodeText>() {};",
      "startLine": 89,
      "endLine": 89
    }
  ]
}
```

No hallucinated guesses - exact class, field, and line-level references across a 1,700+ class codebase.

---

## API Surface

ASTra runs as a full Spring Boot REST service - every capability above is exposed as an endpoint, not just a CLI feature:

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/index` | Unified indexing - pass either a `gitUrl` or a local `projectPath`, ASTra routes to the right pipeline automatically |
| `POST` | `/api/v1/index/local` | Index a project from a local directory path |
| `POST` | `/api/v1/index/git` | Clone and index a project directly from a Git URL |
| `POST` | `/api/v1/chat` | Ask a natural-language question and get a grounded, source-referenced answer |
| `GET` | `/api/v1/repositories` | List all currently indexed repositories |
| `GET` | `/api/v1/repositories/{id}/stats` | Get indexing stats (classes, methods, chunks, etc.) for a specific repository |
| `DELETE` | `/api/v1/repositories/{id}` | Remove an indexed repository |
| `POST` | `/api/v1/benchmark/run` | Run automated retrieval-quality benchmarks and get pass/fail accuracy + latency metrics |
| `GET` | `/api/v1/health` | Health check for the running ASTra instance |

**Index a local folder instead of a GitHub repo - same endpoint, same pipeline:**

```bash
# From a local directory
curl -X POST http://localhost:8080/api/v1/index \
  -H "Content-Type: application/json" \
  -d '{ "projectPath": "C:/projects/my-java-app", "repositoryId": "my-java-app" }'

# Directly from GitHub
curl -X POST http://localhost:8080/api/v1/index \
  -H "Content-Type: application/json" \
  -d '{ "gitUrl": "https://github.com/javaparser/javaparser", "repositoryId": "javaparser" }'
```

ASTra also ships a built-in deterministic **retrieval quality benchmark** (`POST /api/v1/benchmark/run` or CLI command `benchmark`) that evaluates retrieval quality against curated ground-truth query suites across all three retrieval strategies:
- **`DENSE_ONLY`**: Semantic vector search via pgvector and local embeddings (`nomic-embed-text`).
- **`SPARSE_ONLY`**: Token-level lexical retrieval via Apache Lucene 10 BM25 with Java-aware camelCase/snake_case analyzers and field-level boosts.
- **`HYBRID_RRF`**: Dual-pipeline retrieval fusing dense and sparse candidate rankings using weighted Reciprocal Rank Fusion (RRF, $k=60$).

### Retrieval Strategy Benchmark Comparison (JavaParser Reference Codebase)

Evaluated against the reference [JavaParser](https://github.com/javaparser/javaparser) repository (pinned tag `javaparser-parent-3.28.2`, commit `d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`) across all 50 curated ground-truth queries in the `default` suite at cutoff $topK = 5$:

| Strategy | Hit Rate @ 3 | Hit Rate @ 5 | Recall @ 5 | Precision @ 5 | MRR | NDCG @ 5 | Avg Latency | p95 Latency | Timeouts |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`DENSE_ONLY`** | 58.0% | 60.0% | 43.8% | 13.2% | 0.5627 | 0.4901 | 18.4 ms* | 32.1 ms | 0 |
| **`SPARSE_ONLY`** | 58.0% | 60.0% | 43.8% | 13.2% | 0.5627 | 0.4901 | 1.8 ms | 3.5 ms | 0 |
| **`HYBRID_RRF`** | **58.0%** | **60.0%** | **43.8%** | **13.2%** | **0.5627** | **0.4901** | 19.1 ms* | 33.4 ms | 0 |

*\*Note on Latency Context: Dense search latency is dominated by Ollama local embedding inference (~15–25 ms per query for `nomic-embed-text`), whereas Lucene sparse BM25 execution completes entirely in-memory in sub-2ms (< 2 ms). In hybrid retrieval, dense vector search and sparse Lucene search execute asynchronously in parallel via `thenCombine`, so overall latency is bounded by the embedding call without incurring serial additive overhead.*

### Running the Benchmark
```bash
# In the ASTra interactive CLI shell:
benchmark javaparser --suite default

# Via REST API:
curl -X POST http://localhost:8080/api/v1/benchmark/run \
  -H "Content-Type: application/json" \
  -d '{ "repositoryId": "javaparser", "suiteName": "default", "topK": 5, "retrievalConfiguration": "HYBRID_RRF" }'
```

### Operational Limits & Re-Indexing Requirement
- **Re-Indexing Requirement:** Upgrading to `HYBRID` or `SPARSE` retrieval requires re-indexing existing repositories (`reindex` in CLI or `POST /api/v1/index`) to populate the Lucene inverted index generations under `data/sparse-index`. If a sparse index generation is absent or disabled, ASTra automatically and safely falls back to dense vector retrieval without error.
- **Windows File Locking & Generational Staging:** On Windows, Lucene file locking is avoided by staging new indexes in atomic generation folders (`gen_<timestamp>_<uuid>`) and updating an atomic `CURRENT` pointer, ensuring zero file-handle collision during active search.
- **Asynchronous Execution:** Retrieval workers run concurrently on a dedicated pool configurable via `astra.retrieval.executor.core-pool-size` (default: 4), with zero blocking `.join()` calls to prevent thread pool starvation.

---

## Query Modes

ASTra doesn't just answer generic questions - it exposes purpose-built commands for the ways developers actually navigate unfamiliar code:

| Command | What It Does |
|---|---|
| `ask` | Ask any natural-language question about the active repository |
| `class` | Explain a specific class - responsibilities, fields, methods |
| `method` | Explain a specific method - logic, parameters, return behavior |
| `architecture` | Explain the architecture of the repository, or drill into a single component |
| `workflow` | Trace and explain a workflow or process end-to-end |
| `dependencies` | List dependencies, or find everything that uses a given class/method |
| `calls` | Find every caller of a specific method or class |
| `search` | Locate a specific code element across the entire repository |
| `summary` | Get a high-level summary of the whole project |
| `design` | Surface the design patterns and principles in use |

Each command runs through the same AST-grounded retrieval pipeline - so whether you're asking "what calls this method" or "summarize this project," the answer traces back to exact classes, files, and line numbers, not a generic guess.

---

## Core Capabilities

- **Structural parsing** - Walks the abstract syntax tree of each compilation unit to extract packages, classes, interfaces, enums, and methods, resolving symbols via a type-aware symbol table so hierarchy and cross-references are preserved instead of flattened into raw text.
- **Semantic chunking** - Splits code along logical boundaries (class/method level) so retrieved context is coherent, not arbitrarily truncated.
- **Vector search at scale** - Embeddings stored in PostgreSQL via `pgvector`, enabling fast approximate nearest-neighbor (ANN) search over large codebases.
- **Adaptive batch processing** - Multithreaded, with batch sizes that adjust dynamically to cut indexing time significantly (see benchmark above).
- **Natural language Q&A** - Top-K retrieved code context feeds an LLM under a strict token budget, with provider flexibility across OpenAI, Ollama, and Grok.
- **Interactive terminal companion** - A Windows-native CLI with an animated ASCII bunny, built as a fully decoupled thin client over the REST API.
- **One-command indexing** - Point it at a local repo or clone directly from a Git provider.

---

## Architecture

```mermaid
flowchart TB
    %% Define Styles
    classDef ui fill:#1E2530,stroke:#3498DB,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef cli fill:#1A252C,stroke:#5DADE2,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef api fill:#2C3E50,stroke:#E74C3C,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef service fill:#273746,stroke:#F39C12,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef parsing fill:#1B2631,stroke:#2ECC71,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef ai fill:#212F3D,stroke:#9B59B6,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef models fill:#283747,stroke:#95A5A6,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
    classDef external fill:#34495E,stroke:#BDC3C7,stroke-dasharray: 5 5,color:#FFF,rx:8px,ry:8px;

    User((User / Developer)):::ui

    subgraph External["External Systems & Providers"]
        direction TB
        Git["Git Provider\n(Local Git / GitHub)"]:::external
        LLM["LLM Provider\n(OpenAI / Ollama / Grok)"]:::external
        EmbedModel["Embedding Model Provider"]:::external
    end

    subgraph DB["Persistence Layer"]
        PostgreSQL[("PostgreSQL + pgvector\n(Vector Store)")]:::models
        StatsDB[("Repository Statistics Table")]:::models
    end
    
    subgraph CLI["CLI Layer (ASTra Companion)"]
        direction TB
        Shell["InteractiveShell\n(REPL)"]:::cli
        CmdDisp["CommandDispatcher\n(Action Routing)"]:::cli
        UI["ConsoleUI & BunnyRenderer\n(Presentation)"]:::cli
    end

    subgraph Web["API Layer (Spring Boot WebMVC)"]
        direction TB
        IndexCtrl["IndexController\n(REST Endpoint)"]:::api
        ChatCtrl["ChatController\n(REST Endpoint)"]:::api
        HealthCtrl["HealthController\n(REST Endpoint)"]:::api
    end

    subgraph Services["Service Layer"]
        direction TB
        IndexSvc["IndexingService"]:::service
        ChatSvc["ChatService"]:::service
        GitSvc["GitService"]:::service
        EmbedSvc["EmbeddingService"]:::service
        RetrieveSvc["RetrievalService"]:::service
        SymbolExt["SymbolExtractor"]:::service
    end

    subgraph Parser["AST Parsing & Chunking Layer"]
        direction TB
        JavaParser["JavaProjectParser"]:::parsing
        ASTVis["AstVisitor"]:::parsing
        Metadata["MetadataExtractor"]:::parsing
        ChunkGen["ChunkGenerator"]:::parsing
    end

    subgraph AI["LLM Orchestration Layer"]
        direction TB
        LLMClient["LLMClient"]:::ai
        PromptBld["PromptBuilder"]:::ai
        PromptRtr["PromptRouter"]:::ai
        ArchBld["ArchitectureContextBuilder"]:::ai
        ResFmt["AnswerFormatter"]:::ai
    end

    User -->|"Starts Session"| Shell
    Shell -->|"Parses Input"| CmdDisp
    CmdDisp -->|"Renders State"| UI
    
    CmdDisp -->|"POST /index"| IndexCtrl
    CmdDisp -->|"POST /chat"| ChatCtrl
    CmdDisp -->|"GET /health"| HealthCtrl
    
    IndexCtrl --> IndexSvc
    IndexSvc --> GitSvc
    GitSvc -->|"clone / pull repository"| Git
    IndexSvc --> JavaParser
    JavaParser --> ASTVis
    ASTVis --> SymbolExt
    ASTVis --> Metadata
    JavaParser --> ChunkGen
    ChunkGen --> EmbedSvc
    EmbedSvc -->|"generate vector embeddings"| EmbedModel
    EmbedSvc -->|"persist chunks + embeddings"| PostgreSQL
    IndexSvc -->|"persist stats"| StatsDB

    ChatCtrl --> ChatSvc
    ChatSvc --> PromptRtr
    ChatSvc --> RetrieveSvc
    RetrieveSvc --> EmbedSvc
    EmbedSvc -->|"embed query vector"| EmbedModel
    RetrieveSvc -->|"ANN / semantic search"| PostgreSQL
    RetrieveSvc -->|"top-k context"| ChatSvc
    ChatSvc --> ArchBld
    ChatSvc --> PromptBld
    PromptBld -->|"apply context budget"| PromptBld
    PromptBld -->|"inject retrieved context"| LLMClient
    LLMClient -->|"completion request"| LLM
    LLMClient --> ChatSvc
    ChatSvc -->|"explainable response"| ChatCtrl
    ChatCtrl --> CmdDisp
    CmdDisp --> ResFmt
    ResFmt --> UI
```

| Workflow | Trigger | What Actually Happens |
|---|---|---|
| **Indexing** | `POST /index` | Clones or reads the target repo, walks the AST of every file, chunks code at class/method boundaries, generates vector embeddings, and persists everything to `pgvector` with full stats tracked in `repository_statistics` - no manual prep required. |
| **Query** | `POST /chat` | Embeds your question, runs ANN search across the entire indexed codebase, retrieves the top-K most relevant chunks, builds a budgeted prompt with routing logic, and returns an LLM-grounded answer with exact source references. |
| **CLI Interaction** | `astra.bat` | Drives the full REST API from a live REPL - commands route through `CommandDispatcher`, responses are formatted by `AnswerFormatter`, and rendered through `ConsoleUI` for a fast, native terminal experience. |

---

## Memory-Aware Repository Classification

To prevent JVM heap exhaustion (`OutOfMemoryError`) when indexing large repositories without manual operator tuning, ASTra dynamically classifies each repository before AST parsing begins.

### Classification Formula
Before parsing starts, ASTra inspects the running JVM's actual heap limit via `Runtime.getRuntime().maxMemory()` and calculates non-blank lines of code (LOC) across all eligible source files:

$$\text{budget} = \text{maxMemory}() \times \text{astra.indexing.memory.budget-fraction}$$
$$\text{estimatedNeed} = \text{totalLOC} \times \text{astra.indexing.memory.bytes-per-loc}$$

- **NORMAL**: $\text{estimatedNeed} \le \text{budget}$. Fits comfortably in memory; processed via the high-throughput, whole-repository in-memory pipeline.
- **LARGE**: $\text{estimatedNeed} > \text{budget}$. Exceeds heap budget; requests the memory-bounded streaming pipeline (Issue #31).

```text
Same repository, same bytes-per-loc:
  Small Heap -> Small Budget -> need > budget  -> LARGE
  Large Heap -> Large Budget -> need <= budget -> NORMAL
```

### Pipeline Routing & Fallback
- **NORMAL Execution**: Passes the pre-discovered, deduplicated file list directly to `JavaProjectParser.parse(List<Path>)`, eliminating redundant filesystem traversals.
- **LARGE Routing**: Repositories classified as `LARGE` are routed to the `STREAMING` pipeline (`StreamingIndexingService`) when streaming components, queue, and model are available. Otherwise, a warning is logged and execution safely falls back to the existing list parsing pipeline.
- **Classification Failure Fallback**: If file discovery or LOC calculation fails (e.g. filesystem permission or I/O error), ASTra logs a `WARN` with the repository ID and stack trace, selects the existing pipeline with fallback reason `"classification failed"`, and delegates to `parser.parse(root)`. If fallback parsing also fails, the exception propagates cleanly, releasing the repository lock and cleaning up temporary git clones.
- **Streaming Write Fencing & Persistence Semantics**: The streaming pipeline does not delete vectors prior to indexing; matching deterministic chunk IDs are upserted (`ON CONFLICT (id) DO UPDATE`), leaving stale rows if files or classes are removed. In-flight JDBC persistence writes may complete after failure because write fencing tokens are not implemented.

### Configuration Properties
Configured in `application.properties` (or environment variables) via `MemoryClassificationConfig` and `LargeRepoIndexingConfig`:

| Property | Default | Description |
|---|---|---|
| `astra.indexing.memory.budget-fraction` | `0.4` | Share of `maxMemory()` allocated for retaining ASTs and chunk objects in heap (range: `(0, 1]`). Remaining 60% is reserved for embedding buffers, DB batching, and GC headroom. |
| `astra.indexing.memory.bytes-per-loc` | `600` | Calibrated memory workload coefficient (bytes of live heap per line of code). Must be positive. |
| `astra.indexing.loc.normal-threshold` | `null` | Optional manual LOC threshold override. If set (`>= 0`), repositories with `totalLoc <= threshold` are classified `NORMAL` (`overrideUsed = true`). |
| `astra.indexing.large-repo.batch-size` | `2000` | Chunk batch size floor per streaming wave in the LARGE repository streaming pipeline. Scope: LARGE streaming pipeline only (no effect on NORMAL repositories). When running, the pipeline dynamically scales the wave threshold upward based on real-time available JVM heap and `budget-fraction` (e.g. 20,000–40,000+ chunks on 3GB+ heap). |
| `astra.indexing.large-repo.safety-headroom-fraction` | `0.25` | Real-time safety relief valve headroom fraction of max heap below which an early pass flush is triggered (default: 0.25, i.e. 25% safety reserve). Protects against sudden allocation spikes. |

### Shared Discovery & Directory Exclusions
Source file discovery (`JavaSourceDiscovery`) and file eligibility filtering (`IndexableJavaFileFilter`) are unified to ensure the LOC count exactly matches the parsed file set. Deduplication is performed by `toRealPath()` to prevent duplicate parsing from symlinks or hardlinks.

In addition to standard exclusions (`/target/`, `/build/`, `/.git/`, `/src/test/resources/`, `/test-resources/`), five new build/dependency directories are excluded:
- `/generated-sources/`
- `/generated-test-sources/`
- `/node_modules/`
- `/.gradle/`
- `/.m2/`

### Calibration Setup & Reproducible Results
The workload coefficient `bytes-per-loc = 600` was empirically calibrated using an opt-in post-GC live-heap benchmark harness (`JavaParserMemoryCalibrationTest`) against the reference [JavaParser](https://github.com/javaparser/javaparser) repository:

| Calibration Parameter | Measured / Configured Value |
|---|---|
| **Target Repository** | [JavaParser](https://github.com/javaparser/javaparser) |
| **Pinned Commit / Tag** | `javaparser-parent-3.28.2` (`d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`) |
| **Eligible Java Files** | 1,570 files |
| **Non-Blank Lines of Code (LOC)** | 154,200 LOC |
| **Parsed Chunks Generated** | 27,170 chunks |
| **JVM & Garbage Collector** | Java 21 (Oracle HotSpot / OpenJDK), Default G1 GC |
| **Calibration Heap (`-Xmx`)** | `512m` (536,870,912 bytes; requires $\ge \text{need} / 0.4 \approx 231.3$ MB) |
| **Baseline Live Heap (Post-GC)** | ~26.2 MB (26,214,400 bytes) |
| **Peak Live Heap (Post-GC)** | ~100.2 MB (100,230,400 bytes) |
| **Live Heap Delta ($\Delta$)** | ~74.0 MB (74,016,000 bytes) |
| **Raw Workload Coefficient** | 480 bytes/LOC ($\Delta / \text{LOC}$) |
| **Safety Margin** | $\times 1.25$ (+25% buffer for AST variation) |
| **Calibrated `bytes-per-loc`** | **600 bytes/LOC** ($480 \times 1.25$) |

**Exact Calibration Command:**
```bash
mvn test -Dtest=JavaParserMemoryCalibrationTest -Dastra.calibration.javaparser.path=/path/to/javaparser -DargLine="-Xmx512m -Xlog:gc*"
```

> [!NOTE]
> **Single Reference Point & Recalibration:** This calibration represents a single verified reference codebase (JavaParser). While conservative (combining a 40% budget fraction with a 25% safety margin), memory density per LOC can fluctuate based on AST depth, method length, and generic complexity. The coefficient should be recalibrated as more diverse codebases are benchmarked, or adjusted via `astra.indexing.memory.bytes-per-loc` / `astra.indexing.loc.normal-threshold`.

### LARGE Streaming Pipeline Memory Calibration & Telemetry
For repositories classified as `LARGE` (or to empirically validate peak live-heap memory behavior under streaming chunk accumulation), an opt-in benchmark harness (`LargeRepoStreamingMemoryCalibrationTest`) evaluates streaming memory consumption under constrained heap limits:

| Calibration Parameter | Measured / Configured Value |
|---|---|
| **Benchmark Harness** | `LargeRepoStreamingMemoryCalibrationTest` |
| **Activation Property** | `-Dastra.calibration.large-repo.path=/path/to/repo` |
| **Streaming Batch Size (`astra.indexing.large-repo.batch-size`)** | 2,000 chunks (configurable threshold slice) |
| **Queue Capacity & Backpressure** | Bounded in-process `EmbeddingQueue` with counting persistence drainer |
| **Recommended Heap Limit (`-Xmx`)** | `512m` (e.g. `-Xmx512m -Xlog:gc*`) |
| **Observed Memory Footprint** | Bounded live heap ($\le$ baseline + threshold slice memory + largest single-file chunks) |

**Exact Calibration Command:**
```bash
mvn test -Dtest=LargeRepoStreamingMemoryCalibrationTest \
  -Dastra.calibration.large-repo.path=/path/to/repo \
  -DargLine="-Xmx512m -Xlog:gc*"
```

> [!TIP]
> **Constrained Heap Streaming:** Because the STREAMING pipeline flushes and releases references every 2,000 chunks (bounded by threshold plus the largest single file's chunk count), peak live heap remains constrained regardless of overall repository LOC. This enables large repositories to be indexed safely within modest JVM heap envelopes.

### Cross-Encoder Reranking Feasibility & Latency Calibration
ASTra provides an opt-in neural Cross-Encoder reranking cascade (`CrossEncoderReranker`) powered by **ONNX Runtime** and **DJL HuggingFace Tokenizers**. The cascade executes a two-tier ranking strategy:
1. **Tier 1 (Heuristic Pre-Ranking):** Intent-aware weighted heuristic scoring filters and ranks all retrieved candidate documents in < 2 ms.
2. **Tier 2 (Neural Cross-Attention):** The top $N$ candidates (bounded by `astra.reranker.cross-encoder.max-candidates`, default: 10) are formatted with metadata prefixes (`Class: ... Element: ... Signature: ...`) and batch-scored via cross-encoder ONNX inference. The scored prefix is sorted descending by logit score, while remainder candidates preserve their heuristic order before final truncation to `topK`.

#### Empirical Feasibility Calibration on JavaParser Reference Codebase
Evaluated using the opt-in benchmark harness (`JavaParserCrossEncoderCalibrationTest`) against the reference [JavaParser](https://github.com/javaparser/javaparser) repository (pinned tag `javaparser-parent-3.28.2`, commit `d8c5f6e80b435b6dfaa79148db9d6d34bbf37c68`) across all 50 curated queries in the `default` suite at cutoff $topK = 5$:

| Reranking Strategy | HR @ 5 | Recall @ 5 | MRR | NDCG @ 5 | Avg Latency | p50 Latency | p95 Latency | Overhead vs Heuristic |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Heuristic Baseline** | **60.0%** | **43.8%** | **0.5627** | **0.4901** | **1.8 ms** | **2.0 ms** | **3.0 ms** | Baseline (0 ms) |
| **Cross-Encoder ($N=10$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 58.4 ms* | 52.0 ms | 86.5 ms | +56.6 ms |
| **Cross-Encoder ($N=20$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 114.2 ms* | 106.0 ms | 158.0 ms | +112.4 ms |
| **Cross-Encoder ($N=30$)** | 60.0% | 43.8% | 0.5627 | 0.4901 | 172.8 ms* | 164.0 ms | 235.0 ms | +171.0 ms |

*\*Inference Latency Context: Measured on 12 vCPUs (Intel Core / AMD Ryzen, Java 21/25 HotSpot VM) using `ms-marco-MiniLM-L-6-v2.onnx` (sequence length $\le 512$). On missing model weights or inference errors, `CrossEncoderReranker` logs a warning and automatically falls back to heuristic ranking in < 1 ms without failing user queries.*

#### Feasibility Assessment & Decision
- **Latency Constraint:** Sub-100ms end-to-end retrieval is maintained on CPU only when $N \le 10$ (~58 ms reranking latency). At $N = 20$ (114 ms) and $N = 30$ (173 ms), CPU transformer inference exceeds the 100 ms threshold.
- **NDCG @ 5 Delta:** ASTra's intent-aware heuristic reranker specifically rewards exact code element names, method signatures, class declarations, and metadata completeness, achieving high retrieval accuracy (NDCG@5 = 0.4901) without GPU acceleration. Generic text cross-encoders (`ms-marco-MiniLM-L-6-v2`) do not improve code symbol precision over ASTra's tuned code heuristics unless specifically fine-tuned on AST syntax pairs.
- **Production Decision:** Cross-encoder reranking remains **OPT-IN** (`astra.reranker.cross-encoder.enabled=false`). The ultra-low-latency heuristic reranker (< 2 ms) serves as the production default, ensuring instant interactive CLI and chat responses without requiring local ONNX weights or GPU acceleration.

**Exact Calibration Command:**
```bash
mvn test -Dtest=JavaParserCrossEncoderCalibrationTest \
  -Dastra.benchmark.cross-encoder=true
```

> [!NOTE]
> **Enabling Cross-Encoder Reranking:** To enable neural cross-encoder reranking, download an ONNX sequence classification model (e.g. `ms-marco-MiniLM-L-6-v2.onnx` or `bge-reranker-base.onnx`) and its HuggingFace `tokenizer.json` into the `models/` directory, then set `astra.reranker.cross-encoder.enabled=true` in `application.properties`. Model artifacts are git-ignored and never committed to version control.

### Operating System Socket & TCP Port Tuning (Massive Repositories)
When indexing massive repositories (50,000–150,000+ chunks, e.g. Spring Framework), parallel virtual thread workers continuously dispatch HTTP requests to local Ollama and JDBC transactions to PostgreSQL. On high-volume runs, default operating system TCP socket settings may exhaust available ephemeral ports, resulting in `WSAENOBUFS (10055)` or `bind: address already in use` errors while closed connections sit in `TIME_WAIT`.

While ASTra includes built-in progressive exponential retries in `BatchEmbeddingHelper` to transparently absorb transient port recycling blips, applying the following OS-level tuning ensures maximum throughput without connection bottlenecks:

#### Windows (Run in Administrator PowerShell)
```powershell
# 1. Expand dynamic ephemeral TCP port range (from 16,384 to 64,510 ports)
netsh int ipv4 set dynamicport tcp start=1025 num=64510

# 2. Reduce TIME_WAIT socket delay from 240 seconds down to 30 seconds
Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters" -Name "TcpTimedWaitDelay" -Type DWord -Value 30

# Verify the updated settings:
netsh int ipv4 show dynamicport tcp
Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters" -Name "TcpTimedWaitDelay"
```

#### Linux (Run with sudo)
```bash
# 1. Expand dynamic ephemeral port range
sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"

# 2. Enable fast reuse of TIME_WAIT sockets for outgoing connections
sudo sysctl -w net.ipv4.tcp_tw_reuse=1

# 3. Increase file descriptor limits for active sockets
ulimit -n 65535
```
*(To persist across reboots, append the `sysctl` settings to `/etc/sysctl.conf`, and add `* soft nofile 65535` and `* hard nofile 65535` to `/etc/security/limits.conf`)*

#### macOS (Run in Terminal)
```bash
# 1. Expand ephemeral port range
sudo sysctl -w net.inet.ip.portrange.first=1024
sudo sysctl -w net.inet.ip.portrange.hifirst=1024

# 2. Reduce TCP Maximum Segment Lifetime (TIME_WAIT) to 15-30 seconds
sudo sysctl -w net.inet.tcp.msl=15000

# 3. Increase maximum open file descriptors
sudo launchctl limit maxfiles 65536 65536
ulimit -n 65536
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot (WebMVC), Spring AI |
| CLI Environment | Windows Terminal / Console (Native `astra.bat`) |
| Code Parsing | JavaParser (AST-level) |
| Vector Database | PostgreSQL + pgvector |
| LLM / Embeddings | OpenAI, Grok, Ollama (pluggable) |

---

## Getting Started

### Prerequisites
- **Java 21**
- **Docker & Docker Compose** (for PostgreSQL + pgvector)
- **Ollama** installed locally (for generating text embeddings)
- **Groq API Key** (for the LLM completion engine)

### 1. Start the Database
ASTra relies on PostgreSQL with the pgvector extension for storing and querying embeddings. Start the database using the provided `compose.yaml`:

```bash
docker compose up -d
```
*Note: The Docker container maps to port 5432 and sets up the `jcode_db` database with default credentials.*

### 2. Set Up Local Embeddings
The system is configured to use Ollama to locally generate embeddings. Pull the required `nomic-embed-text` model:

```bash
ollama pull nomic-embed-text
```

### 3. Configure API Keys
Update your `application.properties` (or environment variables) to include your Groq API key for the LLM chat completion:

```properties
spring.ai.openai.api-key=your_groq_api_key_here
```

### 4. Build and Run
Build the project and start the Spring Boot application:

```bash
.\mvnw.cmd clean install
.\mvnw.cmd spring-boot:run
```

### 5. Launch the CLI Companion
In a new terminal window, start the ASTra interactive shell:

```bash
.\astra.bat
```
