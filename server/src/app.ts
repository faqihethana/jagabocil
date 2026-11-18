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
import { surahs } from './catalog.js';
import { passes, verdictSchema, type Verify } from './ai.js';

const run = promisify(execFile);
const digest = (s: string) => createHash('sha256').update(s).digest('hex');
const day = 86_400_000;
function isPrivateIpv4(host: string) {
  if (!/^(0|[1-9][0-9]{0,2})(\.(0|[1-9][0-9]{0,2})){3}$/.test(host)) return false;
  const [a, b, c, d] = host.split('.').map(Number);
  return [a,b,c,d].every(x => x !== undefined && x <= 255) && (a === 10 || a === 192 && b === 168 || a === 172 && b! >= 16 && b! <= 31);
}
type Actor = { role: 'parent' | 'child'; id: string };
type Child = { id: string; parent_id: string; name: string; mode: string; surah_id: string; min_seconds: number; unlock_minutes: number; tv_brand: string; tv_transport: string; tv_mac: string; tv_platform: string; tv_host: string; ai_consent: number; revision: number; pair_hash: string | null; pair_expires: number | null };
type Recitation = { id: string; child_id: string; surah_id: string; status: string; duration: number; path: string; mime: string; created_at: number; expires_at: number | null; used_at: number | null; feedback: string; reviewer: string | null };
// The original consent value (1) covered Gemini. DeepSeek requires a fresh opt-in.
const aiConsentByProvider = { gemini: 1, deepseek: 2 } as const;
type Options = { dataDir: string; database?: Database; recordings?: RecordingStore; verify?: Verify; aiProvider?: keyof typeof aiConsentByProvider; logger?: boolean; now?: () => number };
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
  const aiProvider = options.verify ? (options.aiProvider ?? 'gemini') : null;
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
    const token = raw ? (await one<{ role: Actor['role']; subject_id: string }>('SELECT * FROM tokens WHERE hash=? AND expires>?', digest(raw), now())) : undefined;
    if (!token) return fail(401, 'Sesi habis. Silakan masuk lagi.');
    if (required && token.role !== required) return fail(403, 'Hanya orang tua yang dapat mengubah ini.');
    return { role: token.role, id: token.subject_id };
  }
  async function newToken(role: Actor['role'], id: string, revision?: number) {
    const token = randomBytes(32).toString('base64url');
    if (role === 'child') {
      const inserted = await change('INSERT INTO tokens SELECT ?,?,?,? FROM children WHERE id=? AND revision=?', digest(token), role, id, now() + 30 * day, id, revision!);
      if (!inserted.changes) return fail(409, 'Perangkat dicabut saat pairing. Minta kode baru ke orang tua.');
    } else await change('INSERT INTO tokens VALUES(?,?,?,?)', digest(token), role, id, now() + 30 * day);
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
  const publicChild = (c: Child) => ({ id: c.id, name: c.name, mode: c.mode === 'ai' && !hasAiConsent(c) ? 'manual' : c.mode, surahId: c.surah_id, minSeconds: c.min_seconds, unlockMinutes: c.unlock_minutes, tvBrand: c.tv_brand, tvTransport: c.tv_transport, tvMac: c.tv_mac, tvPlatform: c.tv_platform, tvHost: c.tv_host, aiConsent: hasAiConsent(c) });
  const publicRec = (r: Recitation) => ({ id: r.id, childId: r.child_id, surahId: r.surah_id, status: r.status, duration: r.duration, createdAt: r.created_at, expiresAt: r.expires_at, usedAt: r.used_at, feedback: r.feedback, reviewer: r.reviewer });
  async function pairing(id: string) {
    let code: string;
    do { code = String(randomInt(100_000, 1_000_000)); } while ((await one('SELECT id FROM children WHERE pair_hash=?', digest(code))));
    await change('UPDATE children SET pair_hash=?,pair_expires=? WHERE id=?', digest(code), now() + 600_000, id);
    return { code, expiresAt: now() + 600_000 };
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
  const cleanupTimer = setInterval(() => { void cleanup().catch(e => app.log.error(e)); }, 3_600_000);
  cleanupTimer.unref();
  app.addHook('onClose', async () => { clearInterval(cleanupTimer); await db.close(); });

  app.get('/health', async () => ({ ok: true, aiAvailable: aiProvider !== null, aiProvider, database: db.kind, audioStore: recordings.kind, surahs }));
  const credentials = z.object({ username: z.string().trim().min(1).max(254), password: z.string().min(1).max(128) });
  const registration = credentials.extend({
    username: z.string().trim().regex(/^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$/),
    password: z.string().min(4).max(128),
  });
  app.post('/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    const data = registration.parse(req.body);
    const hashed = await passwordHash(data.password);
    const id = randomUUID();
    try { await change('INSERT INTO parents(id,username,name,password) VALUES(?,?,?,?)', id, data.username.toLowerCase(), data.username, hashed); }
    catch (e) { if ((e as Error).message.includes('UNIQUE')) return fail(409, 'Username sudah terdaftar. Silakan masuk.'); throw e; }
    return reply.code(201).send({ token: (await newToken('parent', id)), role: 'parent', name: data.username });
  });
  app.post('/auth/login', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const data = credentials.parse(req.body);
    const p = (await one<{ id: string; name: string; password: string }>('SELECT id,name,password FROM parents WHERE username=?', data.username.toLowerCase()));
    // Run scrypt for unknown accounts too.
    const valid = p ? await passwordMatches(data.password, p.password) : (await passwordHash(data.password), false);
    if (!p || !valid) return fail(401, 'Username atau kata sandi kurang tepat.');
    return { token: (await newToken('parent', p.id)), role: 'parent', name: p.name };
  });
  app.post('/auth/logout', async req => {
    await actor(req);
    await change('DELETE FROM tokens WHERE hash=?', digest(req.headers.authorization!.slice(7)));
    return { ok: true };
  });
  app.post('/children', async (req, reply) => {
    const a = await actor(req, 'parent');
    const { name } = z.object({ name: z.string().trim().min(1).max(40) }).parse(req.body);
    if ((await one<{ n: number }>('SELECT COUNT(*) n FROM children WHERE parent_id=?', a.id))!.n >= 10) return fail(400, 'Maksimal 10 profil anak.');
    const id = randomUUID();
    await change('INSERT INTO children(id,parent_id,name) VALUES(?,?,?)', id, a.id, name);
    return reply.code(201).send({ child: publicChild((await childFor(a, id))), pairing: (await pairing(id)) });
  });
  app.post('/children/:id/pairing', async req => { const a = await actor(req, 'parent'); const c = (await childFor(a, paramId(req))); return { pairing: (await pairing(c.id)) }; });
  app.post('/pair', { config: { rateLimit: { max: 10, timeWindow: '15 minutes' } } }, async req => {
    const { code } = z.object({ code: z.string().regex(/^\d{6}$/) }).parse(req.body);
    const c = (await one<Child>('UPDATE children SET pair_hash=NULL,pair_expires=NULL WHERE pair_hash=? AND pair_expires>? RETURNING *', digest(code), now()));
    if (!c) return fail(400, 'Kode salah atau kedaluwarsa. Minta kode baru ke orang tua.');
    return { token: (await newToken('child', c.id, c.revision)), role: 'child', name: c.name };
  });
  app.delete('/children/:id/devices', async req => {
    const a = await actor(req, 'parent'); const c = (await childFor(a, paramId(req)));
    await db.batch([
      { sql: 'DELETE FROM tokens WHERE subject_id=?', values: [c.id] },
      { sql: 'UPDATE children SET pair_hash=NULL,pair_expires=NULL,revision=revision+1 WHERE id=?', values: [c.id] },
      { sql: "UPDATE recitations SET status='rejected',feedback='Izin dicabut orang tua.' WHERE child_id=? AND used_at IS NULL AND status IN ('pending','approved')", values: [c.id] },
    ]);
    return { ok: true };
  });
  app.put('/children/:id/policy', async req => {
    const a = await actor(req, 'parent'); const c = (await childFor(a, paramId(req)));
    const p = z.object({ mode: z.enum(['manual', 'ai']), surahId: z.enum(['112', '113', '114']), minSeconds: z.number().int().min(10).max(120), unlockMinutes: z.number().int().min(1).max(60), tvBrand: z.enum(['none', 'samsung', 'lg']), aiConsent: z.boolean(), tvPlatform: z.enum(['wol', 'androidtv', 'samsung', 'lg']).default('wol'), tvHost: z.string().trim().max(15).default(''), tvTransport: z.enum(['infrared', 'wifi']).default('infrared'), tvMac: z.string().trim().toUpperCase().regex(/^([0-9A-F]{2}:){5}[0-9A-F]{2}$|^$/).default('') }).parse(req.body);
    if ((p.tvTransport === 'wifi' && p.tvPlatform === 'wol' || p.tvMac) && (!p.tvMac || p.tvMac === '00:00:00:00:00:00' || (parseInt(p.tvMac.slice(0, 2), 16) & 1) !== 0)) return fail(400, 'Masukkan MAC Wi-Fi TV yang valid dan bukan alamat multicast.');
    if (p.tvTransport === 'wifi' && p.tvPlatform !== 'wol' && !isPrivateIpv4(p.tvHost)) return fail(400, 'Masukkan alamat IPv4 lokal TV yang valid.');
    if (p.mode === 'ai' && (!p.aiConsent || !aiProvider)) return fail(400, 'AI perlu persetujuan orang tua dan konfigurasi server.');
    const consent = p.mode === 'ai' && aiProvider ? aiConsentByProvider[aiProvider] : 0;
    await db.batch([
      { sql: 'UPDATE children SET mode=?,surah_id=?,min_seconds=?,unlock_minutes=?,tv_brand=?,ai_consent=?,tv_transport=?,tv_mac=?,tv_platform=?,tv_host=?,revision=revision+1 WHERE id=?', values: [p.mode, p.surahId, p.minSeconds, p.unlockMinutes, p.tvBrand, consent, p.tvTransport, p.tvMac, p.tvPlatform, p.tvHost, c.id] },
      { sql: "UPDATE recitations SET status='rejected',feedback='Aturan diperbarui. Yuk, rekam ngaji lagi.' WHERE child_id=? AND used_at IS NULL AND status IN ('pending','approved')", values: [c.id] },
    ]);
    return { child: publicChild((await childFor(a, c.id))) };
  });
  app.get('/dashboard', async req => {
    const a = await actor(req);
    const children = a.role === 'parent' ? (await all<Child>('SELECT * FROM children WHERE parent_id=? ORDER BY name,id', a.id)) : [(await childFor(a, a.id))];
    const recs = a.role === 'parent'
      ? (await all<Recitation>("SELECT r.* FROM recitations r JOIN children c ON c.id=r.child_id WHERE c.parent_id=? AND r.status NOT IN ('uploading','failed','deleting') ORDER BY r.created_at DESC LIMIT 100", a.id))
      : (await all<Recitation>("SELECT * FROM recitations WHERE child_id=? AND status NOT IN ('uploading','failed','deleting') ORDER BY created_at DESC LIMIT 50", a.id));
    const name = a.role === 'parent' ? (await one<{ name: string }>('SELECT name FROM parents WHERE id=?', a.id))!.name : children[0]!.name;
    return { role: a.role, name, aiAvailable: aiProvider !== null, aiProvider, children: children.map(publicChild), recitations: recs.map(publicRec), surahs, serverTime: now() };
  });
  app.post('/recitations', { config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const a = await actor(req, 'child'); const c = (await childFor(a, a.id));
    if ((await one<{ n: number }>("SELECT COUNT(*) n FROM recitations WHERE child_id=? AND status='pending'", c.id))!.n >= 5) return fail(429, 'Masih ada 5 rekaman menunggu. Tunggu orang tua memeriksa dulu.');
    let buffer: Buffer | undefined; let surahId: string | undefined;
    for await (const part of req.parts()) {
      if (part.type === 'file') { buffer = await part.toBuffer(); if (part.file.truncated) return fail(413, 'Rekaman maksimal 10 MB.'); }
      else if (part.fieldname === 'surahId') surahId = String(part.value);
    }
    if (!buffer?.length || surahId !== c.surah_id) return fail(400, 'Kirim rekaman sesuai surah tugasmu.');
    const id = randomUUID(); const path = join(dataDir, 'tmp', `${id}.audio`);
    await writeFile(path, buffer, { mode: 0o600 });
    let duration: number; let mime: string;
    try {
      const { stdout } = await run('ffprobe', ['-v', 'error', '-protocol_whitelist', 'file,pipe', '-format_whitelist', 'mov,wav', '-show_entries', 'format=duration,format_name:stream=codec_type', '-of', 'json', path], { timeout: 10_000, maxBuffer: 64_000 });
      const meta = JSON.parse(stdout);
      duration = Number(meta.format?.duration);
      if (!Number.isFinite(duration) || duration < c.min_seconds || duration > 180 || !meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'audio') || meta.streams?.some((s: { codec_type: string }) => s.codec_type === 'video')) throw new Error('invalid audio');
      mime = meta.format.format_name === 'wav' ? 'audio/wav' : 'audio/mp4';
    } catch {
      return fail(400, `Gunakan rekaman audio M4A/WAV sepanjang ${c.min_seconds}–180 detik.`);
    } finally { await unlink(path).catch(() => {}); }
    const reference = recordings.reference(id, c.id);
    const inserted = (await change('INSERT INTO recitations(id,child_id,surah_id,status,duration,path,mime,created_at,feedback) SELECT ?,?,?,?,?,?,?,?,? FROM children WHERE id=? AND revision=?', id, c.id, surahId, 'uploading', duration, reference, mime, now(), 'Menunggu orang tua mendengarkan.', c.id, c.revision));
    if (!inserted.changes) return fail(409, 'Aturan atau perangkat diperbarui. Muat ulang dan rekam bacaan baru.');
    try {
      await recordings.put(id, c.id, buffer, mime);
    } catch (error) {
      // Keep the reference for retryable retention cleanup, including ambiguous network failures.
      await change("UPDATE recitations SET status='failed' WHERE id=?", id);
      app.log.error({ err: error }, 'Recording storage failed');
      return fail(503, 'Rekaman belum tersimpan. Coba kirim lagi sebentar.');
    }
    const ready = (await change("UPDATE recitations SET status='pending' WHERE id=? AND status='uploading' AND EXISTS(SELECT 1 FROM children WHERE id=? AND revision=?)", id, c.id, c.revision));
    if (!ready.changes) {
      await recordings.remove(reference);
      await change('DELETE FROM recitations WHERE id=?', id);
      return fail(409, 'Aturan atau perangkat diperbarui. Rekam bacaan baru sesuai tugasmu.');
    }
    if (c.mode === 'ai' && hasAiConsent(c) && options.verify) {
      try {
        const verdict = verdictSchema.parse(await options.verify(buffer, mime, surahId, duration));
        const approved = aiProvider === 'gemini' && passes(verdict);
        await change("UPDATE recitations SET status=?,expires_at=?,feedback=?,reviewer='ai' WHERE id=? AND status='pending' AND reviewer IS NULL", approved ? 'approved' : 'pending', approved ? now() + c.unlock_minutes * 60_000 : null, approved ? verdict.feedback : `Perlu pemeriksaan orang tua. ${verdict.feedback}`, id);
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
  app.post('/recitations/:id/review', async req => {
    const a = await actor(req, 'parent'); const r = (await recFor(a, paramId(req))); const c = (await childFor(a, r.child_id));
    const { decision } = z.object({ decision: z.enum(['approve', 'reject']) }).parse(req.body);
    const approved = decision === 'approve';
    if (r.created_at < now() - 7 * day) return fail(410, 'Rekaman sudah kedaluwarsa.');
    const updated = (await change("UPDATE recitations SET status=?,expires_at=?,reviewer='parent',feedback=? WHERE id=? AND status='pending' AND used_at IS NULL", approved ? 'approved' : 'rejected', approved ? now() + c.unlock_minutes * 60_000 : null, approved ? 'Disetujui orang tua. Terima kasih sudah mengaji!' : 'Yuk, coba mengaji lagi. Orang tua ingin mendengar lebih jelas.', r.id));
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
  app.post('/tv/authorize', async req => {
    const a = await actor(req, 'child');
    const { recitationId, tvTransport } = z.object({ recitationId: z.string().uuid(), tvTransport: z.enum(['infrared', 'wifi']).default('infrared') }).parse(req.body);
    const r = (await recFor(a, recitationId)); const c = (await childFor(a, a.id));
    if (tvTransport !== c.tv_transport) return fail(409, 'Metode koneksi TV berubah. Muat ulang aplikasi dan pengaturan.');
    if (c.tv_transport === 'infrared' && c.tv_brand === 'none') return fail(400, 'Minta orang tua memilih profil TV dulu.');
    if (c.tv_transport === 'wifi' && (c.tv_platform === 'wol' ? !c.tv_mac : !isPrivateIpv4(c.tv_host))) return fail(400, 'Minta orang tua melengkapi pengaturan Wi-Fi TV dulu.');
    const used = (await change("UPDATE recitations SET used_at=? WHERE id=? AND child_id=? AND status='approved' AND used_at IS NULL AND expires_at>? AND created_at>?", now(), r.id, c.id, now(), now() - 7 * day));
    if (!used.changes) return fail(403, 'Ngaji perlu disetujui dulu. Izin hanya berlaku sekali dan bisa kedaluwarsa.');
    return { authorized: true, tvBrand: c.tv_brand, tvTransport: c.tv_transport, tvMac: c.tv_mac, tvPlatform: c.tv_platform, tvHost: c.tv_host, expiresAt: now() + 10_000 };
  });
  return app;
}
