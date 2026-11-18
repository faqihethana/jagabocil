import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVerifier, passes } from '../src/ai.js';
import { createDeepSeekVerifier } from '../src/deepseek.js';
import { wav } from './fixtures.js';

test('Gemini adapter sends inline audio, stateless interaction, and JSON schema through the installed SDK', async () => {
  const verdict = { isRecitation: true, matchesSurah: true, coverage: .99, confidence: .99, feedback: 'Bacaan terdengar lengkap.' };
  let sent: Record<string, unknown> = {};
  const verify = createVerifier('test-key-only', 'gemini-4-hydrogen', async (input, init) => {
    assert.match(String(input), /\/interactions$/);
    sent = JSON.parse(String(init?.body));
    return Response.json({ status: 'completed', model: 'gemini-4-hydrogen', steps: [
      { type: 'model_output', content: [{ type: 'text', text: JSON.stringify(verdict) }] },
    ] });
  });
  const result = await verify(Buffer.from('test-audio-bytes'), 'audio/mp4', '112', 11);
  assert.deepEqual(result, verdict);
  assert.equal(sent.store, false);
  assert.equal(sent.model, 'gemini-4-hydrogen');
  const serialized = JSON.stringify(sent);
  assert.match(serialized, /audio\/mp4/);
  assert.ok(serialized.includes(Buffer.from('test-audio-bytes').toString('base64')));
  assert.match(serialized, /Al-Ikhlas/);
  const formats = sent.response_format as { mime_type: string; schema: { required: string[] } }[];
  assert.equal(formats[0]?.mime_type, 'application/json');
  assert.ok(formats[0]?.schema.required.includes('confidence'));
  assert.equal(passes(result), true);
});

test('DeepSeek receives PCM WAV and recognizes a matching transcript without approving', async () => {
  let opening = 'بسم الله الرحمن الرحيم. ';
  const verify = createDeepSeekVerifier('test-key-only', async (url, init) => {
    assert.equal(url, 'https://api.deepseek.com/v1/tools/asr');
    assert.equal(init?.headers && new Headers(init.headers).get('authorization'), 'Bearer test-key-only');
    assert.ok(init?.body instanceof FormData);
    const file = init.body.get('file');
    assert.ok(file instanceof File);
    const audio = Buffer.from(await file.arrayBuffer());
    assert.equal(file.type, 'audio/wav');
    assert.equal(audio.toString('ascii', 8, 12), 'WAVE');
    assert.equal(audio.readUInt32LE(24), 16_000);
    assert.equal(audio.readUInt16LE(34), 16);
    return Response.json({ text: opening + 'قل هو الله أحد، الله الصمد، لم يلد ولم يولد، ولم يكن له كفوا أحد.' });
  });
  const result = await verify(wav(11), 'audio/wav', '112', 11);
  assert.equal(result.matchesSurah, true);
  assert.equal(passes(result), false);
  opening = '';
  assert.equal((await verify(wav(11), 'audio/wav', '112', 11)).matchesSurah, true);
  await assert.rejects(verify(wav(11), 'audio/wav', '112', 121), /cannot be sent/);
});
