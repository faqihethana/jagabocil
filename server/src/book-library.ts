import { z } from 'zod';
import catalog from './book-catalog.json' with { type: 'json' };
import { readingWords } from './book-reading.js';

export type BookTemplate = typeof catalog[number];
export const bookTemplates = new Map(catalog.map(book => [book.id, book]));
export const storySchema = z.object({
  title: z.string().trim().min(3).max(80),
  text: z.string().trim().max(1600).refine(text => {
    const words = readingWords(text).length;
    return words >= 45 && words <= 120 && !/https?:\/\/|[<>]/i.test(text);
  }),
});
export type WriteStory = (book: BookTemplate) => Promise<z.infer<typeof storySchema>>;
export const storySystem = 'Tulis satu cerita orisinal untuk anak usia 6–10 tahun dalam bahasa Indonesia yang sederhana. Tulis 55–85 kata dalam dua paragraf pendek, dengan kalimat yang mudah dibaca keras-keras, tokoh fiktif, dan akhir yang tuntas. Gunakan topik sebagai titik awal lalu buat alur baru. Jaga isi tetap ramah anak, tanpa kekerasan, bahaya, promosi, atau data pribadi. Kembalikan JSON berisi title dan text, dengan dua paragraf dipisahkan baris kosong. Hindari Markdown dan tautan.';
export const storyPrompt = (book: BookTemplate) => `Tema: ${book.theme}. Topik template: ${book.title}.`;
