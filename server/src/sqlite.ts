import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import type { Database } from './database.js';

export function sqliteDatabase(path: string): Database {
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS parents(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, parent_id TEXT NOT NULL REFERENCES parents(id), name TEXT NOT NULL,
      mode TEXT NOT NULL DEFAULT 'manual', surah_id TEXT NOT NULL DEFAULT '112', min_seconds INTEGER NOT NULL DEFAULT 15,
      unlock_minutes INTEGER NOT NULL DEFAULT 15, tv_brand TEXT NOT NULL DEFAULT 'none', ai_consent INTEGER NOT NULL DEFAULT 0,
      revision INTEGER NOT NULL DEFAULT 0, pair_hash TEXT, pair_expires INTEGER);
    CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, role TEXT NOT NULL, subject_id TEXT NOT NULL, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS recitations(id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES children(id), surah_id TEXT NOT NULL,
      status TEXT NOT NULL, duration REAL NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, created_at INTEGER NOT NULL,
      expires_at INTEGER, used_at INTEGER, feedback TEXT NOT NULL, reviewer TEXT);
    CREATE TABLE IF NOT EXISTS billing_orders(id TEXT PRIMARY KEY,parent_id TEXT NOT NULL REFERENCES parents(id),
      amount INTEGER NOT NULL,created_at INTEGER NOT NULL,status TEXT NOT NULL,checkout_url TEXT,paid_at INTEGER);
    CREATE TABLE IF NOT EXISTS generated_books(
      id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
      template_id TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, UNIQUE(child_id,template_id)
    );
    CREATE UNIQUE INDEX IF NOT EXISTS billing_order_open ON billing_orders(parent_id) WHERE status='open';
    CREATE INDEX IF NOT EXISTS billing_order_paid ON billing_orders(parent_id,paid_at) WHERE status='paid';
    CREATE INDEX IF NOT EXISTS children_parent ON children(parent_id);
    CREATE INDEX IF NOT EXISTS recitations_child_date ON recitations(child_id, created_at DESC);
    CREATE INDEX IF NOT EXISTS tokens_subject ON tokens(subject_id);
    CREATE UNIQUE INDEX IF NOT EXISTS children_pair ON children(pair_hash) WHERE pair_hash IS NOT NULL;`);
  db.exec('BEGIN IMMEDIATE');
  try {
    const accountColumns = db.prepare('PRAGMA table_info(parents)').all();
    if (accountColumns.some(column => column.name === 'email')) db.exec('ALTER TABLE parents RENAME COLUMN email TO username');
    if (!accountColumns.some(column => column.name === 'recovery_hash')) db.exec('ALTER TABLE parents ADD COLUMN recovery_hash TEXT');
    if (!accountColumns.some(column => column.name === 'auth_revision')) db.exec('ALTER TABLE parents ADD COLUMN auth_revision INTEGER NOT NULL DEFAULT 0');
    const tokenColumns = db.prepare('PRAGMA table_info(tokens)').all();
    if (!tokenColumns.some(column => column.name === 'auth_revision')) db.exec('ALTER TABLE tokens ADD COLUMN auth_revision INTEGER NOT NULL DEFAULT 0');
    const recordingColumns = new Set(db.prepare('PRAGMA table_info(recitations)').all().map(column => column.name));
    if (!recordingColumns.has('ready_at')) db.exec('ALTER TABLE recitations ADD COLUMN ready_at INTEGER');
    if (!recordingColumns.has('reviewed_at')) db.exec('ALTER TABLE recitations ADD COLUMN reviewed_at INTEGER');
    if (!recordingColumns.has('reading_kind')) db.exec("ALTER TABLE recitations ADD COLUMN reading_kind TEXT NOT NULL DEFAULT 'quran'");
    if (!recordingColumns.has('book_title')) db.exec('ALTER TABLE recitations ADD COLUMN book_title TEXT');
    if (!recordingColumns.has('reference_text')) db.exec('ALTER TABLE recitations ADD COLUMN reference_text TEXT');
    if (!recordingColumns.has('transcript')) db.exec('ALTER TABLE recitations ADD COLUMN transcript TEXT');
    if (!recordingColumns.has('match_score')) db.exec('ALTER TABLE recitations ADD COLUMN match_score INTEGER');
    if (!recordingColumns.has('task_id')) db.exec('ALTER TABLE recitations ADD COLUMN task_id TEXT');
    if (!recordingColumns.has('task_cycle')) db.exec('ALTER TABLE recitations ADD COLUMN task_cycle INTEGER');
    if (!recordingColumns.has('first_ayah')) db.exec('ALTER TABLE recitations ADD COLUMN first_ayah INTEGER');
    if (!recordingColumns.has('last_ayah')) db.exec('ALTER TABLE recitations ADD COLUMN last_ayah INTEGER');
    db.exec(`CREATE UNIQUE INDEX IF NOT EXISTS recitations_active_task ON recitations(child_id,task_cycle,task_id)
      WHERE task_id IS NOT NULL AND status IN ('uploading','pending','approved')`);
    const columns = new Set(db.prepare('PRAGMA table_info(children)').all().map(column => column.name));
    if (!columns.has('tv_transport')) db.exec("ALTER TABLE children ADD COLUMN tv_transport TEXT NOT NULL DEFAULT 'infrared'");
    if (!columns.has('tv_mac')) db.exec("ALTER TABLE children ADD COLUMN tv_mac TEXT NOT NULL DEFAULT ''");
    if (!columns.has('tv_platform')) db.exec("ALTER TABLE children ADD COLUMN tv_platform TEXT NOT NULL DEFAULT 'wol'");
    if (!columns.has('tv_host')) db.exec("ALTER TABLE children ADD COLUMN tv_host TEXT NOT NULL DEFAULT ''");
    if (!columns.has('username')) db.exec('ALTER TABLE children ADD COLUMN username TEXT');
    if (!columns.has('password')) db.exec('ALTER TABLE children ADD COLUMN password TEXT');
    if (!columns.has('account_revision')) db.exec('ALTER TABLE children ADD COLUMN account_revision INTEGER NOT NULL DEFAULT 0');
    if (!columns.has('book_title')) db.exec("ALTER TABLE children ADD COLUMN book_title TEXT NOT NULL DEFAULT ''");
    if (!columns.has('book_text')) db.exec("ALTER TABLE children ADD COLUMN book_text TEXT NOT NULL DEFAULT ''");
    if (!columns.has('reading_assignment')) db.exec("ALTER TABLE children ADD COLUMN reading_assignment TEXT NOT NULL DEFAULT 'free'");
    if (!columns.has('reading_tasks')) db.exec("ALTER TABLE children ADD COLUMN reading_tasks TEXT NOT NULL DEFAULT '[]'");
    if (!columns.has('reading_cycle')) db.exec('ALTER TABLE children ADD COLUMN reading_cycle INTEGER NOT NULL DEFAULT 0');
    db.exec('CREATE UNIQUE INDEX IF NOT EXISTS children_username ON children(username) WHERE username IS NOT NULL');
    db.exec('COMMIT');
  } catch (error) { db.exec('ROLLBACK'); db.close(); throw error; }

  return {
    kind: 'sqlite',
    async one<T>(sql: string, ...args: SQLInputValue[]) { return db.prepare(sql).get(...args) as T | undefined; },
    async all<T>(sql: string, ...args: SQLInputValue[]) { return db.prepare(sql).all(...args) as T[]; },
    async change(sql: string, ...args: SQLInputValue[]) { return { changes: Number(db.prepare(sql).run(...args).changes) }; },
    async batch(statements) {
      db.exec('BEGIN IMMEDIATE');
      try {
        for (const s of statements) db.prepare(s.sql).run(...s.values);
        db.exec('COMMIT');
      } catch (error) { db.exec('ROLLBACK'); throw error; }
    },
    async close() { db.close(); },
  };
}
