# Neon PostgreSQL dan S3

JagaBocil mendukung dua mode database (SQLite atau PostgreSQL) dan dua mode rekaman (lokal atau S3). Kontrak API Android tetap sama; credential database/AWS hanya berada pada server.

## Resource JagaBocil

- Neon project: `jagabocil`, ID `noisy-silence-08214322`, region Singapore (`aws-ap-southeast-1`).
- Database: `jagabocil`, schema: `jagabocil`.
- Branch production: `br-steep-bread-a17qvrqv`.
- Branch pengembangan: `dev-storage`, ID `br-aged-dawn-a156jn9t`.
- Bucket AWS bersama: `faqih-ethana-general-bucket`, region Jakarta (`ap-southeast-3`).
- Prefix milik aplikasi: `jagabocil/`; production menggunakan `jagabocil/production/`, development dapat menggunakan `jagabocil/dev/`. Objek rekaman: `<prefix>/recordings/<child-uuid>/<recording-uuid>.audio`.

Proyek Neon lain dan prefix bucket aplikasi lain tidak digunakan atau diubah. Connection string dan key tidak disimpan dalam repo. File `.env.local`/`.env.production.local` pada workspace bersifat lokal dan gitignored; mesin hosting harus diberi konfigurasi melalui secret environment-nya sendiri.

## Konfigurasi runtime

```dotenv
DATABASE_URL=postgresql://<role>:<password>@<pooled-host>/<database>?sslmode=require
DATABASE_URL_UNPOOLED=postgresql://<role>:<password>@<direct-host>/<database>?sslmode=require
DATABASE_SCHEMA=jagabocil
DATABASE_AUTO_MIGRATE=false
AUDIO_STORE=s3
AWS_REGION=ap-southeast-3
AWS_S3_BUCKET_NAME=faqih-ethana-general-bucket
AWS_S3_PREFIX=jagabocil/production
AWS_ACCESS_KEY_ID=<server-only>
AWS_SECRET_ACCESS_KEY=<server-only>
# Isi jika memakai temporary AWS credentials:
AWS_SESSION_TOKEN=
```

AWS SDK dapat memakai IAM role dari hosting sebagai pengganti access key. Bila memakai key, berikan hanya akses GetObject/PutObject/DeleteObject pada prefix JagaBocil; jangan pakai variabel publik atau memasukkan key ke APK.

Contoh izin identity policy ada di [recordings-policy.json](../server/iam/recordings-policy.json). Itu bukan bucket policy dan tidak dipasang otomatis; operator IAM dapat menggunakannya untuk role/key backend. Development sebaiknya memiliki izin sendiri pada prefix `jagabocil/dev/recordings/*`.

Key AWS pada proyek Vercel lain yang disimpan sebagai **Secret/Sensitive tidak bisa dibaca balik**. Keberadaan variable itu tidak berarti JagaBocil sudah mendapat akses. Tambahkan credential yang masih dimiliki operator ke secret environment server JagaBocil, atau pasang IAM role yang sesuai. Jangan kirim key ke percakapan atau menaruhnya di Git.

## Migrasi schema

Migrasi additive ada di `server/src/database.ts`. Jalankan pada branch pengembangan terlebih dahulu, lalu pada production dengan connection string **direct/unpooled**:

```sh
cd server
npm ci
npm run build
# .env.local untuk branch pengembangan:
npm run db:migrate
# Production, jika file rahasia ini disediakan lokal:
node --use-env-proxy --env-file=.env.production.local dist/migrate.js
```

Jalankan migrasi versi 2 sebelum memulai backend dengan login username. Migrasi mengubah nama kolom pengenal akun dan mempertahankan akun, profil, serta sesi yang sudah ada. Runtime normal memeriksa versi schema. `DATABASE_AUTO_MIGRATE=true` tersedia untuk setup yang sengaja menjalankan migrasi saat startup. Pada host yang menyuntikkan environment, jalankan `node --use-env-proxy dist/migrate.js` untuk migrasi dan `node --use-env-proxy dist/index.js` untuk API. Dockerfile memakai flag yang sama.

Neon menggunakan driver HTTP melalui HTTPS; Postgres biasa/lokal memakai pool `pg`. Semua tabel aplikasi diberi nama schema secara eksplisit, sehingga tidak bergantung pada `search_path` dari koneksi pool. Pairing dikonsumsi atomik, izin power tetap satu SQL update, dan perubahan aturan/pencabutan perangkat memakai transaksi.

Pada lingkungan yang menggunakan proxy HTTP/HTTPS, gunakan Node 24.5+ dengan flag `--use-env-proxy` saat startup agar koneksi HTTPS Neon dan S3 mengikuti proxy yang tersedia. Script npm dan Dockerfile sudah menyertakannya; mengisi `NODE_USE_ENV_PROXY` hanya di file dotenv tidak cukup pada semua versi Node. Tanpa proxy yang dikonfigurasi, koneksi tetap langsung. TLS tetap diverifikasi; jangan menonaktifkan pemeriksaan sertifikat.

SQLite lama tetap dapat dibuka dengan mengosongkan `DATABASE_URL`; migrasi SQLite sebelumnya tetap dipertahankan. **Mengisi DATABASE_URL tidak menyalin data SQLite lama ke Neon secara otomatis.** Simpan backup dan lakukan transfer terencana sebelum mengganti backend yang sudah memiliki pengguna. Pada setup workspace ini tidak ada database SQLite pengguna yang perlu dipindahkan.

## Privasi dan retensi S3

- Prefix rekaman harus menolak akses anonim. Jangan memasang kebijakan public-read untuk `jagabocil/*/recordings/*` pada bucket bersama.
- Startup mode S3 mengunggah probe kecil tanpa data anak pada bentuk key rekaman, mengecek GET anonim mendapat 403, lalu menghapus probe. Jika pengecekan gagal, API tidak mulai menerima rekaman. Probe ini merupakan pemeriksaan konektivitas/policy dari server, bukan pengganti audit seluruh kondisi IAM/bucket policy.
- Aplikasi tidak mengubah bucket policy, ACL global, lifecycle, atau objek proyek lain. Pengaturan bucket harus tetap menjamin prefix privat.
- Upload memakai SSE-S3 (`AES256`) dan `Cache-Control: private, no-store`. Audio diputar melalui endpoint yang memeriksa token dan kepemilikan keluarga.
- ffprobe tetap memerlukan file sementara untuk memeriksa format/durasi. File sementara dibersihkan setelah pemeriksaan; berkas rekaman permanen masuk S3 pada mode cloud.
- Upload belum selesai/gagal tidak masuk antrean review dan tidak dapat memberi izin. Reference upload tetap dicatat agar cleanup dapat menghapus objek bila kegagalan jaringan membuat hasil upload ambigu.
- Cleanup berjalan saat startup dan tiap jam. Penghapusan yang gagal mempertahankan reference untuk retry. API harus tetap berjalan atau dijalankan terjadwal agar retensi diproses; file/metadata tidak otomatis hilang saat API dimatikan.
- Jangan mengganti `AUDIO_STORE`, bucket, atau prefix pada database berisi rekaman tanpa memindahkan objek/reference yang terkait.
- Branch Neon tidak menyalin atau mengisolasi bucket AWS eksternal. Gunakan prefix development/production berbeda. Jika metadata production disalin ke branch development, jangan mengarahkan cleanup development ke prefix production; adapter menolak reference di luar prefix yang dikonfigurasi.

## Verifikasi

```sh
cd server
npm run typecheck
npm test
# Postgres lokal / database tes terpisah:
TEST_DATABASE_URL=postgresql://<role>:<password>@localhost/<testdb> npm test
npm run build
```

Suite Postgres membuat schema tes acak dengan prefix `jagabocil_` dan menghapus hanya schema tersebut setelah tes. S3 memakai fixture HTTP lokal dengan AWS SDK untuk memeriksa signature/body roundtrip, enkripsi, penolakan akses lintas keluarga, pembatasan prefix, kegagalan upload, dan retry penghapusan. Tes tersebut tidak membuktikan akses pada bucket AWS asli. Lakukan startup privacy probe dengan credential runtime yang valid sebelum deployment.
