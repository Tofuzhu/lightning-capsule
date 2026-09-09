-- Lightning Capsule — Phase 1 schema
CREATE TABLE IF NOT EXISTS capsules (
  id TEXT PRIMARY KEY,
  content TEXT NOT NULL,
  raw_transcript TEXT,
  audio_url TEXT,
  source TEXT DEFAULT 'web',
  tags TEXT,
  status TEXT DEFAULT 'pending',
  version INTEGER DEFAULT 1,
  checksum TEXT,
  export_id TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  synced_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_capsules_status ON capsules(status);
CREATE INDEX IF NOT EXISTS idx_capsules_created ON capsules(created_at);
-- checksum lookup drives text-upload idempotency (see POST /api/capture)
CREATE INDEX IF NOT EXISTS idx_capsules_checksum ON capsules(checksum);
-- export_id lookup drives POST /api/ack
CREATE INDEX IF NOT EXISTS idx_capsules_export_id ON capsules(export_id);
