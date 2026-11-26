import pg from 'pg';
import { neon } from '@neondatabase/serverless';

export type SqlValue = string | number | bigint | null | Uint8Array;
export interface Database {
  kind: 'sqlite' | 'postgres';
  one<T>(sql: string, ...values: SqlValue[]): Promise<T | undefined>;
  all<T>(sql: string, ...values: SqlValue[]): Promise<T[]>;
  change(sql: string, ...values: SqlValue[]): Promise<{ changes: number }>;
  batch(statements: { sql: string; values: SqlValue[] }[]): Promise<void>;
  close(): Promise<void>;
}

export function schemaName(value = 'jagabocil') {
  if (!/^jagabocil(?:_[a-z0-9_]+)?$/.test(value)) throw Error('Database schema must be jagabocil or a jagabocil_ namespace.');
  return `"${value}"`;
}
export function postgresSql(sql: string, schema: string) {
  // Only application-owned, static statements use this adapter. Never interpolate user SQL.
  const qualified = sql.replace(/\b(parents|children|tokens|recitations|billing_orders|generated_books)\b/g, name => `${schemaName(schema)}."${name}"`);
  let index = 0, quoted = false, output = '';
  for (let i = 0; i < qualified.length; i++) {
    const ch = qualified[i]!;
    if (ch === "'" && quoted && qualified[i + 1] === "'") { output += "''"; i++; continue; }
    if (ch === "'") quoted = !quoted;
    output += ch === '?' && !quoted ? `$${++index}` : ch;
  }
  return output;
}

const schemaVersion = 9;

export const migration = (schema: string) => {
  const s = schemaName(schema);
  return [
    `CREATE SCHEMA IF NOT EXISTS ${s}`,
    `CREATE TABLE IF NOT EXISTS ${s}.schema_migrations(version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`,
    `CREATE TABLE IF NOT EXISTS ${s}.parents(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,name TEXT NOT NULL,password TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS ${s}.children(id TEXT PRIMARY KEY,parent_id TEXT NOT NULL REFERENCES ${s}.parents(id),name TEXT NOT NULL,
      mode TEXT NOT NULL DEFAULT 'manual',surah_id TEXT NOT NULL DEFAULT '112',min_seconds INTEGER NOT NULL DEFAULT 15,
      unlock_minutes INTEGER NOT NULL DEFAULT 15,tv_brand TEXT NOT NULL DEFAULT 'none',ai_consent INTEGER NOT NULL DEFAULT 0,
      revision INTEGER NOT NULL DEFAULT 0,pair_hash TEXT,pair_expires BIGINT,
      tv_transport TEXT NOT NULL DEFAULT 'infrared',tv_mac TEXT NOT NULL DEFAULT '',tv_platform TEXT NOT NULL DEFAULT 'wol',tv_host TEXT NOT NULL DEFAULT '')`,
    `CREATE TABLE IF NOT EXISTS ${s}.tokens(hash TEXT PRIMARY KEY,role TEXT NOT NULL,subject_id TEXT NOT NULL,expires BIGINT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS ${s}.recitations(id TEXT PRIMARY KEY,child_id TEXT NOT NULL REFERENCES ${s}.children(id),surah_id TEXT NOT NULL,
      status TEXT NOT NULL,duration DOUBLE PRECISION NOT NULL,path TEXT NOT NULL,mime TEXT NOT NULL,created_at BIGINT NOT NULL,
      expires_at BIGINT,used_at BIGINT,feedback TEXT NOT NULL,reviewer TEXT)`,
    `CREATE INDEX IF NOT EXISTS children_parent ON ${s}.children(parent_id)`,
    `CREATE INDEX IF NOT EXISTS recitations_child_date ON ${s}.recitations(child_id,created_at DESC)`,
    `CREATE INDEX IF NOT EXISTS tokens_subject ON ${s}.tokens(subject_id)`,
    `CREATE INDEX IF NOT EXISTS tokens_expiry ON ${s}.tokens(expires)`,
    `CREATE UNIQUE INDEX IF NOT EXISTS children_pair ON ${s}.children(pair_hash) WHERE pair_hash IS NOT NULL`,
    `INSERT INTO ${s}.schema_migrations(version) VALUES(1) ON CONFLICT DO NOTHING`,
    `DO $migration$
    BEGIN
      LOCK TABLE ${s}.parents IN ACCESS EXCLUSIVE MODE;
      IF EXISTS (
        SELECT FROM information_schema.columns
        WHERE table_schema='${schema}' AND table_name='parents' AND column_name='email'
      ) THEN
        ALTER TABLE ${s}.parents RENAME COLUMN email TO username;
      END IF;
      INSERT INTO ${s}.schema_migrations(version) VALUES(2) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      ALTER TABLE ${s}.parents ADD COLUMN IF NOT EXISTS recovery_hash TEXT;
      ALTER TABLE ${s}.parents ADD COLUMN IF NOT EXISTS auth_revision INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE ${s}.tokens ADD COLUMN IF NOT EXISTS auth_revision INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS ready_at BIGINT;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS reviewed_at BIGINT;
      INSERT INTO ${s}.schema_migrations(version) VALUES(3) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      LOCK TABLE ${s}.parents IN ACCESS EXCLUSIVE MODE;
      CREATE TABLE IF NOT EXISTS ${s}.billing_orders(id TEXT PRIMARY KEY,parent_id TEXT NOT NULL REFERENCES ${s}.parents(id),
        amount INTEGER NOT NULL,created_at BIGINT NOT NULL,status TEXT NOT NULL,checkout_url TEXT,paid_at BIGINT);
      CREATE UNIQUE INDEX IF NOT EXISTS billing_order_open ON ${s}.billing_orders(parent_id) WHERE status='open';
      CREATE INDEX IF NOT EXISTS billing_order_paid ON ${s}.billing_orders(parent_id,paid_at) WHERE status='paid';
      INSERT INTO ${s}.schema_migrations(version) VALUES(4) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS username TEXT;
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS password TEXT;
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS account_revision INTEGER NOT NULL DEFAULT 0;
      CREATE UNIQUE INDEX IF NOT EXISTS children_username ON ${s}.children(username) WHERE username IS NOT NULL;
      INSERT INTO ${s}.schema_migrations(version) VALUES(5) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS book_title TEXT NOT NULL DEFAULT '';
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS book_text TEXT NOT NULL DEFAULT '';
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS reading_kind TEXT NOT NULL DEFAULT 'quran';
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS book_title TEXT;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS reference_text TEXT;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS transcript TEXT;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS match_score INTEGER;
      INSERT INTO ${s}.schema_migrations(version) VALUES(6) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      CREATE TABLE IF NOT EXISTS ${s}.generated_books(
        id TEXT PRIMARY KEY, child_id TEXT NOT NULL REFERENCES ${s}.children(id) ON DELETE CASCADE,
        template_id TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, UNIQUE(child_id,template_id)
      );
      INSERT INTO ${s}.schema_migrations(version) VALUES(7) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS reading_assignment TEXT NOT NULL DEFAULT 'free';
      INSERT INTO ${s}.schema_migrations(version) VALUES(8) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
    `DO $migration$
    BEGIN
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS reading_tasks TEXT NOT NULL DEFAULT '[]';
      ALTER TABLE ${s}.children ADD COLUMN IF NOT EXISTS reading_cycle INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS task_id TEXT;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS task_cycle INTEGER;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS first_ayah INTEGER;
      ALTER TABLE ${s}.recitations ADD COLUMN IF NOT EXISTS last_ayah INTEGER;
      CREATE UNIQUE INDEX IF NOT EXISTS recitations_active_task ON ${s}.recitations(child_id,task_cycle,task_id)
        WHERE task_id IS NOT NULL AND status IN ('uploading','pending','approved');
      INSERT INTO ${s}.schema_migrations(version) VALUES(${schemaVersion}) ON CONFLICT DO NOTHING;
    END;
    $migration$`,
  ];
};

export async function postgresDatabase(url: string, schema = 'jagabocil', migrate = false): Promise<Database> {
  schemaName(schema);
  const parsed = new URL(url);
  if (!['postgres:', 'postgresql:'].includes(parsed.protocol)) throw Error('Invalid database URL protocol.');
  const isNeon = parsed.hostname.endsWith('.neon.tech');
  const http = isNeon ? neon(url, { fullResults: true }) : undefined;
  const pool = isNeon ? undefined : new pg.Pool({ connectionString: url, max: 5, connectionTimeoutMillis: 10_000, idleTimeoutMillis: 30_000 });
  async function query(sql: string, values: SqlValue[] = []) {
    try {
      const result = http
        ? await http.query(sql, values, { fetchOptions: { signal: AbortSignal.timeout(20_000) } })
        : await pool!.query(sql, values);
      const rows = result.rows as Record<string, unknown>[];
      // PG int8 values contain epoch milliseconds and counts, all within JS safe integer range.
      for (const field of result.fields) if (field.dataTypeID === 20) {
        for (const row of rows) if (row[field.name] !== null) {
          const value = Number(row[field.name]);
          if (!Number.isSafeInteger(value)) throw Error('Database integer exceeds safe range.');
          row[field.name] = value;
        }
      }
      return { rows, changes: result.rowCount ?? 0 };
    } catch (error) {
      if ((error as { code?: string }).code === '23505') throw new Error('UNIQUE constraint failed', { cause: error });
      throw error;
    }
  }
  try {
    if (migrate) for (const statement of migration(schema)) await query(statement);
    else if (!(await query(`SELECT version FROM ${schemaName(schema)}.schema_migrations WHERE version=${schemaVersion}`)).rows.length) {
      throw Error('JagaBocil PostgreSQL schema needs migration. Run npm run db:migrate.');
    }
  } catch (error) { await pool?.end(); throw error; }
  return {
    kind: 'postgres',
    async one<T>(sql: string, ...values: SqlValue[]) { return (await query(postgresSql(sql, schema), values)).rows[0] as T | undefined; },
    async all<T>(sql: string, ...values: SqlValue[]) { return (await query(postgresSql(sql, schema), values)).rows as T[]; },
    async change(sql: string, ...values: SqlValue[]) { return { changes: (await query(postgresSql(sql, schema), values)).changes }; },
    async batch(statements) {
      if (http) {
        await http.transaction(statements.map(s => http.query(postgresSql(s.sql, schema), s.values)), { fetchOptions: { signal: AbortSignal.timeout(20_000) } });
        return;
      }
      const client = await pool!.connect();
      try {
        await client.query('BEGIN');
        for (const s of statements) await client.query(postgresSql(s.sql, schema), s.values);
        await client.query('COMMIT');
      } catch (error) { await client.query('ROLLBACK'); throw error; }
      finally { client.release(); }
    },
    async close() { await pool?.end(); },
  };
}
