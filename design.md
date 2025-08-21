# Design Document: Document Categorization and Content Extraction System

## 1. System Overview

### 1.1 Purpose
Prototype a system that categorizes uploaded documents and extracts structured content (entities, dates, and tables) using LLM/VLMs, with emphasis on clarity, robustness, and fraud-prevention use cases.

### 1.2 High-level Architecture
- **Backend**: Scala 2.13.16, Scalatra REST API, SBT 1.11.4
- **Frontend**: Python Streamlit app
- **Database**: H2 (lightweight, local dev)
- **LLM/VLM**: OpenRouter API (multiple models)

### 1.3 Key Decisions (from requirements)
- **Communication**: REST over Scalatra. No streaming for v1 (future improvement).
- **Processing mode**: Synchronous (user waits) and one file at a time (batch is future work).
- **Storage**: Persist file metadata, checksum, processing status/history, extracted results, and original file bytes for download. If local DB storage of large files proves impractical, use a placeholder link (future S3 integration).
- **LLM behavior**: Different models by file type where sensible (e.g., VLM for images/PDFs with images). Prompt to return strict JSON; implement retries and format-correction.
- **UX**: Progress indication, clear errors, confidence score for categorization, HTML table display with CSV download.
- **Validation/Assumptions**: Basic file type validation; no file size limit (documented); single-user assumption; prioritize readability over micro-optimizations.


## 2. Technical Architecture

### 2.1 Component Diagram
```
┌─────────────────┐      HTTP/REST      ┌────────────────────────┐  HTTPS (OpenRouter API) ┌─────────────────┐
│  Streamlit UI   │  ◄────────────────► │     Scala Backend      │  ◄─────────────────────►│  OpenRouter.ai  │
│  (Frontend)     │                     │  (Scalatra + Services) │                         │   (LLM / VLM)   │
└─────────────────┘                     └──────────┬─────────────┘                         └─────────────────┘
                                                   │ JDBC
                                                   ▼
                                         ┌─────────────────┐
                                         │      H2 DB      │
                                         └─────────────────┘
```

### 2.2 Data Flow (synchronous)
1. User uploads a file in Streamlit.
2. Streamlit sends multipart/form-data to backend `/api/files/upload`.
3. Backend validates type, computes checksum, persists file and metadata.
4. Backend determines whether file likely contains images and selects model.
5. Backend calls OpenRouter for categorization and extraction.
6. Backend persists results, then returns the full result payload in the upload response.
7. Frontend displays category, confidence, entities, and tables; offers CSV export and original file download.


## 3. Data Model (H2)

Note: For portability, JSON is stored as `CLOB` (text). H2-specific JSON types/functions can be used in future iterations.

### 3.1 Tables

#### `files`
```
id                VARCHAR(36)   PRIMARY KEY
filename          VARCHAR(255)  NOT NULL
file_size         BIGINT        NOT NULL
file_type         VARCHAR(50)   NOT NULL           -- mime or normalized (pdf/png/jpeg/jpg)
checksum          VARCHAR(64)   NOT NULL           -- e.g., CRC32 (MD5 optional future cache)
file_content      BLOB          NOT NULL           -- original bytes for download (or link in future)
upload_timestamp  TIMESTAMP     NOT NULL
processing_status VARCHAR(20)   NOT NULL           -- e.g., completed/failed
error_message     CLOB                              -- last error (if any)
```

#### `extractions`
```
id                    VARCHAR(36)  PRIMARY KEY
file_id               VARCHAR(36)  NOT NULL REFERENCES files(id)
category              VARCHAR(50)  NOT NULL
confidence_score      DECIMAL(4,3) NOT NULL        -- 0.000-1.000
extracted_data_json   CLOB         NOT NULL        -- entire normalized JSON snapshot
extraction_timestamp  TIMESTAMP    NOT NULL
model_used            VARCHAR(100) NOT NULL
```

#### `entities`
```
id               VARCHAR(36)  PRIMARY KEY
extraction_id    VARCHAR(36)  NOT NULL REFERENCES extractions(id)
entity_type      VARCHAR(50)  NOT NULL            -- name, phone, ip_address, account_id, etc.
entity_value     CLOB         NOT NULL
confidence_score DECIMAL(4,3)
```

#### `tables`
```
id              VARCHAR(36)  PRIMARY KEY
extraction_id   VARCHAR(36)  NOT NULL REFERENCES extractions(id)
table_name      VARCHAR(255)
table_data_json CLOB         NOT NULL            -- { headers:[], rows:[[]] }
```


## 4. API Design (Scalatra)

### 4.1 Upload and process (synchronous)
`POST /api/files/upload`
- Request: multipart/form-data with `file`.
- Response (200):
```
{
  "fileId": "uuid",
  "filename": "string",
  "fileSize": number,
  "fileType": "string",
  "category": "invoice|marketplace_listing_screenshot|chat_screenshot|website_screenshot|other",
  "confidenceScore": number,                        
  "entities": [ { "type": "name", "value": "...", "confidence": 0.91 }, ... ],
  "dates": [ "YYYY-MM-DD", ... ],
  "tables": [ { "table_name": "...", "headers": [..], "rows": [[..],[..]] } ],
  "downloadUrl": "/api/files/{fileId}/download"
}
```
- Errors: 400 (validation), 415 (unsupported type), 500 (processing). Include a helpful `message` field.

### 4.2 Download original file
`GET /api/files/{fileId}/download`
- Response: original file bytes with correct `Content-Type` and `Content-Disposition`.

### 4.3 Get processed result by id
`GET /api/files/{fileId}`
- Response: same shape as upload response (retrieved from DB).

### 4.4 List files (optional helper)
`GET /api/files`
- Response: minimal listing with `fileId`, `filename`, `category`, `uploadTimestamp`.


## 5. LLM/VLM Integration via OpenRouter

### 5.1 Model selection
```
// pseudo-logic
selectModel(fileType, hasImages):
  if fileType in ["png", "jpeg", "jpg"]: return "openai/gpt-4o"
  if fileType == "pdf" and hasImages:     return "openai/gpt-4o"
  else:                                    return "anthropic/claude-3.5-sonnet"
```

### 5.2 Prompts

Categorization prompt:
```
You are a document classification system. Classify the document into:
- invoice
- marketplace_listing_screenshot
- chat_screenshot
- website_screenshot
- other

Return ONLY valid JSON exactly matching:
{
  "category": "category_name",
  "confidence_score": 0.95,
  "reasoning": "Brief explanation"
}
```

Extraction prompt:
```
Extract key information. Look for:
- Names (people, companies, organizations)
- Places/locations
- Phone numbers
- IP addresses
- Account IDs
- Payment methods (credit cards, bank accounts)
- Merchants
- Dates
- Tables (if present)

For each table, output:
{
  "table_name": "string",
  "headers": ["col1", "col2", ...],
  "rows": [["r1c1", "r1c2", ...], ["r2c1", "r2c2", ...]]
}

Return ONLY JSON exactly matching:
{
  "entities": [ {"type": "name|place|phone|ip_address|account_id|payment_method|merchant", "value": "...", "confidence": 0.0-1.0 }, ...],
  "dates": ["YYYY-MM-DD", ...],
  "tables": [ { "table_name": "...", "headers": [...], "rows": [[...], ...] } ]
}
```

Format-correction prompt (on parse failure):
```
The previous response was not valid JSON per the required schema. Please correct it to match EXACTLY this schema and re-send ONLY the JSON object: { ...schema above... }
```

### 5.3 Error handling and retries
- Retry on: 5XX, 408, 429, and network timeouts.
- Strategy: up to 3 attempts with fixed 1s delay.
- On repeated JSON parse failure: send format-correction prompt once; if still invalid, return 502 with actionable message.

### 5.4 OpenRouter usage
- API reference and quickstart: [OpenRouter Quickstart](https://openrouter.ai/docs/quickstart)
- Use app attribution headers when available (`HTTP-Referer`, `X-Title`).


## 6. Processing Pipeline and Code Organization

### 6.1 Pipeline
1. Validate file type (accept: pdf, png, jpg, jpeg). If unsupported, 415.
2. Compute checksum (SHA-256). Record in DB (MD5-based deduplication is a future enhancement).
3. Persist original bytes and metadata.
4. Determine `hasImages` (heuristics: for PDFs, use PDFBox or Tika to check embedded images; images always true).
5. Select model per section 5.1.
6. Categorize and extract using prompts in 5.2.
7. Validate parsed JSON against expected schema; if invalid, attempt format-correction once.
8. Persist extraction snapshot, entities, and tables.
9. Build response DTO and return.

### 6.2 Backend code layout (Scala)
```
src/main/scala/
  Main.scala
  config/AppConfig.scala
  models/ (FileModels.scala, ExtractionModels.scala, ApiModels.scala)
  services/
    FileService.scala            -- validation, checksum, storage
    LlmService.scala             -- OpenRouter client, retry/format-correction
    ExtractionService.scala      -- orchestration, schema validation
    DatabaseService.scala        -- H2 access (ScalikeJDBC)
  routes/FileRoutes.scala       -- Scalatra endpoints
  utils/{FileUtils.scala, JsonUtils.scala}
```

### 6.3 Frontend code layout (Streamlit)
```
frontend/
  app.py                        -- main UI
  services/api_client.py        -- calls backend
  components/
    file_upload.py              -- uploader with progress
    results_display.py          -- category, entities, tables (HTML) + CSV download
    progress_indicator.py       -- spinners/step indicators
  requirements.txt
```


## 7. UX Details (Streamlit)
- File uploader (single file). Show spinner/progress while awaiting synchronous response.
- Display:
  - Category and confidence score.
  - Entities grouped by type (names, phones, IPs, etc.).
  - Tables rendered as HTML; provide CSV download button (convert JSON rows to CSV client-side).
  - Download original file via backend link.
- Error messages should be specific (validation vs. processing vs. LLM rate limit) with retry suggestions.


## 8. Configuration & Environment
- Backend: Scala 2.13.16, SBT 1.11.4; Scalatra 0.23.x; Circe for JSON; ScalikeJDBC; H2 2.x.
- Frontend: Latest Python 3.x; use `venv` for local reproducibility.
- Secrets: OpenRouter API key via environment variable (e.g., `OPENROUTER_API_KEY`).
- Logging: concise request/response summaries; redact file content and secrets.


## 9. Assumptions & Design Decisions
- Single-user, local prototype; no concurrency requirements.
- No file size limit (documented; may impact performance for very large inputs).
- Synchronous processing (no WebSockets/streaming); future enhancement for progress streaming.
- Store original file bytes in DB for simplicity; future switch to S3 with URL in DB.
- Prefer clarity and simple libraries; minimal abstractions for readability.


## 10. Future Improvements
- Streaming progress updates; background jobs with polling.
- Batch uploads and parallel processing; multi-user support.
- S3 storage and store URLs in database.
- Duplicate detection using MD5/SHA-256 cache to facilitate idempotent processing.
- Advanced validation and typed JSON schema enforcement.
- Model performance telemetry; dynamic model routing per file class.
- Search/filter UI; manual corrections and re-run with feedback.


## 11. Implementation Plan (time-boxed)
1) Backend core (Scalatra + DB + upload + download)
2) LLM integration (selection, prompts, retries, parsing)
3) Streamlit UI (upload, progress, results, CSV)
4) E2E test + docs (README with run instructions, assumptions)


## 12. Dependencies (indicative only)

### Backend (SBT)
```scala
scalaVersion := "2.13.16"

libraryDependencies ++= Seq(
  "org.scalatra" %% "scalatra" % "2.8.4",
  "org.scalatra" %% "scalatra-json" % "2.8.4",
  "org.json4s" %% "json4s-jackson" % "4.0.7",
  "org.eclipse.jetty" % "jetty-server" % "9.4.53.v20231009",
  "org.eclipse.jetty" % "jetty-servlet" % "9.4.53.v20231009",
  "org.eclipse.jetty" % "jetty-webapp" % "9.4.53.v20231009",
  "javax.servlet" % "javax.servlet-api" % "3.1.0",
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  "org.scalikejdbc" %% "scalikejdbc" % "4.0.0",
  "com.h2database" % "h2" % "2.2.224",
  "org.apache.pdfbox" % "pdfbox" % "2.0.29",
  "org.apache.tika" % "tika-core" % "2.9.1"
)
```

### Frontend (requirements.txt)
```
streamlit==1.28.1
requests==2.31.0
python-multipart==0.0.6
pandas==2.1.3
```


## 13. Testing Strategy
- Unit tests: file validation, checksum, schema validation, error mapping.
- Integration tests: upload→LLM→persist→fetch flow (mock OpenRouter for determinism).
- Manual tests: provided sample files (PDF, screenshots) to validate categories/entities/tables.


## 14. References
- OpenRouter Quickstart: https://openrouter.ai/docs/quickstart