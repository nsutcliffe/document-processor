# Tunic Pay — Document Categorization & Content Extraction

Prototype system for categorizing uploaded documents and extracting structured content (entities, dates, tables) using LLM/VLM via OpenRouter. Built for clarity, robustness, and responsiveness, aligned with the take-home instructions.

## Quick Start

### Prereqs
- Java 17+
- SBT 1.11.4
- Python 3.10+ (with venv)
- OpenRouter API key

### 1) Set API key (required)
PowerShell:
```powershell
$env:OPENROUTER_API_KEY = "sk-or-..."
```

CMD:
```cmd
set OPENROUTER_API_KEY=sk-or-...
```

MAC/Linux:
```bash
export OPENROUTER_API_KEY=sk-or-...
```

### 2) Start backend
```bash
sbt clean compile
sbt run
```
- Server: http://localhost:8080
- Health check: http://localhost:8080/api/test

### 3) Start frontend
```bash
cd frontend
python setup.py
```
- This should automatically open: http://localhost:8501

## What It Does

- Upload PDF, PNG, JPEG/JPG files
- Categorize into: invoice, marketplace_listing_screenshot, chat_screenshot, website_screenshot, other
- Extract entities, dates, and tables
- Persist all data in local H2 database (file-based)
- Allow user to download original file (to view)
- Show recent processed files in the UI sidebar (select to view results)
- Will not process the same file multiple times (serves up the result from the database)

## Database

H2 file-based database at ./data:
- files(id, filename, file_size, file_type, checksum, file_content, upload_timestamp, processing_status, error_message)
- extractions(id, file_id, category, confidence_score, extracted_data_json, extraction_timestamp, model_used)

We persist original bytes for download, plus structured extraction results.

## How to Test

Backend:
```powershell
sbt test
```

## Design

See:
- design.md — detailed design produced in concert with cursor

Key points to note:
1. Preprocessing: ImageUtils reduces image size and sets low/high detail to avoid provider timeouts or rejections (I observed these with one of the provided files)
2. For convenience / ease of distribution only, I'm using an H2 database to store the data
3. I've decided not to reprocess the same file multiple times, and I'm checking if its the same file using CRC32 - opted for speed, but not really that appropriate for production. I'm not convinced this is the right decision either given the time it takes for the LLM to return, MD5 may have been ok. The UI will present up the cached results if they exist.
4. I am persisting the file in the database so that from the front end the user can download the file to view (at a later point) to see the original source material. In practice this would probably be stored in something like AWS S3 and a link to that data stored in the database instead.


## Known Limitations & Future Work

- Combine categorize+extract to reduce latency (make one LLM call) - high priority (significant performance improvement).
- Explore using faster models (4o-mini/haiku)
- Async processing + polling or streaming progress
- SQL Query optimisation (this can become important at scale)
- Use more robust database
- Investigate using a graph database to store (and explore) discovered relationships?
- On identifying already processed files; switch from CRC32 (fast but too easy for collisions at 32-bit) to e.g. MD5 Hash (much slower but 128-bit)
- S3 storage for binary files rather than directly in database (store URL link in database)
- UI Generally needs quite a bit of work; produced with discussion with cursor, minimal edits done. This includes tables flickering, errors may not clear as quickly as they should, layout / UX, ...
- Entity-level normalization and validation (e.g. normalise phone numbers, emails, address parsing , ...)
- Confidence score from LLM is certainly un-reliable, look into improvements here
- Ensure all free-text is extracted and searchable
- Ensure where there are conversations, these are extracted and parsed into some sensible format, and displayable in the UI.
- More robust handling of failures (e.g. items can get stuck in "processing" if say the server crashed with no way to recover)
- Implement metrics for observability (e.g. how many LLM calls being made, success/failures of LLM (and other REST) calls, ...)
- A few low level TODO's / FIXME's left in code.


## License

Prototype for interview exercise. No license implied.