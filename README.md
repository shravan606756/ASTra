# JCode Intelligence

**AI-powered code comprehension system for Java codebases.** Combines AST-based static analysis with retrieval-augmented generation (RAG) to let developers query a codebase in natural language and get grounded, explainable answers.

---

## Why This Project Matters

Understanding an unfamiliar Java codebase is one of the most time-consuming parts of onboarding, code review, and maintenance. JCode Intelligence addresses this by treating source code as structured, queryable knowledge rather than plain text: parsing it into a semantic representation, indexing it as vector embeddings, and using an LLM to reason over the retrieved context.

**Core capabilities:**

- **Structural parsing** - Extracts packages, classes, interfaces, and methods via `JavaParser`, preserving hierarchy instead of flattening code into raw text.
- **Semantic chunking** - Splits code along logical boundaries (class/method level) so retrieved context is coherent, not arbitrarily truncated.
- **Vector search at scale** - Stores embeddings in PostgreSQL via `pgvector`, enabling fast approximate nearest-neighbor (ANN) search over large codebases.
- **RAG-based Q&A** - Natural language questions (e.g., *"How does the authentication flow work?"*) are answered using top-K retrieved code context fed into an LLM, with provider flexibility across OpenAI, Ollama, and Grok.
- **One-command indexing** - Points at a local repo or clones directly from a Git provider to build the searchable index.
- **Benchmarking endpoint** - A dedicated `/benchmark` API for quantitative evaluation of LLM response fidelity, latency, and retrieval relevance.

---

## Architecture

Designed with clean separation of concerns across four layers: **API, Service, Parsing, and LLM Orchestration**, backed by a vector-native persistence layer.

```mermaid
flowchart TB
    %% Define Styles
    classDef ui fill:#1E2530,stroke:#3498DB,stroke-width:2px,color:#FFF,rx:8px,ry:8px;
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
    end

    subgraph Web["API Layer (Spring Boot WebMVC)"]
        direction TB
        IndexCtrl["IndexController\n(REST Endpoint)"]:::api
        ChatCtrl["ChatController\n(REST Endpoint)"]:::api
        BenchCtrl["BenchmarkController\n(REST Endpoint)"]:::api
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
        PromptTpl["PromptTemplateLoader"]:::ai
        ResFmt["ResponseFormatter"]:::ai
    end

    User -->|"POST /index (trigger indexing)"| IndexCtrl
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

    User -->|"POST /chat (NL code query)"| ChatCtrl
    ChatCtrl --> ChatSvc
    ChatSvc --> PromptRtr
    ChatSvc --> RetrieveSvc
    RetrieveSvc --> EmbedSvc
    EmbedSvc -->|"embed query vector"| EmbedModel
    RetrieveSvc -->|"ANN / semantic search"| PostgreSQL
    RetrieveSvc -->|"top-k context"| ChatSvc
    ChatSvc --> PromptBld
    PromptBld --> PromptTpl
    PromptBld -->|"inject retrieved context"| LLMClient
    LLMClient -->|"completion request"| LLM
    LLMClient --> ResFmt
    ResFmt --> ChatSvc
    ChatSvc -->|"explainable response"| ChatCtrl

    User -->|"POST /benchmark"| BenchCtrl
    BenchCtrl --> ChatSvc
```

| Workflow | Trigger | Path |
|---|---|---|
| **Indexing** | `POST /index` | Clone/read repo, parse AST, chunk, embed, persist to `pgvector` |
| **Query** | `POST /chat` | Embed query, ANN search, retrieve top-K context, build & route prompt, LLM completion, formatted response |
| **Benchmark** | `POST /benchmark` | Runs evaluation queries through the chat pipeline, measuring response fidelity, latency, and retrieval relevance |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot (WebMVC), Spring AI |
| Code Parsing | JavaParser (AST-level) |
| Vector Database | PostgreSQL + pgvector |
| LLM / Embeddings | OpenAI, Grok, Ollama (pluggable) |

---

## Validation: Indexing the JavaParser Codebase

To validate correctness and scalability on a real, non-trivial codebase, JCode Intelligence was run end-to-end against the [JavaParser](https://github.com/javaparser/javaparser) project itself.

**Indexing statistics:**

| Metric | Value |
|---|---|
| Packages | 91 |
| Classes | 1,757 |
| Interfaces | 189 |
| Enums | 53 |
| Fields | 3,191 |
| Constructors | 1,821 |
| Methods | 19,768 |
| Total chunks generated | 27,079 |
| Largest class parsed | `ASTParser` (334,531 chars) |
| Total indexing time | ~14.7 minutes (881,082 ms) |

![Indexing statistics terminal output](jcode-intelligence/assests/parsed_Javaparser_from_github.png)

**Sample query result** (actual system output, `content` fields truncated for readability):

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

This confirms the retrieval pipeline grounds responses to exact class-, field-, and line-level locations across a 1,700+ class codebase, returning structured, verifiable source references rather than a free-text guess.

---

## Engineering Highlights

- **Provider-agnostic LLM/embedding layer** - swap between OpenAI, Ollama, or Grok without touching business logic, via a clean `LLMClient` abstraction.
- **Structure-aware retrieval** - chunking respects code semantics (class/method boundaries) rather than fixed-size text windows, improving retrieval precision over naive RAG.
- **Layered, testable architecture** - strict separation between REST controllers, services, parsing, and prompt orchestration keeps components independently unit-testable.

---

## Getting Started

Follow these steps to set up the vector database, local embedding models, and run the application.

### Prerequisites
- **Java 21**
- **Docker & Docker Compose** (for PostgreSQL + pgvector)
- **Ollama** installed locally (for generating text embeddings)
- **Groq API Key** (for the LLM completion engine)

### 1. Start the Database
JCode Intelligence relies on PostgreSQL with the pgvector extension for storing and querying embeddings. Start the database using the provided `compose.yaml`:

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
./mvnw clean install
./mvnw spring-boot:run
```