import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';
import type { Verify } from '../src/ai.js';

// Real PCM WAV fixtures exercise ffprobe rather than trusting client duration.
function wav(seconds: number) {
  const rate = 8000; const samples = rate * seconds;
  const b = Buffer.alloc(44 + samples * 2);
  b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVEfmt ', 8);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) b.writeInt16LE(Math.round(Math.sin(i * .2) * 5000), 44 + i * 2);
  return b;
}
function multipart(audio: Buffer, surahId = '112') {
  const boundary = 'jagabocil-test-boundary';
  return { headers: { 'content-type': `multipart/form-data; boundary=${boundary}` }, payload: Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="surahId"\r\n\r\n${surahId}\r\n--${boundary}\r\nContent-Disposition: form-data; name="audio"; filename="ngaji.wav"\r\nContent-Type: audio/wav\r\n\r\n`),
    audio, Buffer.from(`\r\n--${boundary}--\r\n`),
  ]) };
}
const root = await mkdtemp(join(tmpdir(), 'jagabocil-test-'));
let now = Date.now();
let verifier: Verify = async () => { throw Error('provider unavailable'); };
const app = await createApp({ dataDir: root, now: () => now, verify: (...args) => verifier(...args) });
const request = (method: 'GET' | 'POST' | 'PUT' | 'DELETE', url: string, token = '', payload?: unknown) => app.inject({ method, url, headers: token ? { authorization: `Bearer ${token}` } : {}, payload });
let parent: string, outsider: string, child: string, childId: string, recId: string;
const policy = { mode: 'manual', surahId: '112', minSeconds: 10, unlockMinutes: 15, tvBrand: 'lg', aiConsent: false };
async function upload(token = child, audio = wav(11), surah = '112') {
  const data = multipart(audio, surah);
  return app.inject({ method: 'POST', url: '/recitations', headers: { ...data.headers, authorization: `Bearer ${token}` }, payload: data.payload });
}
before(async () => {
  parent = (await request('POST', '/auth/register', '', { email: 'parent@example.com', name: 'Ayah', password: 'kata-sandi-kuat' })).json().token;
  outsider = (await request('POST', '/auth/register', '', { email: 'other@example.com', name: 'Lain', password: 'kata-sandi-kuat' })).json().token;
  const created = (await request('POST', '/children', parent, { name: 'Adek' })).json();
  childId = created.child.id;
  child = (await request('POST', '/pair', '', { code: created.pairing.code })).json().token;
  assert.ok(child);
  assert.equal((await request('POST', '/pair', '', { code: created.pairing.code })).statusCode, 400);
  await request('PUT', `/children/${childId}/policy`, parent, policy);
});
after(async () => { await app.close(); await rm(root, { recursive: true, force: true }); });

test('password authentication rejects wrong credentials and normalizes email', async () => {
  assert.equal((await request('POST', '/auth/login', '', { email: 'parent@example.com', password: 'password-salah' })).statusCode, 401);
  assert.equal((await request('POST', '/auth/login', '', { email: 'PARENT@example.com', password: 'kata-sandi-kuat' })).statusCode, 200);
  assert.equal((await request('POST', '/auth/register', '', { email: 'parent@example.com', name: 'Duplikat', password: 'kata-sandi-kuat' })).statusCode, 409);
});
test('dashboard and role boundaries require authentication', async () => {
  assert.equal((await request('GET', '/dashboard')).statusCode, 401);
  assert.equal((await request('POST', '/children', child, { name: 'Palsu' })).statusCode, 403);
  assert.equal((await request('PUT', `/children/${childId}/policy`, outsider, policy)).statusCode, 404);
  assert.equal((await request('GET', '/dashboard', outsider)).json().children.length, 0);
});
test('rejects fake, short, overlong, or wrong-surah audio and removes rejected files', async () => {
  for (const data of [Buffer.from('not audio'), wav(2), wav(181)]) assert.equal((await upload(child, data)).statusCode, 400);
  assert.equal((await upload(child, wav(11), '114')).statusCode, 400);
  assert.equal((await readdir(join(root, 'recordings'))).length, 0);
});
test('manual upload stays pending and is visible only to its family', async () => {
  const response = await upload(); assert.equal(response.statusCode, 201);
  const rec = response.json().recitation; recId = rec.id;
  assert.equal(rec.status, 'pending'); assert.ok(rec.duration >= 10);
  assert.equal((await request('GET', `/recitations/${recId}/audio`, outsider)).statusCode, 404);
  assert.equal((await request('GET', `/recitations/${recId}/audio`, parent)).statusCode, 200);
  assert.equal((await request('GET', `/recitations/${recId}/audio`)).statusCode, 401);
  assert.equal((await request('GET', '/dashboard', parent)).json().recitations[0].id, recId);
});
test('no power permission before approval; child and outsiders cannot approve', async () => {
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: recId })).statusCode, 403);
  assert.equal((await request('POST', `/recitations/${recId}/review`, child, { decision: 'approve' })).statusCode, 403);
  assert.equal((await request('POST', `/recitations/${recId}/review`, outsider, { decision: 'approve' })).statusCode, 404);
});
test('parent approval enables exactly one authorization, even with concurrent requests', async () => {
  assert.equal((await request('POST', `/recitations/${recId}/review`, parent, { decision: 'approve' })).statusCode, 200);
  assert.equal((await request('POST', `/recitations/${recId}/review`, parent, { decision: 'approve' })).statusCode, 409);
  const results = await Promise.all([request('POST', '/tv/authorize', child, { recitationId: recId }), request('POST', '/tv/authorize', child, { recitationId: recId })]);
  assert.deepEqual(results.map(r => r.statusCode).sort(), [200, 403]);
});
test('expired approvals cannot authorize power', async () => {
  const id = (await upload()).json().recitation.id;
  await request('POST', `/recitations/${id}/review`, parent, { decision: 'approve' });
  now += 16 * 60_000;
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: id })).statusCode, 403);
});
test('AI requires parent consent', async () => {
  assert.equal((await request('PUT', `/children/${childId}/policy`, parent, { ...policy, mode: 'ai', aiConsent: false })).statusCode, 400);
  assert.equal((await request('PUT', `/children/${childId}/policy`, child, { ...policy, mode: 'ai', aiConsent: true })).statusCode, 403);
});
test('AI outage falls back to parent review without granting power', async () => {
  await request('PUT', `/children/${childId}/policy`, parent, { ...policy, mode: 'ai', aiConsent: true });
  const rec = (await upload()).json().recitation;
  assert.equal(rec.status, 'pending'); assert.match(rec.feedback, /orang tua/);
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
  await request('POST', `/recitations/${rec.id}/review`, parent, { decision: 'reject' });
});
test('uncertain AI output stays pending; malformed output never grants permission', async () => {
  verifier = async () => ({ isRecitation: true, matchesSurah: true, coverage: .99, confidence: .8, feedback: 'Perlu didengar ulang.' });
  let rec = (await upload()).json().recitation; assert.equal(rec.status, 'pending');
  await request('POST', `/recitations/${rec.id}/review`, parent, { decision: 'reject' });
  verifier = async () => ({ isRecitation: true, matchesSurah: true, coverage: 2, confidence: 1, feedback: 'invalid' });
  rec = (await upload()).json().recitation; assert.equal(rec.status, 'pending');
  await request('POST', `/recitations/${rec.id}/review`, parent, { decision: 'reject' });
});
test('high-confidence complete recitation grants once, and policy changes revoke unused grants', async () => {
  verifier = async () => ({ isRecitation: true, matchesSurah: true, coverage: .99, confidence: .99, feedback: 'Bacaan lengkap.' });
  const rec = (await upload()).json().recitation;
  assert.equal(rec.status, 'approved'); assert.equal(rec.reviewer, 'ai');
  await request('PUT', `/children/${childId}/policy`, parent, policy);
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
});
test('AI cannot overwrite a parent decision made while verification is running', async () => {
  await request('PUT', `/children/${childId}/policy`, parent, { ...policy, mode: 'ai', aiConsent: true });
  let complete!: () => void;
  let began!: () => void;
  const started = new Promise<void>(resolve => { began = resolve; });
  const blocked = new Promise<void>(resolve => { complete = resolve; });
  verifier = async () => {
    began(); await blocked;
    return { isRecitation: true, matchesSurah: true, coverage: 1, confidence: 1, feedback: 'Lengkap.' };
  };
  const uploading = upload();
  await started;
  const rec = (await request('GET', '/dashboard', parent)).json().recitations.find((r: { status: string }) => r.status === 'pending');
  assert.ok(rec);
  await request('POST', `/recitations/${rec.id}/review`, parent, { decision: 'reject' });
  complete();
  assert.equal((await uploading).json().recitation.status, 'rejected');
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
  await request('PUT', `/children/${childId}/policy`, parent, policy);
});
test('high confidence on unrelated audio never passes', async () => {
  await request('PUT', `/children/${childId}/policy`, parent, { ...policy, mode: 'ai', aiConsent: true });
  verifier = async () => ({ isRecitation: false, matchesSurah: false, coverage: 1, confidence: 1, feedback: 'Bukan bacaan surah.' });
  const rec = (await upload()).json().recitation;
  assert.equal(rec.status, 'pending');
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 403);
  await request('PUT', `/children/${childId}/policy`, parent, policy);
});
test('deleting audio also removes its approval and protected playback', async () => {
  const rec = (await upload()).json().recitation;
  await request('POST', `/recitations/${rec.id}/review`, parent, { decision: 'approve' });
  assert.equal((await request('DELETE', `/recitations/${rec.id}`, outsider)).statusCode, 404);
  assert.equal((await request('DELETE', `/recitations/${rec.id}`, parent)).statusCode, 200);
  assert.equal((await request('GET', `/recitations/${rec.id}/audio`, parent)).statusCode, 404);
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: rec.id })).statusCode, 404);
});
test('expired pairing cannot be used and parent can revoke child devices', async () => {
  const pairing = (await request('POST', `/children/${childId}/pairing`, parent)).json().pairing;
  now += 11 * 60_000;
  assert.equal((await request('POST', '/pair', '', { code: pairing.code })).statusCode, 400);
  assert.equal((await request('DELETE', `/children/${childId}/devices`, parent)).statusCode, 200);
  assert.equal((await request('GET', '/dashboard', child)).statusCode, 401);
  assert.equal((await request('POST', '/tv/authorize', child, { recitationId: recId })).statusCode, 401);
});
test('server without AI key allows manual mode and rejects AI activation', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'jagabocil-manual-'));
  const manual = await createApp({ dataDir: dir });
  try {
    assert.equal((await manual.inject('/health')).json().aiAvailable, false);
    const token = (await manual.inject({ method: 'POST', url: '/auth/register', payload: { email: 'a@example.com', name: 'Ayah', password: 'kata-sandi-kuat' } })).json().token;
    const headers = { authorization: `Bearer ${token}` };
    const id = (await manual.inject({ method: 'POST', url: '/children', headers, payload: { name: 'Anak' } })).json().child.id;
    assert.equal((await manual.inject({ method: 'PUT', url: `/children/${id}/policy`, headers, payload: { ...policy, mode: 'ai', aiConsent: true } })).statusCode, 400);
  } finally { await manual.close(); await rm(dir, { recursive: true, force: true }); }
});
test('logout invalidates the session on the server', async () => {
  assert.equal((await request('POST', '/auth/logout', outsider)).statusCode, 200);
  assert.equal((await request('GET', '/dashboard', outsider)).statusCode, 401);
});
test('expired recordings cannot be reviewed or replayed and cleanup removes their files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'jagabocil-retention-'));
  let clock = Date.now();
  let retentionApp = await createApp({ dataDir: dir, now: () => clock });
  try {
    const token = (await retentionApp.inject({ method: 'POST', url: '/auth/register', payload: { email: 'r@example.com', name: 'Ayah', password: 'kata-sandi-kuat' } })).json().token;
    const headers = { authorization: `Bearer ${token}` };
    const created = (await retentionApp.inject({ method: 'POST', url: '/children', headers, payload: { name: 'Anak' } })).json();
    const childToken = (await retentionApp.inject({ method: 'POST', url: '/pair', payload: { code: created.pairing.code } })).json().token;
    const audio = multipart(wav(16));
    const id = (await retentionApp.inject({ method: 'POST', url: '/recitations', headers: { ...audio.headers, authorization: `Bearer ${childToken}` }, payload: audio.payload })).json().recitation.id;
    clock += 8 * 86_400_000;
    assert.equal((await retentionApp.inject({ method: 'GET', url: `/recitations/${id}/audio`, headers })).statusCode, 410);
    assert.equal((await retentionApp.inject({ method: 'POST', url: `/recitations/${id}/review`, headers, payload: { decision: 'approve' } })).statusCode, 410);
    await retentionApp.close();
    retentionApp = await createApp({ dataDir: dir, now: () => clock });
    assert.equal((await readdir(join(dir, 'recordings'))).length, 0);
    assert.equal((await retentionApp.inject({ method: 'GET', url: '/dashboard', headers })).json().recitations.length, 0);
  } finally { await retentionApp.close(); await rm(dir, { recursive: true, force: true }); }
});
