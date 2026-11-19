import { createApp } from './app.js';
import { createVerifier } from './ai.js';
import { createDeepSeekVerifier } from './deepseek.js';
import { postgresDatabase } from './database.js';
import { s3Recordings, verifyPrivateS3 } from './recordings.js';
import { midtransSandbox } from './midtrans.js';

const midtransKey = process.env.MIDTRANS_SANDBOX_SERVER_KEY?.trim();
const midtrans = midtransKey ? midtransSandbox(midtransKey, process.env.MIDTRANS_PUBLIC_ORIGIN?.trim()) : undefined;

const s3 = {
  bucket: process.env.AWS_S3_BUCKET_NAME || 'faqih-ethana-general-bucket',
  region: process.env.AWS_REGION || 'ap-southeast-3',
  prefix: process.env.AWS_S3_PREFIX || 'jagabocil/production',
};
const useS3 = process.env.AUDIO_STORE === 's3';
if (useS3) await verifyPrivateS3(s3);
const database = process.env.DATABASE_URL
  ? await postgresDatabase(process.env.DATABASE_URL, process.env.DATABASE_SCHEMA || 'jagabocil', process.env.DATABASE_AUTO_MIGRATE === 'true')
  : undefined;
const deepSeekKey = process.env.DEEPSEEK_API_KEY?.trim();
const geminiKey = process.env.GEMINI_API_KEY?.trim();

const app = await createApp({
  dataDir: process.env.DATA_DIR || './data', logger: true,
  database,
  midtrans,
  recordings: useS3 ? s3Recordings(s3) : undefined,
  aiProvider: deepSeekKey ? 'deepseek' : 'gemini',
  verify: deepSeekKey ? createDeepSeekVerifier(deepSeekKey)
    : geminiKey ? createVerifier(geminiKey, process.env.AI_MODEL || 'gemini-4-hydrogen') : undefined,
});
await app.listen({ host: process.env.HOST || '0.0.0.0', port: Number(process.env.PORT || 8787) });
for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => { void app.close(); });
