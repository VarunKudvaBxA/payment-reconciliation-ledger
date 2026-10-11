# Payment Reconciliation Ledger

A double entry ledger plus a reconciliation engine. You post payments into the ledger, upload a
bank statement as csv, and the system matches the statement lines to the ledger entries. Lines
that cannot be matched go to an exceptions queue, where an LLM suggests what probably happened
and a person confirms.

Reconciliation is the boring job every payments company has to do and almost nobody builds a
side project around it. I wanted to practise two things that matter there: getting money
movement right under concurrency, and matching messy real world data.

Stack: Java 17, Spring Boot 3, plain JDBC, PostgreSQL 16 with pgvector, Flyway, React (Vite), Recharts, Docker, Testcontainers.

## What it does

**Ledger**
- Every payment is a journal entry with debit and credit lines that must add up to the same amount.
  This is checked in Java and again by a deferred constraint trigger in Postgres.
- Ledger lines cannot be updated or deleted (trigger). A correction is a new entry.
- Money moves with row locks. All accounts in an entry are locked with `select ... for update` in
  ascending id order, so two transfers can never deadlock each other, and balances are checked
  after the lock so two transfers cannot spend the same money.
- Requests accept an `Idempotency-Key` header. Sending the same key again returns the original
  entry instead of posting twice, and a unique index covers the case where two copies arrive at
  the same instant.

**Reconciliation**
- Each statement line goes through three passes, cheapest first:
  1. exact: same amount and same reference
  2. fuzzy: same amount, date within 3 days, description similarity (Jaro-Winkler) above 0.80
  3. semantic: amount within 1 percent, date within 5 days, nearest description by cosine
     distance using pgvector
- A ledger entry can only be matched once (unique index on `matches.entry_id`).
- Checks that raise flags: duplicate lines, amounts far from the batch average (z-score above 3),
  matched pairs where the amounts differ (fees), and ledger entries the bank never showed.
- The exception assistant sends the unmatched line, its flags and the closest open ledger entries
  to the Claude API and asks for a likely cause and next step as JSON. It may only point at entry
  ids it was given. With no API key it falls back to simple rules.

## Embeddings, honestly

The default embedder is local and needs no key: it hashes character trigrams and words into 256
dimensions. It handles abbreviations, reordering and noise, but it does not understand meaning.
For real semantic matching set `EMBEDDING_MODE=openai` and `EMBEDDING_API_KEY`, and it will call an
OpenAI compatible embeddings endpoint asking for 256 dimensions (the column is `vector(256)`).

## Run it with Docker

```bash
cp .env.example .env
docker compose up --build
```

Open http://localhost:3000, go to Bank statements and press "Load demo data". That creates 150
ledger entries, builds a deliberately messy statement for them, uploads it, then press "Run matching".

The demo statement has some lines that match exactly, some with noisy descriptions and a late
date, some a few days late with a fee taken off, some that never arrive at the bank, a few that
exist only at the bank, two duplicates and one huge wire.

## Run it without Docker

```bash
# database, must be the pgvector image
docker run -d --name recon-db -e POSTGRES_DB=recon -e POSTGRES_USER=recon \
  -e POSTGRES_PASSWORD=recon -p 5432:5432 pgvector/pgvector:pg16

cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev     # http://localhost:5173
```

## Tests

```bash
cd backend && mvn test      # needs Docker running, Testcontainers starts a pgvector Postgres
```

`LedgerConcurrencyTest` fires 1000 transfers from 32 threads between 4 wallets and then checks:
no deadlock, total wallet money unchanged, debits equal credits across all lines, no wallet below
zero, and each cached balance equals the sum of its ledger lines. A second test checks that
the same idempotency key posts only once.

## API

| method | path                                  | what it does                               |
|--------|---------------------------------------|--------------------------------------------|
| GET    | /api/accounts                         | accounts and balances                      |
| GET    | /api/entries?limit=30                 | latest entries with their lines            |
| POST   | /api/deposits                         | bank to wallet                             |
| POST   | /api/withdrawals                      | wallet to bank                             |
| POST   | /api/transfers                        | wallet to wallet                           |
| POST   | /api/statements                       | upload csv (multipart field `file`)        |
| GET    | /api/statements                       | list uploads with counts                   |
| POST   | /api/statements/{id}/reconcile        | run the three passes and the anomaly scan  |
| GET    | /api/statements/{id}/lines            | lines with their match, if any             |
| GET    | /api/exceptions                       | unmatched lines and missing settlements    |
| POST   | /api/exceptions/{lineId}/suggest      | cause and next step for one line           |
| POST   | /api/matches/manual                   | match a line to an entry by hand           |
| POST   | /api/exceptions/{lineId}/ignore       | drop a line from the queue                 |
| GET    | /api/dashboard                        | totals for the overview page               |
| POST   | /api/demo/seed                        | make fake ledger entries                   |
| GET    | /api/demo/statement.csv               | matching messy bank statement              |

Example:

```bash
curl -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: abc-123' \
  -d '{"from":"WALLET_ALICE","to":"WALLET_BOB","amount":25.00,"description":"lunch"}'
```

## Design notes

- **Why a cached balance table?** Reading a balance from the lines gets slower as the ledger grows,
  and locking something is needed anyway. `account_balances` is the row we lock and the test proves
  it never drifts from the lines.
- **Why JDBC and not JPA?** The locking order and the exact SQL are the point of this project. I wanted
  nothing hidden between me and the queries.
- **Why pgvector and not a separate vector database?** The candidates are already in Postgres and the
  search is filtered by amount and date first, so one database is simpler and it stays transactional.
- **Why is the amount required in every pass?** Money is the one thing that rarely changes, so
  it keeps the looser description matching from producing wrong matches.

## Known gaps

- No authentication. Anything that moves money would need it.
- The idempotency key is not compared with the request body. The same key with a different amount
  just returns the first entry.
- One currency (USD) only.
- Matching is greedy, line by line. A global assignment (Hungarian algorithm) would do better on
  crowded days with many equal amounts.
- Spring Batch would be the natural choice for very large statement files. A 10 MB csv is read in one go here.
- The local embedder is lexical, see above.

## Layout

```
backend/    Spring Boot API, SQL migrations, tests
frontend/   React app
```
