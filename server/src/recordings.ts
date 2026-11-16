import { mkdir, readFile, writeFile, unlink } from 'node:fs/promises';
import { dirname, basename, resolve } from 'node:path';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';
import { FetchHttpHandler } from '@smithy/fetch-http-handler';
import { randomUUID } from 'node:crypto';

export interface RecordingStore {
  kind: 'local' | 's3';
  reference(id: string, childId: string): string;
  put(id: string, childId: string, bytes: Buffer, mime: string): Promise<string>;
  get(reference: string): Promise<Buffer>;
  remove(reference: string): Promise<void>;
}
const uuid = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
const validId = (id: string) => { if (!new RegExp(`^${uuid}$`).test(id)) throw Error('Invalid recording identifier.'); };

export async function localRecordings(directory: string): Promise<RecordingStore> {
  const root = resolve(directory);
  await mkdir(root, { recursive: true, mode: 0o700 });
  function path(reference: string) {
    const name = reference.startsWith('local:') ? reference.slice(6) : reference;
    const target = resolve(root, name);
    if (dirname(target) !== root || !new RegExp(`^${uuid}\\.audio$`).test(basename(target))) throw Error('Recording outside owned directory.');
    return target;
  }
  return {
    kind: 'local',
    reference(id) { validId(id); return `local:${id}.audio`; },
    async put(id, _childId, bytes) { validId(id); const reference = `local:${id}.audio`; await writeFile(path(reference), bytes, { mode: 0o600 }); return reference; },
    async get(reference) { return readFile(path(reference)); },
    async remove(reference) { try { await unlink(path(reference)); } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; } },
  };
}

export type S3Settings = { bucket: string; region: string; prefix?: string; endpoint?: string; forcePathStyle?: boolean };
function s3Client(settings: S3Settings) {
  return new S3Client({ region: settings.region, endpoint: settings.endpoint, forcePathStyle: settings.forcePathStyle, maxAttempts: 2, requestHandler: new FetchHttpHandler() });
}
export async function verifyPrivateS3(settings: S3Settings, client = s3Client(settings)) {
  if (!/^jagabocil(?:\/[a-z0-9_-]+)*$/.test(settings.prefix ?? 'jagabocil')) throw Error('Invalid S3 prefix.');
  if (!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(settings.bucket) || !/^[a-z0-9-]+$/.test(settings.region)) throw Error('Invalid S3 bucket or region.');
  const key = `${settings.prefix ?? 'jagabocil'}/recordings/${randomUUID()}/${randomUUID()}.audio`;
  // Only harmless probe data is uploaded before checking anonymous access.
  await client.send(new PutObjectCommand({ Bucket: settings.bucket, Key: key, Body: Buffer.from('JagaBocil private-storage check'), ContentType: 'text/plain', ServerSideEncryption: 'AES256' }), { abortSignal: AbortSignal.timeout(20_000) });
  try {
    const base = settings.endpoint ?? `https://s3.${settings.region}.amazonaws.com`;
    const response = await fetch(`${base}/${settings.bucket}/${key}`, { redirect: 'error', signal: AbortSignal.timeout(15_000) });
    await response.body?.cancel();
    if (response.status !== 403) throw Error('S3 privacy check failed: the recording prefix must deny anonymous access before serving child audio.');
  } finally {
    await client.send(new DeleteObjectCommand({ Bucket: settings.bucket, Key: key }), { abortSignal: AbortSignal.timeout(20_000) });
  }
}
export function s3Recordings(settings: S3Settings, client?: S3Client): RecordingStore {
  const prefix = settings.prefix ?? 'jagabocil';
  if (!/^jagabocil(?:\/[a-z0-9_-]+)*$/.test(prefix)) throw Error('S3 prefix must stay inside jagabocil/.');
  if (!settings.bucket || !settings.region) throw Error('S3 bucket and region are required.');
  const s3 = client ?? s3Client(settings);
  function key(reference: string) {
    const value = reference.startsWith('s3:') ? reference.slice(3) : '';
    if (!new RegExp(`^${prefix}/recordings/${uuid}/${uuid}\\.audio$`).test(value)) throw Error('S3 recording outside owned prefix.');
    return value;
  }
  return {
    kind: 's3',
    reference(id, childId) { validId(id); validId(childId); return `s3:${prefix}/recordings/${childId}/${id}.audio`; },
    async put(id, childId, bytes, mime) {
      validId(id); validId(childId);
      const reference = `s3:${prefix}/recordings/${childId}/${id}.audio`;
      await s3.send(new PutObjectCommand({ Bucket: settings.bucket, Key: key(reference), Body: bytes, ContentType: mime, CacheControl: 'private, no-store', ServerSideEncryption: 'AES256' }), { abortSignal: AbortSignal.timeout(20_000) });
      return reference;
    },
    async get(reference) {
      const result = await s3.send(new GetObjectCommand({ Bucket: settings.bucket, Key: key(reference) }), { abortSignal: AbortSignal.timeout(20_000) });
      if (!result.Body) throw Error('Recording has no body.');
      if ((result.ContentLength ?? 0) > 10 * 1024 * 1024) throw Error('Recording exceeds maximum size.');
      const bytes = await result.Body.transformToByteArray();
      if (bytes.length > 10 * 1024 * 1024) throw Error('Recording exceeds maximum size.');
      return Buffer.from(bytes);
    },
    async remove(reference) { await s3.send(new DeleteObjectCommand({ Bucket: settings.bucket, Key: key(reference) }), { abortSignal: AbortSignal.timeout(20_000) }); },
  };
}
