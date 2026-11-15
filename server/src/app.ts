import Fastify, { type FastifyRequest } from 'fastify';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import { randomBytes, randomInt, randomUUID, createHash, scrypt, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { z } from 'zod';
import { surahs } from './catalog.js';
import { passes, verdictSchema, type Verify } from './ai.js';

const run = promisify(execFile);
const digest = (s: string) => createHash('sha256').update(s).digest('hex');
const day = 86_400_000;
type Actor = { role: 'parent' | 'child'; id: string };
type Child = { id: string; parent_id: string; name: string; mode: string; surah_id: string; min_seconds: number; unlock_minutes: number; tv_brand: string; ai_consent: number; revision: number; pair_hash: string | null; pair_expires: number | null };
type Recitation = { id: string; child_id: string; surah_id: string; status: string; duration: number; path: string; mime: string; created_at: number; expires_at: number | null; used_at: number | null; feedback: string; reviewer: string | null };
type Options = { dataDir: string; verify?: Verify; logger?: boolean; now?: () => number };
class HttpError extends Error { constructor(public statusCode: number, message: string) { super(message); } }
const fail = (code: number, message: string): never => { throw new HttpError(code, message); };

async function passwordHash(password: string, salt = randomBytes(16).toString('hex')) {
  const key = await new Promise<Buffer>((ok, no) => scrypt(password, salt, 64, (e, k) => e ? no(e) : ok(k)));
  return `${salt}:${key.toString('hex')}`;
}
async function passwordMatches(password: string, stored: string) {
  const hash = await passwordHash(password, stored.split(':')[0]);
  return timingSafeEqual(Buffer.from(hash), Buffer.from(stored));
}

export async function createApp(options: Options) {
  const now = options.now ?? Date.now;
  const dataDir = resolve(options.dataDir);
  await mkdir(join(dataDir, 'recordings'), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(join(dataDir, 'jagabocil.sqlite'));
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
  const one = <T>(sql: string, ...args: SQLInputValue[]) => db.prepare(sql).get(...args) as T | undefined;
  const all = <T>(sql: string, ...args: SQLInputValue[]) => db.prepare(sql).all(...args) as T[];
  const change = (sql: string, ...args: SQLInputValue[]) => db.prepare(sql).run(...args);
  const app = Fastify({ logger: options.logger ?? false, bodyLimit: 11 * 1024 * 1024, requestTimeout: 65_000 });
  await app.register(multipart, { limits: { fileSize: 10 * 1024 * 1024, files: 1, fields: 1, parts: 2 } });
  await app.register(rateLimit, { max: 180, timeWindow: '1 minute' });
  app.setErrorHandler((error, _req, reply) => {
    if (error instanceof z.ZodError) return reply.code(400).send({ message: 'Data belum sesuai. Periksa isianmu.' });
    const status = (error as HttpError).statusCode ?? 500;
    if (status >= 500) app.log.error(error);
    reply.code(status).send({ message: status >= 500 ? 'Server sedang bermasalah. Coba lagi sebentar.' : (error as Error).message });
  });
  app.addHook('onSend', async (_req, reply) => { reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff'); });
  async function actor(req: FastifyRequest, required?: Actor['role']): Promise<Actor> {
    const raw = req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    const token = raw ? one<{ role: Actor['role']; subject_id: string }>('SELECT * FROM tokens WHERE hash=? AND expires>?', digest(raw), now()) : undefined;
    if (!token) return fail(401, 'Sesi habis. Silakan masuk lagi.');
    if (required && token.role !== required) return fail(403, 'Hanya orang tua yang dapat mengubah ini.');
    return { role: token.role, id: token.subject_id };
  }
  function newToken(role: Actor['role'], id: string) {
    const token = randomBytes(32).toString('base64url');
    change('INSERT INTO tokens VALUES(?,?,?,?)', digest(token), role, id, now() + 30 * day);
    return token;
  }
  function childFor(a: Actor, id: string) {
    const child = one<Child>('SELECT * FROM children WHERE id=?', id);
    if (!child || (a.role === 'parent' ? child.parent_id !== a.id : child.id !== a.id)) return fail(404, 'Profil anak tidak ditemukan.');
    return child;
  }
  function recFor(a: Actor, id: string) {
    const rec = one<Recitation>('SELECT * FROM recitations WHERE id=?', id);
    if (!rec) return fail(404, 'Rekaman tidak ditemukan.');
    childFor(a, rec.child_id);
    return rec;
  }
  const paramId = (req: FastifyRequest) => z.object({ id: z.string().uuid() }).parse(req.params).id;
  const publicChild = (c: Child) => ({ id: c.id, name: c.name, mode: c.mode, surahId: c.surah_id, minSeconds: c.min_seconds, unlockMinutes: c.unlock_minutes, tvBrand: c.tv_brand, aiConsent: c.ai_consent === 1 });
  const publicRec = (r: Recitation) => ({ id: r.id, childId: r.child_id, surahId: r.surah_id, status: r.status, duration: r.duration, createdAt: r.created_at, expiresAt: r.expires_at, usedAt: r.used_at, feedback: r.feedback, reviewer: r.reviewer });
  function pairing(id: string) {
    let code: string;
    do { code = String(randomInt(100_000, 1_000_000)); } while (one('SELECT id FROM children WHERE pair_hash=?', digest(code)));
    change('UPDATE children SET pair_hash=?,pair_expires=? WHERE id=?', digest(code), now() + 600_000, id);
    return { code, expiresAt: now() + 600_000 };
  }
  async function cleanup() {
    for (const r of all<Recitation>('SELECT * FROM recitations WHERE created_at<?', now() - 7 * day)) {
      await unlink(r.path).catch(() => {});
      change('DELETE FROM recitations WHERE id=?', r.id);
    }
    change('DELETE FROM tokens WHERE expires<=?', now());
    change('UPDATE children SET pair_hash=NULL,pair_expires=NULL WHERE pair_expires<=?', now());
  }
  await cleanup();
  const cleanupTimer = setInterval(() => { void cleanup().catch(e => app.log.error(e)); }, 3_600_000);
  cleanupTimer.unref();
  app.addHook('onClose', async () => { clearInterval(cleanupTimer); db.close(); });

  app.get('/health', async () => ({ ok: true, aiAvailable: !!options.verify, surahs }));
  const credentials = z.object({ email: z.email().max(254).transform(s => s.toLowerCase()), password: z.string().min(10).max(128) });
  app.post('/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    const data = credentials.extend({ name: z.string().trim().min(1).max(60) }).parse(req.body);
    const hashed = await passwordHash(data.password);
    const id = randomUUID();
    try { change('INSERT INTO parents VALUES(?,?,?,?)', id, data.email, data.name, hashed); }
    catch (e) { if ((e as Error).message.includes('UNIQUE')) return fail(409, 'Email sudah terdaftar. Silakan masuk.'); throw e; }
    return reply.code(201).send({ token: newToken('parent', id), role: 'parent', name: data.name });
  });
  app.post('/auth/login', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const data = credentials.parse(req.body);
    const p = one<{ id: string; name: string; password: string }>('SELECT * FROM parents WHERE email=?', data.email);
    // Run scrypt for unknown accounts too.
    const valid = p ? await passwordMatches(data.password, p.password) : (await passwordHash(data.password), false);
    if (!p || !valid) return fail(401, 'Email atau kata sandi kurang tepat.');
    return { token: newToken('parent', p.id), role: 'parent', name: p.name };
  });
  app.post('/auth/logout', async req => {
    await actor(req);
    change('DELETE FROM tokens WHERE hash=?', digest(req.headers.authorization!.slice(7)));
    return { ok: true };
  });
  app.post('/children', async (req, reply) => {
    const a = await actor(req, 'parent');
    const { name } = z.object({ name: z.string().trim().min(1).max(40) }).parse(req.body);
    if (one<{ n: number }>('SELECT COUNT(*) n FROM children WHERE parent_id=?', a.id)!.n >= 10) return fail(400, 'Maksimal 10 profil anak.');
    const id = randomUUID();
    change('INSERT INTO children(id,parent_id,name) VALUES(?,?,?)', id, a.id, name);
    return reply.code(201).send({ child: publicChild(childFor(a, id)), pairing: pairing(id) });
  });
  app.post('/children/:id/pairing', async req => { const a = await actor(req, 'parent'); const c = childFor(a, paramId(req)); return { pairing: pairing(c.id) }; });
  app.post('/pair', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const { code } = z.object({ code: z.string().regex(/^\d{6}$/) }).parse(req.body);
    const c = one<Child>('SELECT * FROM children WHERE pair_hash=? AND pair_expires>?', digest(code), now());
    if (!c) return fail(400, 'Kode salah atau kedaluwarsa. Minta kode baru ke orang tua.');
    change('UPDATE children SET pair_hash=NULL,pair_expires=NULL WHERE id=?', c.id);
    return { token: newToken('child', c.id), role: 'child', name: c.name };
  });
  app.delete('/children/:id/devices', async req => {
    const a = await actor(req, 'parent'); const c = childFor(a, paramId(req));
    change('DELETE FROM tokens WHERE subject_id=?', c.id);
    change('UPDATE children SET pair_hash=NULL,pair_expires=NULL,revision=revision+1 WHERE id=?', c.id);
    change("UPDATE recitations SET status='rejected',feedback='Izin dicabut orang tua.' WHERE child_id=? AND used_at IS NULL", c.id);
    return { ok: true };
  });
  app.put('/children/:id/policy', async req => {
    const a = await actor(req, 'parent'); const c = childFor(a, paramId(req));
    const p = z.object({ mode: z.enum(['manual', 'ai']), surahId: z.enum(['112', '113', '114']), minSeconds: z.number().int().min(10).max(120), unlockMinutes: z.number().int().min(1).max(60), tvBrand: z.enum(['none', 'samsung', 'lg']), aiConsent: z.boolean() }).parse(req.body);
    if (p.mode === 'ai' && (!p.aiConsent || !options.verify)) return fail(400, 'AI perlu persetujuan orang tua dan konfigurasi server.');
    change('UPDATE children SET mode=?,surah_id=?,min_seconds=?,unlock_minutes=?,tv_brand=?,ai_consent=?,revision=revision+1 WHERE id=?', p.mode, p.surahId, p.minSeconds, p.unlockMinutes, p.tvBrand, Number(p.aiConsent), c.id);
    // A changed assignment requires a fresh recording; never reuse an old approval.
    change("UPDATE recitations SET status='rejected',feedback='Aturan diperbarui. Yuk, rekam ngaji lagi.' WHERE child_id=? AND used_at IS NULL", c.id);
    return { child: publicChild(childFor(a, c.id)) };
  });
  app.get('/dashboard', async req => {
    const a = await actor(req);
    const children = a.role === 'parent' ? all<Child>('SELECT * FROM children WHERE parent_id=? ORDER BY rowid', a.id) : [childFor(a, a.id)];
    const recs = a.role === 'parent'
      ? all<Recitation>('SELECT r.* FROM recitations r JOIN children c ON c.id=r.child_id WHERE c.parent_id=? ORDER BY r.created_at DESC LIMIT 100', a.id)
      : all<Recitation>('SELECT * FROM recitations WHERE child_id=? ORDER BY created_at DESC LIMIT 50', a.id);
    const name = a.role === 'parent' ? one<{ name: string }>('SELECT name FROM parents WHERE id=?', a.id)!.name : children[0]!.name;
    return { role: a.role, name, aiAvailable: !!options.verify, children: children.map(publicChild), recitations: recs.map(publicRec), surahs, serverTime: now() };
  });
  app.post('/recitations', { config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const a = await actor(req, 'child'); const c = childFor(a, a.id);
    if (one<{ n: number }>("SELECT COUNT(*) n FROM recitations WHERE child_id=? AND status='pending'", c.id)!.n >= 5) return fail(429, 'Masih ada 5 rekaman menunggu. Tunggu orang tua memeriksa dulu.');
    let buffer: Buffer | undefined; let surahId: string | undefined;
    for await (const part of req.parts()) {
      if (part.type === 'file') { buffer = await part.toBuffer(); if (part.file.truncated) return fail(413, 'Rekaman maksimal 10 MB.'); }
      else if (part.fieldname === 'surahId') surahId = String(part.value);
    }
    if (!buffer?.length || surahId !== c.surah_id) return fail(400, 'Kirim rekaman sesuai surah tugasmu.');
    const id = randomUUID(); const path = join(dataDir, 'recordings', `${id}.audio`);
    await writeFile(path, buffer, { mode: 0o600 });
    let duration: number; let mime: string;
    try {
      const { stdout } = await run('ffprobe', ['-v', 'error', '-protocol_whitelist', 'file,pipe', '-format_whitelist', 'mov,wav', '-show_entries', 'format=duration,format_name:stream=codec_type', '-of', 'json', path], { timeout: 10_000, maxBuffer: 64_000 });
      const meta = JSON.parse(stdout);
      duration = Number(meta.format?.duration);
      if (!Number.isFinite(duration) || duration < c.min_seconds || duration > 180 || !meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'audio') || meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'video')) throw new Error('invalid audio');
      mime = meta.format.format_name === 'wav' ? 'audio/wav' : 'audio/mp4';
    } catch {
      await unlink(path).catch(() => {});
      return fail(400, `Gunakan rekaman audio M4A/WAV sepanjang ${c.min_seconds}–180 detik.`);
    }
    if (childFor(a, a.id).revision !== c.revision) {
      await unlink(path).catch(() => {});
      return fail(409, 'Aturan atau perangkat diperbarui. Muat ulang dan rekam bacaan baru.');
    }
    change('INSERT INTO recitations(id,child_id,surah_id,status,duration,path,mime,created_at,feedback) VALUES(?,?,?,?,?,?,?,?,?)', id, c.id, surahId, 'pending', duration, path, mime, now(), 'Menunggu orang tua mendengarkan.');
    if (c.mode === 'ai' && c.ai_consent && options.verify) {
      try {
        const verdict = verdictSchema.parse(await options.verify(buffer, mime, surahId));
        const approved = passes(verdict);
        change("UPDATE recitations SET status=?,expires_at=?,feedback=?,reviewer='ai' WHERE id=? AND status='pending' AND reviewer IS NULL", approved ? 'approved' : 'pending', approved ? now() + c.unlock_minutes * 60_000 : null, approved ? verdict.feedback : `Perlu pemeriksaan orang tua. ${verdict.feedback}`, id);
      } catch {
        change("UPDATE recitations SET feedback='AI belum bisa memeriksa. Rekaman diteruskan ke orang tua.' WHERE id=? AND status='pending' AND reviewer IS NULL", id);
      }
    }
    return reply.code(201).send({ recitation: publicRec(one<Recitation>('SELECT * FROM recitations WHERE id=?', id)!) });
  });
  app.get('/recitations/:id/audio', async (req, reply) => {
    const a = await actor(req); const r = recFor(a, paramId(req));
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    return reply.type(r.mime).send(await readFile(r.path));
  });
  app.post('/recitations/:id/review', async req => {
    const a = await actor(req, 'parent'); const r = recFor(a, paramId(req)); const c = childFor(a, r.child_id);
    const { decision } = z.object({ decision: z.enum(['approve', 'reject']) }).parse(req.body);
    const approved = decision === 'approve';
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    const updated = change("UPDATE recitations SET status=?,expires_at=?,reviewer='parent',feedback=? WHERE id=? AND status='pending' AND used_at IS NULL", approved ? 'approved' : 'rejected', approved ? now() + c.unlock_minutes * 60_000 : null, approved ? 'Disetujui orang tua. Terima kasih sudah mengaji!' : 'Yuk, coba mengaji lagi. Orang tua ingin mendengar lebih jelas.', r.id);
    if (!updated.changes) return fail(409, 'Rekaman ini sudah diperiksa.');
    return { recitation: publicRec(one<Recitation>('SELECT * FROM recitations WHERE id=?', r.id)!) };
  });
  app.delete('/recitations/:id', async req => {
    const a = await actor(req, 'parent'); const r = recFor(a, paramId(req));
    await unlink(r.path).catch(() => {});
    change('DELETE FROM recitations WHERE id=?', r.id);
    return { ok: true };
  });
  app.post('/tv/authorize', async req => {
    const a = await actor(req, 'child');
    const { recitationId } = z.object({ recitationId: z.string().uuid() }).parse(req.body);
    const r = recFor(a, recitationId); const c = childFor(a, a.id);
    if (c.tv_brand === 'none') return fail(400, 'Minta orang tua memilih profil TV dulu.');
    const used = change("UPDATE recitations SET used_at=? WHERE id=? AND child_id=? AND status='approved' AND used_at IS NULL AND expires_at>? AND created_at>?", now(), r.id, c.id, now(), now() - 7 * day);
    if (!used.changes) return fail(403, 'Ngaji perlu disetujui dulu. Izin hanya berlaku sekali dan bisa kedaluwarsa.');
    return { authorized: true, tvBrand: c.tv_brand, expiresAt: now() + 10_000 };
  });
  return app;
}
