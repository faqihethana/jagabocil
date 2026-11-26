import { matchReadingWords } from './book-reading.js';

const wordPattern = /[\p{L}\p{N}\p{M}]+/gu;

function quranWords(text: string) {
  return Array.from(text.matchAll(wordPattern), ([word]) => ({
    word,
    plain: word.normalize('NFKD').toLowerCase()
      .replace(/[\p{M}ـۥۦ]/gu, '').replace(/ٱ/g, 'ا').replace(/ى/g, 'ي'),
  })).filter(word => word.plain);
}

export function compareQuranReading(ayahs: string[], transcript: string, includesBasmala = true) {
  const expected = quranWords(ayahs.join(' '));
  const heard = quranWords(transcript);
  // Juz 30 references start with a basmala. The child may recite it or skip it.
  const opening = includesBasmala ? expected.splice(0, 4) : [];
  const hasOpening = opening.every((word, index) => word.plain === heard[index]?.plain);
  const offset = hasOpening ? opening.length : 0;
  const spoken = heard.slice(offset);
  const { score, rows } = matchReadingWords(expected.map(word => word.plain), spoken.map(word => word.plain));

  // Copy harakat only onto matching words in reading order. Keep ASR words and punctuation
  // where there is no match, so omissions and substitutions remain visible.
  const vocalized = hasOpening ? opening.map(word => word.word) : [];
  let i = spoken.length;
  let j = expected.length;
  while (i > 0 && j > 0) {
    const current = rows[i]![j]!;
    const diagonal = rows[i - 1]![j - 1]!;
    if (spoken[i - 1]!.plain === expected[j - 1]!.plain && current === diagonal) {
      vocalized[offset + i - 1] = expected[j - 1]!.word;
      i--;
      j--;
    } else if (current === diagonal + 1) {
      i--;
      j--;
    } else if (current === rows[i - 1]![j]! + 1) {
      i--;
    } else {
      j--;
    }
  }
  let index = 0;
  const vocalizedTranscript = transcript.replace(wordPattern, word => {
    // The Quranic export can contain freestanding pause marks.
    return quranWords(word).length ? vocalized[index++] ?? word : word;
  });
  return { score, vocalizedTranscript };
}
