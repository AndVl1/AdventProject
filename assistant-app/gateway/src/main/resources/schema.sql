CREATE TABLE IF NOT EXISTS regex_rule (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    pattern     TEXT NOT NULL,
    category    TEXT NOT NULL,            -- API_KEY | PII | CUSTOM
    placeholder TEXT NOT NULL,            -- e.g. REDACTED_API_KEY
    enabled     INTEGER NOT NULL DEFAULT 1,
    builtin     INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts              INTEGER NOT NULL,
    conversation_id TEXT,
    client_ip       TEXT,
    model           TEXT,
    request_text    TEXT,
    redacted_text   TEXT,
    response_text   TEXT,
    status          TEXT NOT NULL,        -- OK | BLOCKED | ERROR | RATE_LIMITED
    block_reason    TEXT,
    input_findings  TEXT,                 -- JSON array of {rule, count}
    output_findings TEXT,
    latency_ms      INTEGER
);

CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts);

CREATE TABLE IF NOT EXISTS redaction_event (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ts              INTEGER NOT NULL,
    conversation_id TEXT,
    direction       TEXT NOT NULL,        -- INPUT | OUTPUT
    rule_name       TEXT NOT NULL,
    placeholder     TEXT NOT NULL,
    original_hash   TEXT NOT NULL         -- SHA-256 prefix, never store raw secret
);

CREATE INDEX IF NOT EXISTS idx_redaction_ts ON redaction_event(ts);

CREATE TABLE IF NOT EXISTS cost_record (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ts                INTEGER NOT NULL,
    conversation_id   TEXT,
    model             TEXT NOT NULL,
    prompt_tokens     INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens      INTEGER NOT NULL,
    cost_usd          REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cost_ts ON cost_record(ts);
