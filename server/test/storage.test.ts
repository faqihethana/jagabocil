import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { mkdtemp, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { S3Client } from '@aws-sdk/client-s3';
import { FetchHttpHandler } from '@smithy/fetch-http-handler';
import { s3Recordings, verifyPrivateS3 } from '../src/recordings.js';
import { createApp } from '../src/app.js';
import { sqliteDatabase } from '../src/sqlite.js';
import { postgresDatabase } from '../src/database.js';
import { wav, multipart } from './fixtures.js';

// Local S3 wire fixture: real AWS SDK signing/HTTP/body handling, no AWS credentials or objects.
const objects = new Map<string, Buffer>();
let publicRead = false, failPut = false, failDelete = false;
const requests: { method: string; key: string; encryption?: string; cache?: string }[] = [];
const service = createServer(async (req, res) => {
  const key = decodeURIComponent(new URL(req.url!, 'http://localhost').pathname).slice('/test-bucket/'.length);
  requests.push({ method: req.method!, key, encryption: req.headers['x-amz-server-side-encryption'] as string, cache: req.headers['cache-control'] as string });
  const denied = !req.headers.authorization && !publicRead || req.method === 'PUT' && failPut || req.method === 'DELETE' && failDelete;
  if (denied) { res.writeHead(403, { 'content-type': 'application/xml' }); res.end('<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>'); return; }
  if (req.method === 'PUT') {
    const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(Buffer.from(chunk));
    objects.set(key, Buffer.concat(chunks)); res.writeHead(200, { ETag: '"fixture"' }); res.end();
  } else if (req.method === 'GET' && objects.has(key)) {
    const bytes = objects.get(key)!; res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': bytes.length }); res.end(bytes);
  } else if (req.method === 'DELETE') { objects.delete(key); res.writeHead(204); res.end(); }
  else { res.writeHead(404); res.end(); }
});
await new Promise<void>(resolve => service.listen(0, '127.0.0.1', resolve));
const endpoint = `http://127.0.0.1:${(service.address() as { port: number }).port}`;
const settings = { bucket: 'test-bucket', region: 'ap-southeast-3', prefix: 'jagabocil/test', endpoint, forcePathStyle: true };
const client = new S3Client({ ...settings, credentials: { accessKeyId: 'test-access-only', secretAccessKey: 'test-secret-only' }, requestHandler: new FetchHttpHandler(), maxAttempts: 1 });
const recordings = s3Recordings(settings, client);
const root = await mkdtemp(join(tmpdir(), 'jagabocil-s3-'));
const schema = 'jagabocil_storage_' + randomUUID().replaceAll('-', '');
const openDb = () => process.env.TEST_DATABASE_URL ? postgresDatabase(process.env.TEST_DATABASE_URL, schema, true) : sqliteDatabase(join(root, 'jagabocil.sqlite'));
let database = await openDb();
let now = Date.now();
let app = await createApp({ dataDir: root, database, recordings, now: () => now });
let parent: string, child: string, outsider: string, childId: string;
const api = (method: 'GET'|'POST'|'PUT'|'DELETE', url: string, token: string, payload?: unknown) => app.inject({ method, url, headers: token ? { authorization: `Bearer ${token}` } : {}, payload });
const upload = () => {
  const parts = multipart(wav(11));
  return app.inject({ method: 'POST', url: '/recitations', payload: parts.payload, headers: { ...parts.headers, authorization: `Bearer ${child}` } });
};
before(async () => {
  parent = (await api('POST', '/auth/register', '', { email: 's3@example.com', name: 'Ayah', password: 'kata-sandi-kuat' })).json().token;
  outsider = (await api('POST', '/auth/register', '', { email: 'outsider@example.com', name: 'Lain', password: 'kata-sandi-kuat' })).json().token;
  const created = (await api('POST', '/children', parent, { name: 'Adek' })).json(); childId = created.child.id;
  child = (await api('POST', '/pair', '', { code: created.pairing.code })).json().token;
  await api('PUT', `/children/${childId}/policy`, parent, { mode: 'manual', surahId: '112', minSeconds: 10, unlockMinutes: 15, tvBrand: 'lg', aiConsent: false });
});
after(async () => {
  if (process.env.TEST_DATABASE_URL) await database.change(`DROP SCHEMA "${schema}" CASCADE`);
  await app.close(); client.destroy();
  await new Promise<void>(resolve => service.close(() => resolve()));
  await rm(root, { recursive: true, force: true });
});

test('S3 startup privacy probe accepts denied anonymous reads and removes only its own harmless probe', async () => {
  await verifyPrivateS3(settings, client);
  assert.equal(objects.size, 0);
  publicRead = true;
  try { await assert.rejects(verifyPrivateS3(settings, client), /privacy check failed/); }
  finally { publicRead = false; }
  assert.equal(objects.size, 0);
});
test('S3 roundtrip keeps audio private behind family access and preserves single-use approval', async () => {
  const response = await upload(); assert.equal(response.statusCode, 201);
  const rec = response.json().recitation;
  assert.equal(rec.status, 'pending');
  assert.equal((await readdir(join(root, 'recordings'))).length, 0);
  assert.equal((await readdir(join(root, 'tmp'))).length, 0);
  const put = requests.find(r => r.method === 'PUT' && r.key.startsWith(`jagabocil/test/recordings/${childId}/`))!;
  assert.ok(put.key.startsWith(`jagabocil/test/recordings/${childId}/`));
  assert.equal(put.encryption, 'AES256'); assert.equal(put.cache, 'private, no-store');
  const before = requests.filter(r => r.method === 'GET').length;
  assert.equal((await api('GET', `/recitations/${rec.id}/audio`, '')).statusCode, 401);
  assert.equal((await api('GET', `/recitations/${rec.id}/audio`, outsider)).statusCode, 404);
  assert.equal(requests.filter(r => r.method === 'GET').length, before);
  const audio = await api('GET', `/recitations/${rec.id}/audio`, parent);
  assert.equal(audio.statusCode, 200); assert.deepEqual(audio.rawPayload, wav(11));
  assert.equal((await api('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
  await api('POST', `/recitations/${rec.id}/review`, parent, { decision: 'approve' });
  assert.equal((await api('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 200);
  assert.equal((await api('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
  assert.equal((await api('DELETE', `/recitations/${rec.id}`, parent)).statusCode, 200);
  assert.equal(objects.size, 0);
});
test('S3 adapter refuses other projects, malformed keys, and arbitrary prefix configuration', async () => {
  const before = requests.length;
  for (const key of ['s3:duit/goals/private.png', 's3:jagabocil/test/../elsewhere', 's3:jagabocil/test/recordings/x/y.audio', 'https://example.com/file']) {
    await assert.rejects(recordings.get(key)); await assert.rejects(recordings.remove(key));
  }
  assert.throws(() => s3Recordings({ ...settings, prefix: 'duit' }, client));
  assert.equal(requests.length, before);
});
test('failed uploads are tracked for cleanup, hidden from review, and never unlock power', async () => {
  failPut = true;
  try { assert.equal((await upload()).statusCode, 503); } finally { failPut = false; }
  const failed = await database.one<{ id: string; status: string }>("SELECT id,status FROM recitations WHERE status='failed'");
  assert.ok(failed);
  assert.equal((await api('GET', '/dashboard', parent)).json().recitations.length, 0);
  assert.equal((await api('POST', `/recitations/${failed.id}/review`, parent, { decision: 'approve' })).statusCode, 409);
  assert.equal((await api('POST', '/tv/authorize', child, { recitationId: failed.id })).statusCode, 403);
});
test('retention keeps metadata on S3 deletion failure and retries after restart', async () => {
  now += 8 * 86400000; await app.close();
  failDelete = true; database = await openDb();
  app = await createApp({ dataDir: root, database, recordings, now: () => now });
  assert.equal((await database.all('SELECT id FROM recitations')).length, 1);
  await app.close(); failDelete = false; database = await openDb();
  app = await createApp({ dataDir: root, database, recordings, now: () => now });
  assert.equal((await database.all('SELECT id FROM recitations')).length, 0);
});
