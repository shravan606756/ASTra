# JCode Intelligence & ASTra CLI

**AI-powered code comprehension system for Java codebases.** Combines AST-based static analysis with retrieval-augmented generation (RAG) to let developers query a codebase in natural language and get grounded, explainable answers. Now features the ASTra CLI, an interactive terminal companion for seamless repository exploration.

---

## Why This Project Matters

Understanding an unfamiliar Java codebase is one of the most time-consuming parts of onboarding, code review, and maintenance. JCode Intelligence addresses this by treating source code as structured, queryable knowledge rather than plain text: parsing it into a semantic representation, indexing it as vector embeddings, and using an LLM to reason over the retrieved context.

**Core capabilities:**

- **Structural parsing** - Extracts packages, classes, interfaces, and methods via `JavaParser`, preserving hierarchy instead of flattening code into raw text.
- **Semantic chunking** - Splits code along logical boundaries (class/method level) so retrieved context is coherent, not arbitrarily truncated.
- **Vector search at scale** - Stores embeddings in PostgreSQL via `pgvector`, enabling fast approximate nearest-neighbor (ANN) search over large codebases.
- **Adaptive Batch Processing** - Backed by multithreading, intelligent batch sizes dynamically adjust to reduce batch processing and indexing time by ~15%.
- **RAG-based Q&A** - Natural language questions are answered using top-K retrieved code context fed into an LLM, respecting strict token budgets, with provider flexibility across OpenAI, Ollama, and Grok.
- **Interactive Terminal Companion (ASTra)** - A rich, Windows-native CLI layer featuring an animated ASCII bunny companion, providing an engaging and highly functional local developer experience.
- **One-command indexing** - Points at a local repo or clones directly from a Git provider to build the searchable index.

---

## Architecture

Designed with clean separation of concerns across five layers: **CLI, API, Service, Parsing, and LLM Orchestration**, backed by a vector-native persistence layer.

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

| Workflow | Trigger | Path |
|---|---|---|
| **Indexing** | `POST /index` | Clone/read repo, parse AST, chunk, embed, persist to `pgvector` & `repository_statistics` |
| **Query** | `POST /chat` | Embed query, ANN search, retrieve top-K context, build & route prompt with budgeting, LLM completion, formatted response |
| **CLI Interaction** | `astra.bat` | REPL loop routes commands via `CommandDispatcher`, interacts with REST API, formats response using `AnswerFormatter`, displays via `ConsoleUI` |

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

## Engineering Highlights

- **Adaptive Batch Processing** - Concurrent thread execution with intelligent batching yields a 15% reduction in total AST parsing and vector ingestion times.
- **Strict Context Budgeting** - Retrieval pipelines enforce a strict token limit to prevent massive architectural contexts from overwhelming LLM limits (e.g., LLaMA-3.3 12k budget).
- **Structure-aware retrieval** - Chunking respects code semantics (class/method boundaries) rather than fixed-size text windows, improving retrieval precision over naive RAG.
- **Separated Presentation Layer** - The CLI maintains a highly animated user experience via `ConsoleUI`, `BunnyRenderer`, and `AnswerFormatter` while remaining a completely decoupled thin client to the REST API.
- **Provider-agnostic LLM/embedding layer** - Swap between OpenAI, Ollama, or Grok without touching business logic, via a clean `LLMClient` abstraction.

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
.\mvnw.cmd clean install
.\mvnw.cmd spring-boot:run
```

### 5. Launch the CLI Companion
In a new terminal window, start the ASTra interactive shell:

```bash
.\astra.bat
```