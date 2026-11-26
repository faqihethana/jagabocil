import { z } from 'zod';
import { isTaskSurah, quranText } from './catalog.js';

const verse = z.number().int().positive();
const quranTask = z.object({ kind: z.literal('quran'), surahId: z.string().refine(isTaskSurah),
  firstAyah: verse.optional(), lastAyah: verse.optional() });
const bookTask = z.object({ kind: z.literal('book'), title: z.string().trim().min(1).max(80),
  text: z.string().trim().max(1600) });
export const taskInput = z.union([quranTask, bookTask,
  z.object({ kind: z.literal('book'), bookId: z.string().min(1).max(80) })]);
export const taskPlan = z.object({ tasks: z.array(taskInput).max(10) });

export type ReadingTask = { id: string } & (
  { kind: 'quran'; surahId: string; firstAyah?: number; lastAyah?: number } |
  { kind: 'book'; title: string; text: string }
);

export function quranPassage(task: Extract<ReadingTask, { kind: 'quran' }>) {
  const chapter = quranText(task.surahId)?.ayahs;
  if (!chapter) return;
  const first = task.firstAyah ?? 1;
  const last = task.lastAyah ?? chapter.length;
  return chapter.slice(first - 1, last);
}
