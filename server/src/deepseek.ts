import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rmdir, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { z } from 'zod';
import { transcriptionSchema, type Transcribe } from './ai.js';
import { storyPrompt, storySchema, storySystem, type WriteStory } from './book-library.js';

const run = promisify(execFile);
export function createDeepSeekTranscriber(key: string, fetchAudio = globalThis.fetch): Transcribe {
  return async (audio, _mediaType, duration) => {
    if (!Number.isFinite(duration) || duration <= 0 || duration > 120) {
      throw Error('Recording cannot be sent to DeepSeek ASR.');
    }

    const dir = await mkdtemp(join(tmpdir(), 'jagabocil-asr-'));
    const input = join(dir, 'input.audio');
    const output = join(dir, 'speech.wav');
    let wav: Buffer;
    try {
      // Android M4A may place its metadata at the end and require a seekable input.
      await writeFile(input, audio, { mode: 0o600 });
      await run('ffmpeg', ['-nostdin', '-v', 'error', '-protocol_whitelist', 'file,pipe',
        '-format_whitelist', 'mov,wav', '-i', input, '-map', '0:a:0', '-map_metadata', '-1',
        '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-t', '121', output],
      { timeout: 10_000, maxBuffer: 64_000 });
      wav = await readFile(output);
    } finally {
      await Promise.all([input, output].map(path => unlink(path).catch(() => {})));
      await rmdir(dir).catch(() => {});
    }

    const body = new FormData();
    body.set('file', new Blob([new Uint8Array(wav)], { type: 'audio/wav' }), 'speech.wav');
    const response = await fetchAudio('https://api.deepseek.com/v1/tools/asr', {
      method: 'POST', headers: { Authorization: `Bearer ${key}` }, body,
      redirect: 'error', signal: AbortSignal.timeout(45_000),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw Error(`DeepSeek ASR returned HTTP ${response.status}.`);
    }
    return transcriptionSchema.parse(await response.json()).text;
  };
}

const completionSchema = z.object({
  choices: z.array(z.object({ finish_reason: z.literal('stop'), message: z.object({ content: z.string() }) })).min(1),
});
export function createDeepSeekStoryWriter(key: string, fetchText = globalThis.fetch): WriteStory {
  return async book => {
    const response = await fetchText('https://api.deepseek.com/chat/completions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'deepseek-flash', thinking: { type: 'disabled' },
        max_tokens: 700, response_format: { type: 'json_object' },
        messages: [{ role: 'system', content: storySystem }, { role: 'user', content: storyPrompt(book) }],
      }),
      redirect: 'error', signal: AbortSignal.timeout(25_000),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw Error(`DeepSeek story generation returned HTTP ${response.status}.`);
    }
    const result = completionSchema.parse(await response.json());
    return storySchema.parse(JSON.parse(result.choices[0]!.message.content));
  };
}
