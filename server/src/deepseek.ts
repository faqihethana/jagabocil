import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rmdir, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { z } from 'zod';
import type { Verify } from './ai.js';
import { transcriptionReference } from './catalog.js';

const run = promisify(execFile);
const transcription = z.object({ text: z.string() });

function normalizeArabic(text: string) {
  return text.normalize('NFKC').replace(/[\p{M}\p{P}\p{Cf}\sـ]/gu, '')
    .replace(/[ٱأإآ]/g, 'ا').replace(/ى/g, 'ي');
}

export function createDeepSeekVerifier(key: string, fetchAudio = globalThis.fetch): Verify {
  return async (audio, _mediaType, surahId, duration) => {
    const reference = transcriptionReference(surahId);
    if (!reference || !Number.isFinite(duration) || duration <= 0 || duration > 120) {
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
    const { text } = transcription.parse(await response.json());
    const normalized = normalizeArabic(text);
    const basmala = normalizeArabic('بسم الله الرحمن الرحيم');
    const withBasmala = normalizeArabic(reference);
    const expected = withBasmala.startsWith(basmala) ? withBasmala.slice(basmala.length) : withBasmala;
    const matchesSurah = normalized === expected || normalized === basmala + expected;
    // A transcript can match exactly without establishing that anyone recited it clearly.
    return { isRecitation: false, matchesSurah, coverage: matchesSurah ? 1 : 0, confidence: 0,
      feedback: matchesSurah ? 'Teks yang dikenali cocok dengan surah tugas.' : 'AI belum mengenali surah tugas secara lengkap.' };
  };
}
