import quran from './quran.json' with { type: 'json' };

const chapters = new Map(quran.chapters.map(chapter => [chapter.id, chapter]));
export const quranNotice = quran.notice;

export function quranText(id: string) {
  const chapter = chapters.get(id);
  if (chapter) return { id, ayahs: chapter.ayahs, notice: quran.notice };
}

export function isTaskSurah(id: string) {
  return chapters.has(id) && Number(id) >= quran.juz30Start;
}

export function taskRecordingLimit(id: string) {
  return chapters.get(id)?.maxRecordingSeconds ?? 180;
}

export function taskSurah(id: string) {
  if (!isTaskSurah(id)) return;
  const chapter = chapters.get(id)!;
  return { id, name: chapter.name, verses: chapter.ayahs.length, arabic: chapter.ayahs.join('\n') };
}

export function transcriptionReference(id: string) {
  return isTaskSurah(id) ? quran.transcription[id as keyof typeof quran.transcription] : undefined;
}

// Older Android builds read the task and history text from their dashboard.
export function legacySurahs(referencedIds: Iterable<string> = []) {
  return Array.from(new Set(['112', '113', '114', ...referencedIds])).flatMap(id => {
    const surah = taskSurah(id);
    if (!surah) return [];
    // In the Tanzil export each Juz 30 chapter begins with the four words of the basmala.
    // The legacy recorder already displays them in a separate heading.
    const opening = surah.arabic.split(' ', 4).join(' ');
    return [{ ...surah, arabic: surah.arabic.slice(opening.length + 1) }];
  });
}
