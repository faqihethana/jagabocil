import { createApp } from './app.js';
import { createVerifier } from './ai.js';

const app = await createApp({
  dataDir: process.env.DATA_DIR || './data', logger: true,
  verify: process.env.GEMINI_API_KEY ? createVerifier(process.env.GEMINI_API_KEY, process.env.AI_MODEL || 'gemini-4-hydrogen') : undefined,
});
await app.listen({ host: process.env.HOST || '0.0.0.0', port: Number(process.env.PORT || 8787) });
for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => { void app.close(); });
