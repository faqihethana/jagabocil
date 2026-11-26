export const bookMatchThreshold = 65;
export const maxAiReadingSeconds = 120;

export function readingWords(text: string) {
  return text.normalize('NFKD').toLowerCase().replace(/\p{M}/gu, '').match(/[\p{L}\p{N}]+/gu) ?? [];
}

export function bookMatchScore(reference: string, transcript: string) {
  const expected = readingWords(reference), heard = readingWords(transcript);
  if (!expected.length || !heard.length) return 0;
  // Count substitutions, omitted words, and extra words across the whole passage.
  let previous = Array.from({ length: expected.length + 1 }, (_, index) => index);
  for (const [index, word] of heard.entries()) {
    const current = [index + 1];
    for (const [i, expectedWord] of expected.entries()) {
      current.push(Math.min(previous[i + 1]! + 1, current[i]! + 1, previous[i]! + Number(word !== expectedWord)));
    }
    previous = current;
  }
  return Math.max(0, Math.round(100 * (1 - previous[expected.length]! / expected.length)));
}
