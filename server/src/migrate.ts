import { postgresDatabase } from './database.js';

const url = process.env.DATABASE_URL_UNPOOLED || process.env.DATABASE_URL;
if (!url) throw Error('Set DATABASE_URL_UNPOOLED for the migration.');
if (new URL(url).hostname.includes('-pooler.')) throw Error('Migrations require the direct, unpooled database URL.');
const db = await postgresDatabase(url, process.env.DATABASE_SCHEMA || 'jagabocil', true);
await db.close();
console.log('JagaBocil PostgreSQL schema is ready.');
