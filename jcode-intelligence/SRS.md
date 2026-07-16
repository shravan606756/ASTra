markdown_content = """# Software Requirements Specification (SRS)
## Intelligent Java Code Comprehension Using AST-Based Semantic Retrieval

---

## 1. Introduction

### 1.1 Purpose
The purpose of this project is to develop an intelligent Java code comprehension system that enables developers to understand, navigate, and analyze Java codebases using natural language queries. The system combines Abstract Syntax Tree (AST) parsing, semantic vector retrieval, and Artificial Intelligence to provide contextual explanations of source code and supplementary project documentation.

### 1.2 Scope
The proposed system allows users to analyze Java repositories by parsing source code into meaningful structural components such as packages, classes, interfaces, and methods. These components are transformed into semantic embeddings and stored in a PostgreSQL database with pgvector support. Furthermore, the system integrates external documentation via Google Drive MCP, enabling the LLM to cross-reference code with design documents. Users can ask natural language questions regarding the codebase, and the system retrieves relevant code segments and documentation context before generating AI-assisted responses.

The project focuses on Java source code comprehension and supplementary document analysis; it does not perform code generation or automatic code modification.

### 1.3 Intended Users
* Software Developers
* Computer Science Students
* Open Source Contributors
* Software Engineers
* Technical Interview Candidates

---

## 2. System Overview
The system consists of the following major modules:
* Repository & Documentation Acquisition Module
* Java AST Parser
* Code Chunk Generator
* Embedding Generator
* Vector Database
* Semantic Retrieval Engine
* AI Response Generator
* REST API Layer
* **Google Drive MCP Integration** (for LLM documentation access)

---

## 3. Functional Requirements

### FR1: Repository and Documentation Acquisition
The system shall allow users to analyze Java projects and associated documentation.
**Functions:**
* Import a local Java project.
* Import a GitHub repository through GitHub MCP.
* **Import project documentation (e.g., architecture docs, SRS, wikis) through Google Drive MCP for enhanced LLM context.**
* Validate project structure and accessible files before processing.
  **Output:** Repository and linked documentation ready for analysis.

### FR2: Java Source Code Parsing
The system shall parse Java source files using JavaParser.
The parser shall extract:
* Packages, Classes, Interfaces, Enums
* Methods, Constructors, Fields, Annotations
  The parser shall preserve the hierarchical structure of the project.

### FR3: AST-Based Code Chunking
The system shall divide source code into meaningful semantic units.
* Each chunk may represent: A class, a method, an interface, or an enum.
* Each chunk shall include metadata: Project Name, Package Name, File Path, Class Name, Method Name, Line Numbers, Imports, Annotations.

### FR4: Embedding Generation
The system shall generate semantic vector embeddings for every code chunk and imported documentation chunk using an embedding model. Each embedding shall be associated with its corresponding metadata.

### FR5: Vector Storage
The system shall store embeddings in PostgreSQL using pgvector.
Stored information shall include:
* Chunk ID
* Source Code / Document Text
* Embedding Vector
* Metadata

### FR6: Semantic Retrieval
The system shall retrieve the most relevant code and document chunks based on semantic similarity.
The retrieval process shall:
* Convert the user query into an embedding.
* Perform similarity search.
* Return the Top-K most relevant chunks.

### FR7: AI-Based Code Comprehension
The system shall generate responses using Spring AI integrated with an LLM.
The AI shall answer questions such as:
* Explain this class.
* Describe this method.
* Trace authentication flow.
* Which class handles database operations?
* Explain the architecture based on the provided Google Drive design docs.
* Where is validation implemented?
  Responses shall be generated only from the retrieved project and documentation context.

### FR8: Repository Question Answering
The system shall allow users to submit natural language questions regarding the imported Java project.
*Examples:*
* How does login work?
* Explain `UserService`.
* Which classes depend on `OrderRepository`?
* Where is JWT validation implemented?

### FR9: Source Reference
Each generated response shall include:
* File Name / Document Name
* Class Name
* Method Name
  This ensures high explainability and traceability.

### FR10: REST API
The system shall expose REST APIs for:
* Repository / Document Upload & Linking
* Repository Parsing
* Embedding Generation
* Semantic Search
* Question Answering

### FR11: Error Handling
The system shall gracefully handle:
* Invalid repositories or inaccessible Google Drive links
* Unsupported files
* Empty repositories
* Parsing failures
* Embedding failures
* AI service failures
  Meaningful error responses shall be returned.

---

## 4. Non-Functional Requirements

* **NFR1: Performance** - Repository parsing should complete within an acceptable time for medium-sized projects. Semantic retrieval should return results with low latency. AI responses should be generated within a reasonable response time.
* **NFR2: Scalability** - The system shall support repositories containing hundreds of Java files and multiple linked Google Drive documents without architectural changes.
* **NFR3: Reliability** - The system shall continue functioning even if one source file fails to parse or a repository contains unsupported files.
* **NFR4: Maintainability** - The application shall follow a modular layered architecture. Modules shall remain independent for future enhancements.
* **NFR5: Extensibility** - The architecture shall allow future support for Kotlin, C#, Python, and JavaScript without redesigning the entire system.
* **NFR6: Security** - The system shall validate repository inputs, prevent unauthorized API access, protect database credentials, secure Google Drive OAuth tokens/MCP access, and prevent the execution of uploaded source code.
* **NFR7: Availability** - The application shall remain operational during long-running indexing operations.
* **NFR8: Portability** - The application shall be deployable using Docker.
* **NFR9: Usability** - The APIs shall provide consistent responses, clear error messages, and human-readable explanations.
* **NFR10: Accuracy** - The system shall retrieve semantically relevant code and documentation before generating responses to reduce hallucinations.

---

## 5. System Architecture
The overall workflow is as follows:
1. Import Java repository (Local or GitHub MCP).
2. **Link and import supporting documentation via Google Drive MCP.**
3. Parse source code using JavaParser and extract text from documentation.
4. Extract AST elements from code.
5. Generate semantic chunks for both code and documents.
6. Create vector embeddings.
7. Store embeddings in PostgreSQL (pgvector).
8. Receive user query.
9. Retrieve relevant code and documentation chunks.
10. Spring AI constructs the retrieval context.
11. LLM generates a context-aware explanation.
12. Return response with source/document references.

---

## 6. Technology Stack

| Component | Technology |
| :--- | :--- |
| **Backend** | Spring Boot (Java 21) |
| **AI Integration** | Spring AI |
| **Source Code Parser** | JavaParser |
| **Repository Access** | GitHub MCP |
| **Documentation Access** | Google Drive MCP |
| **Database** | PostgreSQL |
| **Vector Database** | pgvector |
| **Embedding Model** | OpenAI / Grok / Llama-compatible embedding model |
| **Large Language Model** | Grok or Llama 3.3 |
| **Build Tool** | Maven |
| **Deployment** | Docker |

---

## 7. Assumptions
* The repository primarily contains Java source files.
* Internet connectivity is available for LLM access when using cloud-hosted models.
* GitHub repositories and Google Drive documents are accessible with the required permissions and valid tokens.
* Supported Java syntax is compatible with the selected JavaParser version.

---

## 8. Constraints
* Only Java projects are supported for AST parsing in the initial version.
* The system performs read-only analysis and does not modify source code or Drive documents.
* Response quality depends highly on the chosen embedding model and LLM.
* Extremely large repositories or extensive Google Drive folders may require longer indexing times.

---

## 9. Future Enhancements
* Cross-file dependency visualization.
* UML diagram generation.
* Sequence and class diagram generation.
* Detection of SOLID principle violations.
* AI-powered code review and refactoring suggestions.
* Multi-language support (e.g., Python, TS, Go).
* Incremental repository and document indexing.
* Integration with IDEs such as IntelliJ IDEA and VS Code.
  """

with open('SRS_Java_Code_Comprehension.md', 'w', encoding='utf-8') as f:
f.write(markdown_content)

print("Markdown file generated successfully.")