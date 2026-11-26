import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGeminiTranscriber } from '../src/ai.js';
import { createDeepSeekTranscriber } from '../src/deepseek.js';
import { wav } from './fixtures.js';

test('Gemini adapter sends inline audio, stateless interaction, and JSON schema through the installed SDK', async () => {
  const transcript = 'قل هو الله أحد';
  let sent: Record<string, unknown> = {};
  const transcribe = createGeminiTranscriber('test-key-only', 'gemini-4-hydrogen', async (input, init) => {
    assert.match(String(input), /\/interactions$/);
    sent = JSON.parse(String(init?.body));
    return Response.json({ status: 'completed', model: 'gemini-4-hydrogen', steps: [
      { type: 'model_output', content: [{ type: 'text', text: JSON.stringify({ text: transcript }) }] },
    ] });
  });
  const result = await transcribe(Buffer.from('test-audio-bytes'), 'audio/mp4', 11);
  assert.equal(result, transcript);
  assert.equal(sent.store, false);
  assert.equal(sent.model, 'gemini-4-hydrogen');
  const serialized = JSON.stringify(sent);
  assert.match(serialized, /audio\/mp4/);
  assert.ok(serialized.includes(Buffer.from('test-audio-bytes').toString('base64')));
  assert.doesNotMatch(serialized, /Al-Ikhlas/);
  const formats = sent.response_format as { mime_type: string; schema: { required: string[] } }[];
  assert.equal(formats[0]?.mime_type, 'application/json');
  assert.deepEqual(formats[0]?.schema.required, ['text']);
});

test('DeepSeek receives PCM WAV and returns the provider transcript', async () => {
  const transcript = 'بسم الله الرحمن الرحيم. قل هو الله أحد، الله الصمد، لم يلد ولم يولد، ولم يكن له كفوا أحد.';
  const transcribe = createDeepSeekTranscriber('test-key-only', async (url, init) => {
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
    return Response.json({ text: transcript });
  });
  assert.equal(await transcribe(wav(11), 'audio/wav', 11), transcript);
  await assert.rejects(transcribe(wav(11), 'audio/wav', 121), /cannot be sent/);
});
