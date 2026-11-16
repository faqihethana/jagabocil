import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import type { Database } from './database.js';

export function sqliteDatabase(path: string): Database {
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS parents(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS children(id TEXT PRIMARY KEY, parent_id TEXT NOT NULL REFERENCES parents(id), name TEXT NOT NULL,
      mode TEXT NOT NULL DEFAULT 'manual', surah_id TEXT NOT NULL DEFAULT '112', min_seconds INTEGER NOT NULL DEFAULT 15,
      unlock_minutes INTEGER NOT NULL DEFAULT 15, tv_brand TEXT NOT NULL DEFAULT 'none', ai_consent INTEGER NOT NULL DEFAULT 0,
      revision INTEGER NOT NULL DEFAULT 0, pair_hash TEXT, pair_expires INTEGER);
    CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, role TEXT NOT NULL, subject_id TEXT NOT NULL, expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS recitations(id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES children(id), surah_id TEXT NOT NULL,
      status TEXT NOT NULL, duration REAL NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, created_at INTEGER NOT NULL,
      expires_at INTEGER, used_at INTEGER, feedback TEXT NOT NULL, reviewer TEXT);
    CREATE INDEX IF NOT EXISTS children_parent ON children(parent_id);
    CREATE INDEX IF NOT EXISTS recitations_child_date ON recitations(child_id, created_at DESC);
    CREATE INDEX IF NOT EXISTS tokens_subject ON tokens(subject_id);
    CREATE UNIQUE INDEX IF NOT EXISTS children_pair ON children(pair_hash) WHERE pair_hash IS NOT NULL;`);
  // Additive migration preserves existing families and keeps their IR configuration.
  const columns = new Set((db.prepare('PRAGMA table_info(children)').all() as { name: string }[]).map(c => c.name));
  db.exec('BEGIN IMMEDIATE');
  try {
    if (!columns.has('tv_transport')) db.exec("ALTER TABLE children ADD COLUMN tv_transport TEXT NOT NULL DEFAULT 'infrared'");
    if (!columns.has('tv_mac')) db.exec("ALTER TABLE children ADD COLUMN tv_mac TEXT NOT NULL DEFAULT ''");
    if (!columns.has('tv_platform')) db.exec("ALTER TABLE children ADD COLUMN tv_platform TEXT NOT NULL DEFAULT 'wol'");
    if (!columns.has('tv_host')) db.exec("ALTER TABLE children ADD COLUMN tv_host TEXT NOT NULL DEFAULT ''");
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
