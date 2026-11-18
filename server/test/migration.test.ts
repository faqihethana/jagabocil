import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomUUID, scryptSync } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';

test('migrations preserve legacy login, families, tokens, and IR settings across restarts', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'jagabocil-legacy-'));
  const parent = randomUUID(), child = randomUUID(), token = 'a'.repeat(43);
  const db = new DatabaseSync(join(dir, 'jagabocil.sqlite'));
  db.exec(`CREATE TABLE parents(id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL);
    CREATE TABLE children(id TEXT PRIMARY KEY,parent_id TEXT NOT NULL REFERENCES parents(id),name TEXT NOT NULL,
      mode TEXT DEFAULT 'manual',surah_id TEXT DEFAULT '112',min_seconds INTEGER DEFAULT 15,unlock_minutes INTEGER DEFAULT 15,
      tv_brand TEXT DEFAULT 'none',ai_consent INTEGER DEFAULT 0,revision INTEGER DEFAULT 0,pair_hash TEXT,pair_expires INTEGER);
    CREATE TABLE tokens(hash TEXT PRIMARY KEY,role TEXT NOT NULL,subject_id TEXT NOT NULL,expires INTEGER NOT NULL);`);
  const oldPassword = 'password-lama';
  const salt = 'legacy-salt';
  const passwordHash = `${salt}:${scryptSync(oldPassword, salt, 64).toString('hex')}`;
  db.prepare('INSERT INTO parents VALUES(?,?,?,?)').run(parent, 'old@example.com', 'Ayah lama', passwordHash);
  db.prepare('INSERT INTO children(id,parent_id,name,tv_brand) VALUES(?,?,?,?)').run(child, parent, 'Adek lama', 'lg');
  db.prepare('INSERT INTO tokens VALUES(?,?,?,?)').run(createHash('sha256').update(token).digest('hex'), 'parent', parent, Date.now() + 86400000);
  db.close();
  try {
    for (let i = 0; i < 2; i++) {
      const app = await createApp({ dataDir: dir });
      try {
        const login = await app.inject({ method: 'POST', url: '/auth/login', payload: { username: 'old@example.com', password: oldPassword } });
        assert.equal(login.statusCode, 200);
        assert.equal(login.json().name, 'Ayah lama');
        const response = await app.inject({ method: 'GET', url: '/dashboard', headers: { authorization: `Bearer ${token}` } });
        assert.equal(response.statusCode, 200);
        const c = response.json().children[0];
        assert.equal(c.id, child); assert.equal(c.name, 'Adek lama');
        assert.equal(c.tvBrand, 'lg'); assert.equal(c.tvTransport, 'infrared');
        assert.equal(c.tvMac, ''); assert.equal(c.tvHost, ''); assert.equal(c.tvPlatform, 'wol');
      } finally { await app.close(); }
    }
  } finally { await rm(dir, { recursive: true, force: true }); }
});
