import Fastify, { type FastifyRequest } from 'fastify';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import type { Database } from './database.js';
import { sqliteDatabase } from './sqlite.js';
import { localRecordings, type RecordingStore } from './recordings.js';
import { randomBytes, randomInt, randomUUID, createHash, scrypt, timingSafeEqual } from 'node:crypto';
import { mkdir, writeFile, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { z } from 'zod';
import { isTaskSurah, legacySurahs, quranNotice, quranText, taskRecordingLimit } from './catalog.js';
import type { Transcribe } from './ai.js';
import { bookMatchScore, bookMatchThreshold, maxAiReadingSeconds, readingWords } from './book-reading.js';
import { bookTemplates, storySchema, type WriteStory } from './book-library.js';
import { billing } from './billing.js';
import type { Midtrans } from './midtrans.js';

const run = promisify(execFile);
const digest = (s: string) => createHash('sha256').update(s).digest('hex');
const day = 86_400_000;
const sessionDuration = 30 * day;
const pairingDuration = 10 * 60_000;
const setupDuration = 10 * 60_000;
const tvSettings = z.object({
  tvBrand: z.enum(['none', 'samsung', 'lg']),
  tvTransport: z.enum(['infrared', 'wifi']).default('infrared'),
  tvPlatform: z.enum(['wol', 'androidtv', 'samsung', 'lg']).default('wol'),
  tvHost: z.string().trim().max(15).default(''),
  tvMac: z.string().trim().toUpperCase().regex(/^([0-9A-F]{2}:){5}[0-9A-F]{2}$|^$/).default(''),
});
function isPrivateIpv4(host: string) {
  if (!/^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}$/.test(host)) return false;
  const [a, b, c, d] = host.split('.').map(Number);
  return [a,b,c,d].every(x => x !== undefined && x <= 255) && (a === 10 || a === 192 && b === 168 || a === 172 && b! >= 16 && b! <= 31);
}
type TokenRole = 'parent' | 'child' | 'child_account';
type Actor = { role: 'parent' | 'child'; accountLogin: boolean; id: string; authRevision: number; tokenHash: string; expires: number };
type Child = { id: string; parent_id: string; name: string; username: string | null; mode: string; surah_id: string; min_seconds: number; unlock_minutes: number; tv_brand: string; tv_transport: string; tv_mac: string; tv_platform: string; tv_host: string; ai_consent: number; revision: number; pair_hash: string | null; pair_expires: number | null; book_title: string; book_text: string; reading_assignment: 'free' | 'quran' | 'book' };
type Recitation = { id: string; child_id: string; surah_id: string; status: string; duration: number; path: string; mime: string; created_at: number; ready_at: number | null; reviewed_at: number | null; expires_at: number | null; used_at: number | null; feedback: string; reviewer: string | null; reading_kind: 'quran' | 'book'; book_title: string | null; reference_text: string | null; transcript: string | null; match_score: number | null; transcript_available?: boolean | number };
// Values 1 and 2 covered sending audio without keeping transcripts.
const aiConsentByProvider = { gemini: 3, deepseek: 4 } as const;
type GeneratedBook = { id: string; template_id: string; title: string; text: string };
const publicStory = (book: GeneratedBook) => ({ id: book.id, templateId: book.template_id, title: book.title, text: book.text });
type Options = { dataDir: string; database?: Database; recordings?: RecordingStore; transcribe?: Transcribe; writeStory?: WriteStory; aiProvider?: keyof typeof aiConsentByProvider; logger?: boolean; now?: () => number; midtrans?: Midtrans };
class HttpError extends Error { constructor(public statusCode: number, message: string) { super(message); } }
const fail = (code: number, message: string): never => { throw new HttpError(code, message); };

function validateTv(p: z.infer<typeof tvSettings>) {
  if ((p.tvTransport === 'wifi' && p.tvPlatform === 'wol' || p.tvMac) &&
      (!p.tvMac || p.tvMac === '00:00:00:00:00:00' || (parseInt(p.tvMac.slice(0, 2), 16) & 1) !== 0)) {
    return fail(400, 'Masukkan MAC Wi-Fi TV yang valid dan bukan alamat multicast.');
  }
  if (p.tvTransport === 'wifi' && p.tvPlatform !== 'wol' && !isPrivateIpv4(p.tvHost)) {
    return fail(400, 'Masukkan alamat IPv4 lokal TV yang valid.');
  }
}

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
  const aiProvider = options.transcribe ? (options.aiProvider ?? 'gemini') : null;
  const hasAiConsent = (child: Child) => aiProvider !== null && child.ai_consent === aiConsentByProvider[aiProvider];
  const dataDir = resolve(options.dataDir);
  await mkdir(join(dataDir, 'recordings'), { recursive: true, mode: 0o700 });
  await mkdir(join(dataDir, 'tmp'), { recursive: true, mode: 0o700 });
  const db = options.database ?? sqliteDatabase(join(dataDir, 'jagabocil.sqlite'));
  const recordings = options.recordings ?? await localRecordings(join(dataDir, 'recordings'));
  const one = db.one, all = db.all, change = db.change;
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
    const tokenHash = raw ? digest(raw) : '';
    const token = tokenHash ? await one<{ role: TokenRole; subject_id: string; auth_revision: number; expires: number }>(
      `SELECT t.role,t.subject_id,t.auth_revision,t.expires FROM tokens t WHERE t.hash=? AND t.expires>?
       AND (t.role='child' AND EXISTS(SELECT 1 FROM children c WHERE c.id=t.subject_id AND c.username IS NULL)
         OR t.role='child_account' AND EXISTS(SELECT 1 FROM children c WHERE c.id=t.subject_id AND c.account_revision=t.auth_revision)
         OR t.role='parent' AND EXISTS(SELECT 1 FROM parents p WHERE p.id=t.subject_id AND p.auth_revision=t.auth_revision))`,
      tokenHash, now(),
    ) : undefined;
    if (!token) return fail(401, 'Sesi habis. Silakan masuk lagi.');
    const role = token.role === 'parent' ? 'parent' : 'child';
    if (required && role !== required) return fail(403, required === 'parent' ? 'Hanya orang tua yang dapat mengubah ini.' : 'Gunakan HP anak untuk langkah ini.');
    return { role, accountLogin: token.role === 'child_account', id: token.subject_id, authRevision: token.auth_revision, tokenHash, expires: token.expires };
  }
  const subscription = billing(app, db, now, async req => (await actor(req, 'parent')).id, options.midtrans);
  async function newToken(role: TokenRole, id: string, revision: number) {
    const token = randomBytes(32).toString('base64url');
    const expiration = now() + sessionDuration;
    if (role === 'child') {
      const inserted = await change('INSERT INTO tokens(hash,role,subject_id,expires,auth_revision) SELECT ?,?,?,?,revision FROM children WHERE id=? AND revision=? AND username IS NULL', digest(token), role, id, expiration, id, revision);
      if (!inserted.changes) return fail(409, 'Perangkat dicabut saat pairing. Minta kode baru ke orang tua.');
    } else if (role === 'child_account') {
      const inserted = await change('INSERT INTO tokens(hash,role,subject_id,expires,auth_revision) SELECT ?,?,?,?,account_revision FROM children WHERE id=? AND account_revision=?',
        digest(token), role, id, expiration, id, revision);
      if (!inserted.changes) return fail(409, 'Login anak baru saja diperbarui. Silakan masuk lagi.');
    } else {
      const inserted = await change('INSERT INTO tokens(hash,role,subject_id,expires,auth_revision) SELECT ?,?,?,?,auth_revision FROM parents WHERE id=? AND auth_revision=?',
        digest(token), role, id, expiration, id, revision);
      if (!inserted.changes) return fail(409, 'Kata sandi baru saja berubah. Silakan masuk lagi.');
    }
    return token;
  }
  async function childFor(a: Actor, id: string) {
    const child = (await one<Child>('SELECT * FROM children WHERE id=?', id));
    if (!child || (a.role === 'parent' ? child.parent_id !== a.id : child.id !== a.id)) return fail(404, 'Profil anak tidak ditemukan.');
    return child;
  }
  async function recFor(a: Actor, id: string) {
    const rec = (await one<Recitation>('SELECT * FROM recitations WHERE id=?', id));
    if (!rec) return fail(404, 'Rekaman tidak ditemukan.');
    await childFor(a, rec.child_id);
    return rec;
  }
  const paramId = (req: FastifyRequest) => z.object({ id: z.string().uuid() }).parse(req.params).id;
  const publicChild = (c: Child, extended = true) => ({ id: c.id, name: c.name, username: c.username, mode: c.mode === 'ai' && !hasAiConsent(c) ? 'manual' : c.mode, surahId: c.surah_id, minSeconds: c.min_seconds, unlockMinutes: c.unlock_minutes, tvBrand: c.tv_brand, tvTransport: c.tv_transport, tvMac: c.tv_mac, tvPlatform: c.tv_platform, tvHost: c.tv_host, tvRevision: c.revision, aiConsent: hasAiConsent(c), readingAssignment: c.reading_assignment, bookReading: extended && c.book_title ? { title: c.book_title, text: c.book_text } : undefined });
  const publicRec = (r: Recitation) => ({ id: r.id, childId: r.child_id, surahId: r.surah_id, status: r.status, duration: r.duration, createdAt: r.created_at, readyAt: r.ready_at, reviewedAt: r.reviewed_at, expiresAt: r.expires_at, usedAt: r.used_at, feedback: r.feedback, reviewer: r.reviewer, readingKind: r.reading_kind, bookTitle: r.book_title, hasTranscript: Boolean(r.transcript_available ?? r.transcript) });
  const awaitingTv = (c: Child) => c.tv_transport === 'infrared' && c.tv_brand === 'none';
  // The capability belongs to a recently paired device and the policy revision it received.
  const tvSetupUntil = (a: Actor) => a.expires - sessionDuration + setupDuration;
  const powerPermit = (c: Child) => ({ authorized: true, tvBrand: c.tv_brand, tvTransport: c.tv_transport, tvMac: c.tv_mac, tvPlatform: c.tv_platform, tvHost: c.tv_host, expiresAt: now() + 10_000 });
  async function pairing(id: string) {
    let code: string;
    do { code = String(randomInt(100_000, 1_000_000)); } while ((await one('SELECT id FROM children WHERE pair_hash=?', digest(code))));
    const expiresAt = now() + pairingDuration;
    await change('UPDATE children SET pair_hash=?,pair_expires=? WHERE id=?', digest(code), expiresAt, id);
    return { code, expiresAt };
  }
  async function cleanup() {
    for (const r of (await all<Recitation>("SELECT * FROM recitations WHERE created_at<? OR status='deleting'", now() - 7 * day))) {
      try {
        await recordings.remove(r.path);
        await change('DELETE FROM recitations WHERE id=?', r.id);
      } catch (error) { app.log.error({ err: error }, 'Recording cleanup will retry'); }
    }
    await change('DELETE FROM tokens WHERE expires<=?', now());
    await change('UPDATE children SET pair_hash=NULL,pair_expires=NULL WHERE pair_expires<=?', now());
  }
  await cleanup();
  const cleanupTimer = setInterval(() => { void cleanup().catch(e => app.log.error(e)); }, 60_000);
  cleanupTimer.unref();
  app.addHook('onClose', async () => { clearInterval(cleanupTimer); await db.close(); });

  app.get('/health', async () => ({ ok: true, aiAvailable: aiProvider !== null, aiProvider, database: db.kind, audioStore: recordings.kind, surahs: legacySurahs(), quranNotice }));
  app.get<{ Params: { id: string } }>('/quran/surahs/:id', async req => {
    return quranText(req.params.id) ?? fail(404, 'Surah tidak ditemukan.');
  });
  const credentials = z.object({ username: z.string().trim().min(1).max(254), password: z.string().min(1).max(128) });
  const newPassword = z.string().min(4).max(128);
  const registration = credentials.extend({
    username: z.string().trim().regex(/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$/),
    password: newPassword,
  });
  const profileName = z.object({ name: z.string().trim().min(1).max(40) });
  async function checkChildLimit(parentId: string) {
    if ((await one<{ n: number }>('SELECT COUNT(*) n FROM children WHERE parent_id=?', parentId))!.n >= 10) {
      return fail(400, 'Maksimal 10 profil anak.');
    }
  }
  app.post('/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    const data = registration.parse(req.body);
    const hashed = await passwordHash(data.password);
    const id = randomUUID();
    try { await change('INSERT INTO parents(id,username,name,password) VALUES(?,?,?,?)', id, data.username.toLowerCase(), data.username, hashed); }
    catch (e) { if ((e as Error).message.includes('UNIQUE')) return fail(409, 'Username sudah terdaftar. Silakan masuk.'); throw e; }
    return reply.code(201).send({ token: (await newToken('parent', id, 0)), role: 'parent', name: data.username });
  });
  app.post('/auth/login', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const data = credentials.parse(req.body);
    const p = await one<{ id: string; name: string; password: string; auth_revision: number }>(
      'SELECT id,name,password,auth_revision FROM parents WHERE username=?', data.username.toLowerCase(),
    );
    // Run scrypt for unknown accounts too.
    const valid = p ? await passwordMatches(data.password, p.password) : (await passwordHash(data.password), false);
    if (!p || !valid) return fail(401, 'Username atau kata sandi kurang tepat.');
    return { token: (await newToken('parent', p.id, p.auth_revision)), role: 'parent', name: p.name };
  });
  app.post('/auth/child/login', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const { username, password } = credentials.parse(req.body);
    const child = await one<{ id: string; name: string; password: string; account_revision: number }>(
      'SELECT id,name,password,account_revision FROM children WHERE username=? AND password IS NOT NULL', username.toLowerCase(),
    );
    const valid = child ? await passwordMatches(password, child.password) : (await passwordHash(password), false);
    if (!child || !valid) return fail(401, 'Username atau kata sandi kurang tepat.');
    return { token: await newToken('child_account', child.id, child.account_revision), role: 'child', name: child.name };
  });
  app.post('/auth/recovery-code', { config: { rateLimit: { max: 5, timeWindow: '15 minutes' } } }, async req => {
    const a = await actor(req, 'parent');
    const { password } = credentials.pick({ password: true }).parse(req.body);
    const p = await one<{ password: string }>('SELECT password FROM parents WHERE id=? AND auth_revision=?', a.id, a.authRevision);
    if (!p || !await passwordMatches(password, p.password)) return fail(403, 'Kata sandi tidak cocok.');
    const code = randomBytes(16).toString('hex').toUpperCase();
    const issued = await change('UPDATE parents SET recovery_hash=? WHERE id=? AND auth_revision=?', digest(code), a.id, a.authRevision);
    if (!issued.changes) return fail(409, 'Kata sandi baru saja berubah. Silakan masuk lagi.');
    return { code: code.match(/.{8}/g)!.join('-') };
  });
  app.post('/auth/recover', { config: { rateLimit: { max: 10, timeWindow: '30 minutes' } } }, async req => {
    const { username, code, password } = credentials.extend({
      code: z.string().max(64).transform(s => s.replace(/[\s-]/g, '').toUpperCase()).pipe(z.string().regex(/^[0-9A-F]{32}$/)),
      password: newPassword,
    }).parse(req.body);
    const changed = await change(
      'UPDATE parents SET password=?,recovery_hash=NULL,auth_revision=auth_revision+1 WHERE username=? AND recovery_hash=?',
      await passwordHash(password), username.toLowerCase(), digest(code),
    );
    if (!changed.changes) return fail(401, 'Username atau kode pemulihan tidak cocok.');
    return { ok: true };
  });
  app.post('/auth/logout', async req => {
    await actor(req);
    await change('DELETE FROM tokens WHERE hash=?', digest(req.headers.authorization!.slice(7)));
    return { ok: true };
  });
  app.post('/children', async (req, reply) => {
    const a = await actor(req, 'parent');
    const { name } = profileName.parse(req.body);
    await checkChildLimit(a.id);
    const id = randomUUID();
    await change('INSERT INTO children(id,parent_id,name) VALUES(?,?,?)', id, a.id, name);
    return reply.code(201).send({ child: publicChild((await childFor(a, id))), pairing: (await pairing(id)) });
  });
  app.post('/children/accounts', async (req, reply) => {
    const a = await actor(req, 'parent');
    const { name, username, password } = registration.extend(profileName.shape).parse(req.body);
    await checkChildLimit(a.id);
    const id = randomUUID();
    try {
      await change('INSERT INTO children(id,parent_id,name,username,password) VALUES(?,?,?,?,?)',
        id, a.id, name, username.toLowerCase(), await passwordHash(password));
    } catch (error) {
      if ((error as Error).message.includes('UNIQUE')) return fail(409, 'Username anak sudah dipakai. Pilih username lain.');
      throw error;
    }
    return reply.code(201).send({ child: publicChild(await childFor(a, id)) });
  });
  app.put('/children/:id/account', async req => {
    const a = await actor(req, 'parent'); const c = await childFor(a, paramId(req));
    const { username, password } = registration.parse(req.body);
    try {
      await db.batch([
        { sql: 'UPDATE children SET username=?,password=?,account_revision=account_revision+1,revision=revision+1,pair_hash=NULL,pair_expires=NULL WHERE id=?',
          values: [username.toLowerCase(), await passwordHash(password), c.id] },
        { sql: "DELETE FROM tokens WHERE subject_id=? AND (role='child' OR role='child_account')", values: [c.id] },
      ]);
    } catch (error) {
      if ((error as Error).message.includes('UNIQUE')) return fail(409, 'Username anak sudah dipakai. Pilih username lain.');
      throw error;
    }
    return { child: publicChild(await childFor(a, c.id)) };
  });
  app.post('/children/:id/pairing', async req => {
    const a = await actor(req, 'parent'); const c = await childFor(a, paramId(req));
    if (c.username !== null) return fail(409, 'Anak sudah memiliki akun. Masuk dengan username dan kata sandi anak.');
    return { pairing: await pairing(c.id) };
  });
  app.post('/pair', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const { code } = z.object({ code: z.string().regex(/^\d{6}$/) }).parse(req.body);
    const c = (await one<Child>('UPDATE children SET pair_hash=NULL,pair_expires=NULL WHERE pair_hash=? AND pair_expires>? AND username IS NULL RETURNING *', digest(code), now()));
    if (!c) return fail(400, 'Kode salah atau kedaluwarsa. Minta kode baru ke orang tua.');
    return { token: (await newToken('child', c.id, c.revision)), role: 'child', name: c.name };
  });
  app.delete('/children/:id/devices', async req => {
    const a = await actor(req, 'parent'); const c = (await childFor(a, paramId(req)));
    await db.batch([
      { sql: 'UPDATE children SET pair_hash=NULL,pair_expires=NULL,revision=revision+1,account_revision=account_revision+1 WHERE id=?', values: [c.id] },
      { sql: "DELETE FROM tokens WHERE subject_id=? AND (role='child' OR role='child_account')", values: [c.id] },
      { sql: "UPDATE recitations SET status='rejected',reviewed_at=NULL,feedback='Izin dicabut orang tua.' WHERE child_id=? AND used_at IS NULL AND status IN ('pending','approved')", values: [c.id] },
    ]);
    return { ok: true };
  });
  app.put('/children/:id/policy', async req => {
    const a = await actor(req, 'parent'); const c = (await childFor(a, paramId(req)));
    const p = tvSettings.extend({
      mode: z.enum(['manual', 'ai']), surahId: z.string().refine(isTaskSurah), minSeconds: z.number().int().min(10).max(120),
      unlockMinutes: z.number().int().min(1).max(60), aiConsent: z.boolean(), transcriptConsent: z.boolean().optional(),
      bookReading: z.object({ title: z.string().trim().max(80), text: z.string().trim().max(1600) }).optional(),
    }).parse(req.body);
    validateTv(p);
    if (p.mode === 'ai' && (!p.aiConsent || !p.transcriptConsent || !aiProvider)) return fail(400, 'Aktifkan izin pengiriman audio dan penyimpanan transkripsi di aplikasi terbaru.');
    const book = p.bookReading;
    if (book && (book.title || book.text) && (!book.title || readingWords(book.text).length < 8)) {
      return fail(400, 'Masukkan judul buku dan kutipan dengan minimal 8 kata.');
    }
    const consent = p.mode === 'ai' && aiProvider ? aiConsentByProvider[aiProvider] : 0;
    await db.batch([
      { sql: `UPDATE children SET mode=?,surah_id=CASE WHEN reading_assignment='quran' THEN surah_id ELSE ? END,
        min_seconds=?,unlock_minutes=?,tv_brand=?,ai_consent=?,tv_transport=?,tv_mac=?,tv_platform=?,tv_host=?,
        book_title=CASE WHEN reading_assignment='book' THEN book_title ELSE COALESCE(?,book_title) END,
        book_text=CASE WHEN reading_assignment='book' THEN book_text ELSE COALESCE(?,book_text) END,
        revision=revision+1 WHERE id=?`, values: [p.mode, p.surahId, p.minSeconds, p.unlockMinutes, p.tvBrand, consent, p.tvTransport, p.tvMac, p.tvPlatform, p.tvHost, book?.title ?? null, book?.text ?? null, c.id] },
      { sql: "UPDATE recitations SET status='rejected',reviewed_at=NULL,feedback='Aturan diperbarui. Yuk, rekam bacaan baru.' WHERE child_id=? AND used_at IS NULL AND status IN ('pending','approved')", values: [c.id] },
    ]);
    return { child: publicChild((await childFor(a, c.id)), true) };
  });
  app.put('/children/:id/reading-task', async req => {
    const a = await actor(req, 'parent');
    const child = await childFor(a, paramId(req));
    const task = z.union([
      z.object({ kind: z.literal('free') }),
      z.object({ kind: z.literal('quran'), surahId: z.string().refine(isTaskSurah) }),
      z.object({ kind: z.literal('book'), bookId: z.string().min(1).max(80) }),
      z.object({ kind: z.literal('book'), title: z.string().trim().min(1).max(80), text: z.string().trim().max(1600) }),
    ]).parse(req.body);
    let book: { title: string; text: string } | undefined;
    if (task.kind === 'book') {
      book = 'bookId' in task ? bookTemplates.get(task.bookId) : task;
      if (!book) return fail(404, 'Cerita tidak ditemukan di Rak Buku.');
      if (readingWords(book.text).length < 8) return fail(400, 'Masukkan kutipan dengan minimal 8 kata.');
    }
    const surahId = task.kind === 'quran' ? task.surahId : null;
    if (task.kind === child.reading_assignment && (surahId === null || surahId === child.surah_id) &&
      (!book || (book.title === child.book_title && book.text === child.book_text))) {
      return { child: publicChild(child) };
    }
    await db.batch([
      { sql: `UPDATE children SET reading_assignment=?,surah_id=COALESCE(?,surah_id),
        book_title=COALESCE(?,CASE WHEN reading_assignment='book' THEN '' ELSE book_title END),
        book_text=COALESCE(?,CASE WHEN reading_assignment='book' THEN '' ELSE book_text END),
        revision=revision+1 WHERE id=?`, values: [task.kind, surahId, book?.title ?? null, book?.text ?? null, child.id] },
      { sql: "UPDATE recitations SET status='rejected',reviewed_at=NULL,feedback='Tugas bacaan diganti. Yuk, baca tugas barumu.' WHERE child_id=? AND used_at IS NULL AND status IN ('pending','approved')", values: [child.id] },
    ]);
    return { child: publicChild(await childFor(a, child.id)) };
  });
  app.get('/dashboard', async req => {
    const a = await actor(req);
    const children = a.role === 'parent' ? (await all<Child>('SELECT * FROM children WHERE parent_id=? ORDER BY name,id', a.id)) : [(await childFor(a, a.id))];
    const query = req.query as { quran?: string; reading?: string };
    const extended = query.reading === 'extended';
    const recitationSummary = `SELECT r.id,r.child_id,r.surah_id,r.status,r.duration,r.created_at,r.ready_at,r.reviewed_at,
      r.expires_at,r.used_at,r.feedback,r.reviewer,r.reading_kind,r.book_title,r.transcript IS NOT NULL AS transcript_available FROM recitations r`;
    const visible = "AND r.status NOT IN ('uploading','failed','deleting') AND (r.reading_kind='quran' OR ?=1)";
    const recs = a.role === 'parent'
      ? await all<Recitation>(`${recitationSummary} JOIN children c ON c.id=r.child_id WHERE c.parent_id=? ${visible} ORDER BY r.created_at DESC LIMIT 100`, a.id, Number(extended))
      : await all<Recitation>(`${recitationSummary} WHERE r.child_id=? ${visible} ORDER BY r.created_at DESC LIMIT 50`, a.id, Number(extended));
    const parent = a.role === 'parent' ? await one<{ name: string; recovery_hash: string | null }>('SELECT name,recovery_hash FROM parents WHERE id=?', a.id) : undefined;
    const localQuran = query.quran === 'local';
    const surahs = localQuran ? [] : legacySurahs([...children.map(c => c.surah_id), ...recs.map(r => r.surah_id)]);
    const plan = a.role === 'parent' ? await subscription.summary(a.id) : null;
    const firstChild = children[0];
    const setup = a.role === 'child' && !a.accountLogin && firstChild && awaitingTv(firstChild) && firstChild.revision === a.authRevision && tvSetupUntil(a) > now();
    return { role: a.role, name: parent?.name ?? children[0]!.name, recoveryConfigured: !!parent?.recovery_hash, aiAvailable: aiProvider !== null, aiProvider, children: children.map(c => publicChild(c, extended)), recitations: recs.map(publicRec), surahs, juz30Tasks: true, readingActivities: true, readingAssignments: true, bookLibrary: true, storyGeneration: Boolean(options.writeStory), quranNotice: localQuran ? undefined : quranNotice, serverTime: now(), billing: plan, tvSetupUntil: setup ? tvSetupUntil(a) : null };
  });
  app.get('/books/generated', async req => {
    const a = await actor(req, 'child');
    const books = await all<GeneratedBook>('SELECT id,template_id,title,text FROM generated_books WHERE child_id=? ORDER BY template_id', a.id);
    return { books: books.map(publicStory) };
  });
  app.post('/books/generated', { config: { rateLimit: { max: 12, timeWindow: '1 hour' } } }, async (req, reply) => {
    const a = await actor(req, 'child');
    const { templateId } = z.object({ templateId: z.string().max(80) }).parse(req.body);
    const template = bookTemplates.get(templateId);
    if (!template) return fail(404, 'Template cerita tidak ditemukan.');
    const findStory = () => one<GeneratedBook>('SELECT id,template_id,title,text FROM generated_books WHERE child_id=? AND template_id=?', a.id, templateId);
    const existing = await findStory();
    if (existing) return { book: publicStory(existing) };
    if (!options.writeStory) return reply.code(503).send({ message: 'Versi AI belum tersedia. Cerita bawaan tetap bisa dibaca.' });
    let story;
    try {
      story = storySchema.parse(await options.writeStory(template));
    } catch {
      app.log.warn('Book generation failed');
      return reply.code(502).send({ message: 'Cerita AI belum berhasil dibuat. Coba lagi atau baca versi bawaan.' });
    }
    await change('INSERT INTO generated_books(id,child_id,template_id,title,text) VALUES(?,?,?,?,?) ON CONFLICT(child_id,template_id) DO NOTHING',
      randomUUID(), a.id, templateId, story.title, story.text);
    return { book: publicStory((await findStory())!) };
  });
  app.post('/recitations', { config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const a = await actor(req, 'child'); const c = (await childFor(a, a.id));
    if ((await one<{ n: number }>("SELECT COUNT(*) n FROM recitations WHERE child_id=? AND status='pending'", c.id))!.n >= 5) return fail(429, 'Masih ada 5 rekaman menunggu. Tunggu orang tua memeriksa dulu.');
    let buffer: Buffer | undefined; let surahId: string | undefined;
    for await (const part of req.parts()) {
      if (part.type === 'file') { buffer = await part.toBuffer(); if (part.file.truncated) return fail(413, 'Rekaman maksimal 10 MB.'); }
      else if (part.fieldname === 'surahId') surahId = String(part.value);
    }
    const query = req.query as { selection?: string; kind?: string; revision?: string; bookId?: string };
    const isBook = query.kind === 'book';
    const selfSelected = query.selection === 'self';
    if (!buffer?.length) return fail(400, 'Sertakan rekaman bacaanmu.');
    if ((isBook || c.reading_assignment !== 'free' || query.revision !== undefined) && query.revision !== String(c.revision)) {
      return fail(409, 'Aturan bacaan diperbarui. Muat ulang lalu rekam ulang.');
    }
    if (c.reading_assignment === 'quran' && (isBook || surahId !== c.surah_id) ||
        c.reading_assignment === 'book' && (!isBook || query.bookId !== undefined)) {
      return fail(400, 'Kirim rekaman sesuai tugas dari orang tua.');
    }
    let book: { title: string; text: string } | undefined;
    if (isBook) {
      if (query.bookId !== undefined) {
        const { bookId } = z.object({ bookId: z.string().min(1).max(80) }).parse(query);
        book = bookTemplates.get(bookId)
          ?? await one<GeneratedBook>('SELECT title,text FROM generated_books WHERE id=? AND child_id=?', bookId, c.id);
        if (!book) return fail(404, 'Cerita tidak ditemukan. Pilih lagi dari rak buku.');
      } else if (c.book_title && c.book_text) {
        book = { title: c.book_title, text: c.book_text };
      } else {
        return fail(400, 'Pilih cerita dari rak buku.');
      }
    } else if (!surahId || (selfSelected ? !isTaskSurah(surahId) : surahId !== c.surah_id)) {
      return fail(400, selfSelected ? 'Pilih surah Juz 30 dan sertakan rekaman bacaanmu.' : 'Kirim rekaman sesuai surah tugasmu.');
    }
    const maxSeconds = isBook ? 180 : taskRecordingLimit(surahId!);
    const id = randomUUID(); const path = join(dataDir, 'tmp', `${id}.audio`);
    await writeFile(path, buffer, { mode: 0o600 });
    let duration: number; let mime: string;
    try {
      const { stdout } = await run('ffprobe', ['-v', 'error', '-protocol_whitelist', 'file,pipe', '-format_whitelist', 'mov,wav', '-show_entries', 'format=duration,format_name:stream=codec_type', '-of', 'json', path], { timeout: 10_000, maxBuffer: 64_000 });
      const meta = JSON.parse(stdout);
      duration = Number(meta.format?.duration);
      if (!Number.isFinite(duration) || duration < c.min_seconds || duration > maxSeconds || !meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'audio') || meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'video')) throw new Error('invalid audio');
      mime = meta.format.format_name === 'wav' ? 'audio/wav' : 'audio/mp4';
    } catch {
      return fail(400, `Gunakan rekaman audio M4A/WAV sepanjang ${c.min_seconds}–${maxSeconds} detik.`);
    } finally { await unlink(path).catch(() => {}); }
    const reference = recordings.reference(id, c.id);
    const inserted = await change(`INSERT INTO recitations(id,child_id,surah_id,status,duration,path,mime,created_at,feedback,reading_kind,book_title,reference_text)
      SELECT ?,?,?,?,?,?,?,?,?,?,?,? FROM children WHERE id=? AND revision=?`, id, c.id, isBook ? '' : surahId!, 'uploading', duration,
      reference, mime, now(), 'Menunggu orang tua mendengarkan.', isBook ? 'book' : 'quran', book?.title ?? null, book?.text ?? null, c.id, c.revision);
    if (!inserted.changes) return fail(409, 'Aturan atau perangkat diperbarui. Muat ulang dan rekam bacaan baru.');
    try {
      await recordings.put(id, c.id, buffer, mime);
    } catch (error) {
      // Keep the reference for retryable retention cleanup, including ambiguous network failures.
      await change("UPDATE recitations SET status='failed' WHERE id=?", id);
      app.log.error({ err: error }, 'Recording storage failed');
      return fail(503, 'Rekaman belum tersimpan. Coba kirim lagi sebentar.');
    }
    const policyLock = db.kind === 'postgres' ? ' FOR UPDATE' : '';
    const ready = await change(`UPDATE recitations SET status='pending',ready_at=? WHERE id=? AND status='uploading'
      AND EXISTS(SELECT 1 FROM children WHERE id=? AND revision=?${policyLock})`, now(), id, c.id, c.revision);
    if (!ready.changes) {
      await recordings.remove(reference);
      await change('DELETE FROM recitations WHERE id=?', id);
      return fail(409, 'Aturan atau perangkat diperbarui. Rekam bacaan baru sesuai tugasmu.');
    }
    if (c.mode === 'ai' && hasAiConsent(c) && options.transcribe) {
      try {
        if (duration > maxAiReadingSeconds) throw Error('Recording exceeds the transcription limit.');
        const transcript = (await options.transcribe(buffer, mime, duration)).trim().slice(0, 8000);
        if (!readingWords(transcript).length) throw Error('No intelligible words.');
        const score = book ? bookMatchScore(book.text, transcript) : null;
        const feedback = score === null ? 'Transkripsi siap. Cocokkan dengan teks surah dan dengarkan rekaman.'
          : score >= bookMatchThreshold ? 'Transkripsi cukup cocok dengan kutipan buku.' : 'Transkripsi perlu dicocokkan ulang dengan kutipan buku.';
        await change("UPDATE recitations SET transcript=?,match_score=?,feedback=? WHERE id=? AND status='pending' AND reviewer IS NULL",
          transcript, score, feedback, id);
      } catch {
        await change("UPDATE recitations SET feedback='AI belum bisa memeriksa. Rekaman diteruskan ke orang tua.' WHERE id=? AND status='pending' AND reviewer IS NULL", id);
      }
    }
    return reply.code(201).send({ recitation: publicRec((await one<Recitation>('SELECT * FROM recitations WHERE id=?', id))!) });
  });
  app.get('/recitations/:id/audio', async (req, reply) => {
    const a = await actor(req); const r = (await recFor(a, paramId(req)));
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    if (['uploading', 'failed', 'deleting'].includes(r.status)) return fail(409, 'Rekaman belum siap diputar.');
    return reply.type(r.mime).send(await recordings.get(r.path));
  });
  app.get('/recitations/:id/reading-details', async (req, reply) => {
    const a = await actor(req, 'parent'); const r = await recFor(a, paramId(req));
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    if (['uploading', 'failed', 'deleting'].includes(r.status)) return fail(409, 'Rekaman belum siap dibuka.');
    reply.header('cache-control', 'private, no-store');
    return { transcript: r.transcript, referenceText: r.reference_text, matchScore: r.match_score, matchThreshold: bookMatchThreshold };
  });
  app.post('/recitations/:id/review', async req => {
    const a = await actor(req, 'parent'); const r = (await recFor(a, paramId(req))); const c = (await childFor(a, r.child_id));
    const { decision } = z.object({ decision: z.enum(['approve', 'reject']) }).parse(req.body);
    const approved = decision === 'approve';
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    const reviewTime = now();
    const updated = await change("UPDATE recitations SET status=?,expires_at=?,reviewer='parent',feedback=?,reviewed_at=? WHERE id=? AND status='pending' AND used_at IS NULL", approved ? 'approved' : 'rejected', approved ? reviewTime + c.unlock_minutes * 60_000 : null, approved ? 'Disetujui orang tua. Terima kasih sudah membaca!' : 'Yuk, coba baca lagi. Orang tua ingin mendengar lebih jelas.', reviewTime, r.id);
    if (!updated.changes) return fail(409, 'Rekaman ini sudah diperiksa.');
    return { recitation: publicRec((await one<Recitation>('SELECT * FROM recitations WHERE id=?', r.id))!) };
  });
  app.delete('/recitations/:id', async req => {
    const a = await actor(req, 'parent'); const r = (await recFor(a, paramId(req)));
    await change("UPDATE recitations SET status='deleting' WHERE id=?", r.id);
    await recordings.remove(r.path);
    await change('DELETE FROM recitations WHERE id=?', r.id);
    return { ok: true };
  });
  app.put('/child/tv', async req => {
    const a = await actor(req, 'child');
    const p = tvSettings.parse(req.body);
    validateTv(p);
    const child = await one<Child>(
      `UPDATE children AS c SET tv_brand=?,tv_transport=?,tv_platform=?,tv_host=?,tv_mac=?,revision=revision+1
       WHERE c.id=? AND EXISTS(SELECT 1 FROM tokens t WHERE t.hash=? AND t.expires>?
         AND (t.role='child' AND c.username IS NULL OR t.role='child_account' AND t.auth_revision=c.account_revision))
       RETURNING *`,
      p.tvBrand, p.tvTransport, p.tvPlatform, p.tvHost, p.tvMac, a.id, a.tokenHash, now(),
    );
    if (!child) return fail(401, 'Sesi habis. Silakan masuk lagi.');
    return { child: publicChild(child) };
  });
  app.post('/tv/setup', async req => {
    const a = await actor(req, 'child');
    const { platform, host } = z.object({ platform: z.enum(['androidtv', 'samsung', 'lg']), host: z.string().max(15).refine(isPrivateIpv4) }).parse(req.body);
    const at = now();
    if (a.accountLogin) return fail(403, 'Untuk menyalakan TV, kirim bacaan dan tunggu persetujuan.');
    if (tvSetupUntil(a) <= at) return fail(409, 'Waktu tes TV habis. Minta orang tua membuat kode sambung baru.');
    const updated = await one<Child>(
      `UPDATE children AS c SET tv_transport='wifi',tv_platform=?,tv_host=?,tv_mac='',revision=revision+1
       WHERE c.id=? AND c.revision=? AND c.tv_transport='infrared' AND c.tv_brand='none'
       AND EXISTS(SELECT 1 FROM tokens t WHERE t.hash=? AND t.role='child' AND t.subject_id=c.id AND t.expires>?)
       RETURNING *`, platform, host, a.id, a.authRevision, a.tokenHash, at,
    );
    if (!updated) return fail(409, 'Tes TV ini sudah tidak tersedia. Minta orang tua cek pengaturan TV.');
    return { child: publicChild(updated), permit: powerPermit(updated) };
  });
  app.post('/tv/authorize', async req => {
    const a = await actor(req, 'child');
    const { recitationId, tvTransport, tvRevision } = z.object({ recitationId: z.string().uuid(), tvTransport: z.enum(['infrared', 'wifi']).default('infrared'), tvRevision: z.number().int().nonnegative().optional() }).parse(req.body);
    const r = (await recFor(a, recitationId)); const c = (await childFor(a, a.id));
    if (tvTransport !== c.tv_transport || tvRevision !== undefined && tvRevision !== c.revision) return fail(409, 'Pengaturan TV berubah. Muat ulang lalu coba lagi.');
    if (c.tv_transport === 'infrared' && c.tv_brand === 'none') return fail(400, 'Pilih TV dulu.');
    if (c.tv_transport === 'wifi' && (c.tv_platform === 'wol' ? !c.tv_mac : !isPrivateIpv4(c.tv_host))) return fail(400, 'Lengkapi sambungan Wi-Fi TV dulu.');
    const used = await change(`UPDATE recitations SET used_at=? WHERE id=? AND child_id=? AND status='approved'
      AND used_at IS NULL AND expires_at>? AND created_at>? AND EXISTS(SELECT 1 FROM children c WHERE c.id=? AND c.revision=?)`,
      now(), r.id, c.id, now(), now() - 7 * day, c.id, c.revision);
    if (!used.changes) return fail(403, 'Ngaji perlu disetujui dulu. Izin hanya berlaku sekali dan bisa kedaluwarsa.');
    return powerPermit(c);
  });
  return app;
}
