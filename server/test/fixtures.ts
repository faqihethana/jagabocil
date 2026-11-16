// Real PCM WAV fixtures exercise ffprobe rather than trusting client duration.
export function wav(seconds: number) {
  const rate = 8000; const samples = rate * seconds;
  const b = Buffer.alloc(44 + samples * 2);
  b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVEfmt ', 8);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) b.writeInt16LE(Math.round(Math.sin(i * .2) * 5000), 44 + i * 2);
  return b;
}
export function multipart(audio: Buffer, surahId = '112') {
  const boundary = 'jagabocil-test-boundary';
  return { headers: { 'content-type': `multipart/form-data; boundary=${boundary}` }, payload: Buffer.concat([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="surahId"\r\n\r\n${surahId}\r\n--${boundary}\r\nContent-Disposition: form-data; name="audio"; filename="ngaji.wav"\r\nContent-Type: audio/wav\r\n\r\n`),
    audio, Buffer.from(`\r\n--${boundary}--\r\n`),
  ]) };
}
