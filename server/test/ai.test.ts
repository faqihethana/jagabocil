import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVerifier, passes } from '../src/ai.js';

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
  const result = await verify(Buffer.from('test-audio-bytes'), 'audio/mp4', '112');
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
