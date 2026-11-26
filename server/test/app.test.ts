import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';
import { postgresDatabase } from '../src/database.js';
import { createHash, randomUUID } from 'node:crypto';
import type { Verify } from '../src/ai.js';
import { isTaskSurah, quranText } from '../src/catalog.js';

import { wav, multipart } from './fixtures.js';
const root = await mkdtemp(join(tmpdir(), 'jagabocil-test-'));
let now = Date.now();
let verifier: Verify = async () => { throw Error('provider unavailable'); };
const schema = 'jagabocil_test_' + randomUUID().replaceAll('-', '');
const database = process.env.TEST_DATABASE_URL ? await postgresDatabase(process.env.TEST_DATABASE_URL, schema, true) : undefined;
const app = await createApp({ dataDir: root, database, now: () => now, verify: (...args) => verifier(...args) });
const request = (method: 'GET' | 'POST' | 'PUT' | 'DELETE', url: string, token = '', payload?: unknown) => app.inject({ method, url, headers: token ? { authorization: `Bearer ${token}` } : {}, payload });
let parent: string, outsider: string, child: string, childId: string, recId: string;
const policy = { mode: 'manual', surahId: '112', minSeconds: 10, unlockMinutes: 15, tvBrand: 'lg', aiConsent: false };
async function upload(token = child, audio = wav(11), surah = '112') {
  const data = multipart(audio, surah);
  return app.inject({ method: 'POST', url: '/recitations', headers: { ...data.headers, authorization: `Bearer ${token}` }, payload: data.payload });
}
before(async () => {
  parent = (await request('POST', '/auth/register', '', { username: 'OrangTua', password: '1234' })).json().token;
  outsider = (await request('POST', '/auth/register', '', { username: 'KeluargaLain', password: 'kata-sandi-kuat' })).json().token;
  const created = (await request('POST', '/children', parent, { name: 'Adek' })).json();
  childId = created.child.id;
  child = (await request('POST', '/pair', '', { code: created.pairing.code })).json().token;
  assert.ok(child);
  assert.equal((await request('POST', '/pair', '', { code: created.pairing.code })).statusCode, 400);
  await request('PUT', `/children/${childId}/policy`, parent, policy);
});
after(async () => { if (database) await database.change(`DROP SCHEMA \"${schema}\" CASCADE`); await app.close(); await rm(root, { recursive: true, force: true }); });

test('username authentication accepts a four-character password and ignores username case', async () => {
  assert.equal((await request('POST', '/auth/login', '', { username: 'OrangTua', password: '123' })).statusCode, 401);
  const loggedIn = await request('POST', '/auth/login', '', { username: ' ORANGTUA ', password: '1234' });
  assert.equal(loggedIn.statusCode, 200);
  assert.equal(loggedIn.json().name, 'OrangTua');
  assert.equal((await request('POST', '/auth/register', '', { username: 'orangtua', password: '1234' })).statusCode, 409);
  assert.equal((await request('POST', '/auth/register', '', { username: 'Baru', password: '123' })).statusCode, 400);
  assert.equal((await request('POST', '/auth/register', '', { username: 'baru@example.com', password: '1234' })).statusCode, 400);
});
test('dashboard and role boundaries require authentication', async () => {
  assert.equal((await request('GET', '/dashboard')).statusCode, 401);
  assert.equal((await request('POST', '/children', child, { name: 'Palsu' })).statusCode, 403);
  assert.equal((await request('PUT', `/children/${childId}/policy`, outsider, policy)).statusCode, 404);
  assert.equal((await request('GET', '/dashboard', outsider)).json().children.length, 0);
});
test('a freshly linked child can configure and test one TV without unlocking daily power', async () => {
  const profile = (await request('POST', '/children', parent, { name: 'Anak baru' })).json();
  const linked = (await request('POST', '/pair', '', { code: profile.pairing.code })).json().token;
  const target = { platform: 'androidtv', host: '192.168.1.20' };
  const dashboard = async () => (await request('GET', '/dashboard', linked)).json();
  assert.equal((await dashboard()).tvSetupUntil, now + 10 * 60_000);
  assert.equal((await request('POST', '/tv/setup', parent, target)).statusCode, 403);
  assert.equal((await request('POST', '/tv/setup', linked, { ...target, host: '8.8.8.8' })).statusCode, 400);
  const results = await Promise.all([
    request('POST', '/tv/setup', linked, target),
    request('POST', '/tv/setup', linked, { ...target, host: '192.168.1.21' }),
  ]);
  assert.deepEqual(results.map(r => r.statusCode).sort(), [200, 409]);
  const saved = results.find(r => r.statusCode === 200)!.json();
  assert.equal(saved.permit.tvHost, saved.child.tvHost);
  assert.equal(saved.permit.expiresAt, now + 10_000);
  assert.equal((await dashboard()).tvSetupUntil, null);
  assert.equal((await dashboard()).children[0].tvHost, saved.child.tvHost);
  const audio = multipart(wav(16), '112');
  const rec = await app.inject({ method: 'POST', url: '/recitations', remoteAddress: '192.0.2.1', headers: { ...audio.headers, authorization: `Bearer ${linked}` }, payload: audio.payload });
  assert.equal(rec.statusCode, 201);
  assert.equal((await request('POST', '/tv/authorize', linked, { recitationId: rec.json().recitation.id, tvTransport: 'wifi' })).statusCode, 403);
  await request('DELETE', `/recitations/${rec.json().recitation.id}`, parent);
});
test('TV setup expires and never overwrites a TV set by the parent', async () => {
  const profile = (await request('POST', '/children', parent, { name: 'Adik' })).json();
  const linked = (await request('POST', '/pair', '', { code: profile.pairing.code })).json().token;
  const target = { platform: 'lg', host: '192.168.1.20' };
  await request('PUT', `/children/${profile.child.id}/policy`, parent, policy);
  assert.equal((await request('POST', '/tv/setup', linked, target)).statusCode, 409);
  await request('PUT', `/children/${profile.child.id}/policy`, parent, { ...policy, tvBrand: 'none' });
  const pair = (await request('POST', `/children/${profile.child.id}/pairing`, parent)).json().pairing;
  const relinked = (await request('POST', '/pair', '', { code: pair.code })).json().token;
  const originalTime = now;
  try {
    now += 10 * 60_000;
    assert.equal((await request('GET', '/dashboard', relinked)).json().tvSetupUntil, null);
    assert.equal((await request('POST', '/tv/setup', relinked, target)).statusCode, 409);
  } finally { now = originalTime; }
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
  assert.equal(rec.readyAt, now);
  assert.equal(rec.reviewedAt, null);
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
  const decision = await request('POST', `/recitations/${recId}/review`, parent, { decision: 'approve' });
  assert.equal(decision.statusCode, 200);
  assert.equal(decision.json().recitation.reviewedAt, now);
  assert.equal((await request('GET', '/dashboard', child)).json().recitations[0].reviewedAt, now);
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
test('Wi-Fi policy rejects invalid MACs, public hosts, and child changes', async () => {
  const wifi = { ...policy, tvBrand: 'none', tvTransport: 'wifi', tvPlatform: 'wol', tvMac: '34:12:98:ab:cd:ef' };
  for (const mac of ['', '00:00:00:00:00:00', 'FF:FF:FF:FF:FF:FF', '01:00:5E:00:00:01', 'not-a-mac']) {
    assert.equal((await request('PUT', `/children/${childId}/policy`, parent, { ...wifi, tvMac: mac })).statusCode, 400);
  }
  for (const host of ['8.8.8.8', '127.0.0.1', '169.254.169.254', 'example.com', '192.168.1.300', '192.168.01.20']) {
    assert.equal((await request('PUT', `/children/${childId}/policy`, parent, { ...wifi, tvPlatform: 'samsung', tvHost: host })).statusCode, 400);
  }
  assert.equal((await request('PUT', `/children/${childId}/policy`, child, wifi)).statusCode, 403);
});
test('all four Wi-Fi profiles keep approval, transport compatibility, and single-use rules', async () => {
  for (const platform of ['wol', 'androidtv', 'samsung', 'lg']) {
    const wifi = { ...policy, tvBrand: 'none', tvTransport: 'wifi', tvPlatform: platform, tvHost: platform === 'wol' ? '' : '192.168.1.20', tvMac: '34:12:98:ab:cd:ef' };
    const saved = await request('PUT', `/children/${childId}/policy`, parent, wifi);
    assert.equal(saved.statusCode, 200);
    assert.equal(saved.json().child.tvMac, '34:12:98:AB:CD:EF');
    const response = await upload(); assert.equal(response.statusCode, 201);
    const id = response.json().recitation.id;
    assert.equal((await request('POST', '/tv/authorize', child, { recitationId: id, tvTransport: 'wifi' })).statusCode, 403);
    await request('POST', `/recitations/${id}/review`, parent, { decision: 'approve' });
    // Legacy clients and stale transport settings must not consume the grant.
    assert.equal((await request('POST', '/tv/authorize', child, { recitationId: id })).statusCode, 409);
    const allowed = await request('POST', '/tv/authorize', child, { recitationId: id, tvTransport: 'wifi', tvHost: '8.8.8.8', tvPlatform: 'injected' });
    assert.equal(allowed.statusCode, 200);
    assert.equal(allowed.json().tvPlatform, platform);
    assert.equal(allowed.json().tvHost, wifi.tvHost);
    assert.equal(allowed.json().tvTransport, 'wifi');
    assert.equal((await request('POST', '/tv/authorize', child, { recitationId: id, tvTransport: 'wifi' })).statusCode, 403);
  }
  await request('PUT', `/children/${childId}/policy`, parent, policy);
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
    const token = (await manual.inject({ method: 'POST', url: '/auth/register', payload: { username: 'Ayah', password: 'kata-sandi-kuat' } })).json().token;
    const headers = { authorization: `Bearer ${token}` };
    const id = (await manual.inject({ method: 'POST', url: '/children', headers, payload: { name: 'Anak' } })).json().child.id;
    assert.equal((await manual.inject({ method: 'PUT', url: `/children/${id}/policy`, headers, payload: { ...policy, mode: 'ai', aiConsent: true } })).statusCode, 400);
  } finally { await manual.close(); await rm(dir, { recursive: true, force: true }); }
});
test('a switch from Gemini to DeepSeek needs a fresh opt-in and keeps parent approval', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'jagabocil-provider-'));
  let calls = 0;
  const verify: Verify = async () => {
    calls++;
    return { isRecitation: true, matchesSurah: true, coverage: 1, confidence: 1, feedback: 'Teks cocok.' };
  };
  let family = await createApp({ dataDir: dir, verify });
  try {
    const registration = await family.inject({ method: 'POST', url: '/auth/register', payload: { username: 'Ayah', password: 'kata-sandi-kuat' } });
    const parentHeaders = { authorization: `Bearer ${registration.json().token}` };
    const created = await family.inject({ method: 'POST', url: '/children', headers: parentHeaders, payload: { name: 'Anak' } });
    const paired = await family.inject({ method: 'POST', url: '/pair', payload: { code: created.json().pairing.code } });
    const childHeaders = { authorization: `Bearer ${paired.json().token}` };
    const policyUrl = `/children/${created.json().child.id}/policy`;
    const optIn = () => family.inject({ method: 'PUT', url: policyUrl, headers: parentHeaders, payload: { ...policy, mode: 'ai', aiConsent: true } });
    const data = multipart(wav(11));
    const submit = () => family.inject({ method: 'POST', url: '/recitations', headers: { ...data.headers, ...childHeaders }, payload: data.payload });
    assert.equal((await optIn()).statusCode, 200);
    await family.close();
    family = await createApp({ dataDir: dir, verify, aiProvider: 'deepseek' });
    const dashboard = (await family.inject({ url: '/dashboard', headers: parentHeaders })).json();
    assert.equal(dashboard.children[0].mode, 'manual');
    assert.equal(dashboard.children[0].aiConsent, false);
    assert.equal((await submit()).json().recitation.status, 'pending');
    assert.equal(calls, 0);
    assert.equal((await optIn()).statusCode, 200);
    const rec = (await submit()).json().recitation;
    assert.equal(calls, 1);
    assert.equal(rec.status, 'pending');
    assert.match(rec.feedback, /Teks cocok/);
    const authorize = () => family.inject({ method: 'POST', url: '/tv/authorize', headers: childHeaders, payload: { recitationId: rec.id } });
    assert.equal((await authorize()).statusCode, 403);
    const review = await family.inject({ method: 'POST', url: `/recitations/${rec.id}/review`, headers: parentHeaders, payload: { decision: 'approve' } });
    assert.equal(review.statusCode, 200);
    assert.equal((await authorize()).statusCode, 200);
    assert.equal((await authorize()).statusCode, 403);
  } finally { await family.close(); await rm(dir, { recursive: true, force: true }); }
});
test('logout invalidates the session on the server', async () => {
  assert.equal((await request('POST', '/auth/logout', outsider)).statusCode, 200);
  assert.equal((await request('GET', '/dashboard', outsider)).statusCode, 401);
});
test('recovery requires the current password to issue and atomically invalidates the code and parent sessions', async () => {
  const username = 'Pemulihan';
  const password = 'lama';
  const registration = await request('POST', '/auth/register', '', { username, password });
  assert.equal(registration.statusCode, 201);
  const session = registration.json().token;
  const secondSession = (await request('POST', '/auth/login', '', { username, password })).json().token;
  const profile = (await request('POST', '/children', session, { name: 'Anak' })).json();
  const childSession = (await request('POST', '/pair', '', { code: profile.pairing.code })).json().token;
  assert.equal((await request('POST', '/auth/recovery-code', childSession, { password })).statusCode, 403);
  assert.equal((await request('POST', '/auth/recovery-code', session, { password: 'salah' })).statusCode, 403);
  const issue = () => request('POST', '/auth/recovery-code', session, { password });
  const first = (await issue()).json().code;
  const current = (await issue()).json().code;
  assert.match(current, /^[0-9A-F]{8}(?:-[0-9A-F]{8}){3}$/);
  assert.notEqual(first, current);
  const dashboard = (await request('GET', '/dashboard', session)).json();
  assert.equal(dashboard.recoveryConfigured, true);
  assert.equal(JSON.stringify(dashboard).includes(current), false);

  const recover = (code: string) => request('POST', '/auth/recover', '', { username, code, password: 'baru' });
  assert.equal((await recover(first)).statusCode, 401);
  const attempts = await Promise.all([recover(current.toLowerCase()), recover(current.replaceAll('-', ' '))]);
  assert.deepEqual(attempts.map(r => r.statusCode).sort(), [200, 401]);
  assert.equal((await request('GET', '/dashboard', session)).statusCode, 401);
  assert.equal((await request('GET', '/dashboard', secondSession)).statusCode, 401);
  assert.equal((await request('GET', '/dashboard', childSession)).statusCode, 200);
  assert.equal((await request('POST', '/auth/login', '', { username, password })).statusCode, 401);
  const login = await request('POST', '/auth/login', '', { username, password: 'baru' });
  assert.equal(login.statusCode, 200);
  const recoveredDashboard = (await request('GET', '/dashboard', login.json().token)).json();
  assert.equal(recoveredDashboard.children[0].id, profile.child.id);
  assert.equal(recoveredDashboard.recoveryConfigured, false);
});
test('expired recordings cannot be reviewed or replayed and cleanup removes their files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'jagabocil-retention-'));
  let clock = Date.now();
  let retentionApp = await createApp({ dataDir: dir, now: () => clock });
  try {
    const token = (await retentionApp.inject({ method: 'POST', url: '/auth/register', payload: { username: 'Ayah', password: 'kata-sandi-kuat' } })).json().token;
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

test('the Quran catalog preserves the full source and downloads one surah at a time', async () => {
  const hash = createHash('sha256');
  let verseCount = 0;
  let juz30Count = 0;
  for (let chapter = 1; chapter <= 114; chapter++) {
    const text = quranText(String(chapter))!;
    assert.ok(text.ayahs.length);
    for (const [index, ayah] of text.ayahs.entries()) hash.update(`${chapter}|${index + 1}|${ayah}\n`);
    verseCount += text.ayahs.length;
    if (isTaskSurah(String(chapter))) juz30Count++;
  }
  assert.equal(verseCount, 6236);
  assert.equal(juz30Count, 37);
  assert.equal(hash.digest('hex'), '76781116345a687997d203ae154d64ceae9d06722139cd0e454c0d446f6da1d8');
  const download = (await request('GET', '/quran/surahs/2')).json();
  assert.deepEqual(download, quranText('2'));
  assert.equal(download.ayahs.length, 286);
  assert.match(download.notice, /Tanzil Project/);
  for (const id of ['0', '02', '115']) {
    assert.equal((await request('GET', `/quran/surahs/${id}`)).statusCode, 404);
  }
});

test('a long Juz 30 assignment accepts the matching recording and old clients can open it', async () => {
  const session = (await request('POST', '/auth/register', '', { username: 'Juz30Demo', password: 'test' })).json().token;
  const created = (await request('POST', '/children', session, { name: 'Anak' })).json();
  const childSession = (await request('POST', '/pair', '', { code: created.pairing.code })).json().token;
  const policyPath = `/children/${created.child.id}/policy`;
  assert.equal((await request('PUT', policyPath, session, { ...policy, surahId: '77' })).statusCode, 400);
  assert.equal((await request('PUT', policyPath, session, { ...policy, surahId: '78' })).statusCode, 200);
  assert.equal((await upload(childSession, wav(181), '112')).statusCode, 400);
  assert.equal((await upload(childSession, wav(481), '78')).statusCode, 400);
  const submitted = await upload(childSession, wav(181), '78');
  assert.equal(submitted.statusCode, 201);
  assert.equal(submitted.json().recitation.surahId, '78');
  const dashboard = (await request('GET', '/dashboard?quran=local', childSession)).json();
  assert.equal(dashboard.juz30Tasks, true);
  assert.deepEqual(dashboard.surahs, []);
  assert.equal(dashboard.quranNotice, undefined);
  assert.equal(dashboard.children[0].surahId, '78');
  const legacy = (await request('GET', '/dashboard', childSession)).json();
  const assigned = legacy.surahs.find((surah: { id: string }) => surah.id === '78');
  assert.equal(assigned.verses, 40);
  assert.equal(assigned.arabic.startsWith(quranText('1')!.ayahs[0]!), false);
});

test('parent can add credentials to an existing child and reset its login', async () => {
  const other = (await request('POST', '/auth/login', '', { username: 'KeluargaLain', password: 'kata-sandi-kuat' })).json().token;
  const profile = (await request('POST', '/children', parent, { name: 'Anak lama' })).json();
  const path = `/children/${profile.child.id}/account`;
  const oldToken = (await request('POST', '/pair', '', { code: profile.pairing.code })).json().token;
  const newCredentials = { username: 'AnakLama', password: 'katabaharu' };
  assert.equal((await request('PUT', path, other, newCredentials)).statusCode, 404);
  const saved = await request('PUT', path, parent, newCredentials);
  assert.equal(saved.statusCode, 200);
  assert.equal(saved.json().child.id, profile.child.id);
  assert.equal(saved.json().child.username, 'anaklama');
  assert.equal(saved.json().child.password, undefined);
  assert.equal((await request('GET', '/dashboard', oldToken)).statusCode, 401);
  assert.equal((await request('POST', `/children/${profile.child.id}/pairing`, parent)).statusCode, 409);

  const login = await request('POST', '/auth/child/login', '', { ...newCredentials, username: ' ANAKLAMA ' });
  assert.equal(login.statusCode, 200);
  assert.equal(login.json().role, 'child');
  const token = login.json().token;
  assert.equal((await request('GET', '/dashboard?quran=local', token)).json().children[0].id, profile.child.id);
  assert.equal((await request('PUT', path, token, newCredentials)).statusCode, 403);
  assert.equal((await request('PUT', path, parent, { ...newCredentials, password: 'resetbaru' })).statusCode, 200);
  assert.equal((await request('GET', '/dashboard', token)).statusCode, 401);
  assert.equal((await request('POST', '/auth/child/login', '', newCredentials)).statusCode, 401);
  const reLogin = await request('POST', '/auth/child/login', '', { ...newCredentials, password: 'resetbaru' });
  assert.equal(reLogin.statusCode, 200);
  assert.equal((await request('GET', '/dashboard', reLogin.json().token)).json().children[0].id, profile.child.id);
});

test('child account chooses a TV and surah, then needs review for one power permit', async () => {
  const other = (await request('POST', '/auth/login', '', { username: 'KeluargaLain', password: 'kata-sandi-kuat' })).json().token;
  const credentials = { username: 'Nara', password: 'katabaru' };
  const created = await request('POST', '/children/accounts', parent, { ...credentials, name: 'Nara' });
  assert.equal(created.statusCode, 201);
  const profile = created.json().child;
  assert.equal(profile.surahId, '112');
  assert.equal((await request('POST', '/children/accounts', other, { ...credentials, name: 'Nara lain', username: 'NARA' })).statusCode, 409);
  const login = await request('POST', '/auth/child/login', '', credentials);
  assert.equal(login.statusCode, 200);
  const token = login.json().token;
  const submit = (surahId: string, selfSelected: boolean) => {
    const data = multipart(wav(16), surahId);
    return app.inject({
      method: 'POST', url: selfSelected ? '/recitations?selection=self' : '/recitations',
      remoteAddress: '127.0.0.2', headers: { ...data.headers, authorization: `Bearer ${token}` }, payload: data.payload,
    });
  };
  const tv = { tvBrand: 'none', tvTransport: 'wifi', tvPlatform: 'androidtv', tvHost: '192.168.1.24' };
  assert.equal((await request('PUT', '/child/tv', parent, tv)).statusCode, 403);
  assert.equal((await request('PUT', '/child/tv', token, { ...tv, tvHost: '8.8.8.8' })).statusCode, 400);
  assert.equal((await request('POST', '/tv/setup', token, { platform: 'androidtv', host: tv.tvHost })).statusCode, 403);
  const selectedTv = await request('PUT', '/child/tv', token, tv);
  assert.equal(selectedTv.statusCode, 200);
  assert.equal(selectedTv.json().permit, undefined);
  assert.equal(selectedTv.json().child.tvHost, tv.tvHost);
  const tvRevision = selectedTv.json().child.tvRevision;

  assert.equal((await submit('114', false)).statusCode, 400);
  assert.equal((await submit('77', true)).statusCode, 400);
  const submitted = await submit('114', true);
  assert.equal(submitted.statusCode, 201);
  const recitation = submitted.json().recitation;
  assert.equal(recitation.surahId, '114');
  const authorize = (revision: number) => request('POST', '/tv/authorize', token,
    { recitationId: recitation.id, tvTransport: tv.tvTransport, tvRevision: revision });
  assert.equal((await authorize(tvRevision)).statusCode, 403);
  const reviewPath = `/recitations/${recitation.id}/review`;
  assert.equal((await request('POST', reviewPath, other, { decision: 'approve' })).statusCode, 404);
  assert.equal((await request('POST', reviewPath, parent, { decision: 'approve' })).statusCode, 200);
  const anotherTv = await request('PUT', '/child/tv', token, { ...tv, tvHost: '192.168.1.25' });
  assert.equal(anotherTv.statusCode, 200);
  assert.equal((await authorize(tvRevision)).statusCode, 409);
  const permit = await authorize(anotherTv.json().child.tvRevision);
  assert.equal(permit.statusCode, 200);
  assert.equal(permit.json().tvHost, '192.168.1.25');
  assert.equal((await authorize(anotherTv.json().child.tvRevision)).statusCode, 403);
});
