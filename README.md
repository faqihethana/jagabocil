# JagaBocil

Aplikasi Android untuk membangun kebiasaan **ngaji sebelum memakai tombol power TV**. Anak merekam bacaan, AI atau orang tua memeriksanya, lalu server memberikan izin satu kali power. Antarmuka berbahasa Indonesia, dibuat dengan Kotlin dan Jetpack Compose.

Ini MVP yang bisa dijalankan sendiri: aplikasi Android + API Node.js 24/Fastify + SQLite + penyimpanan audio privat. Tidak membutuhkan layanan database atau akun AI untuk mode manual.

## Yang sudah tersedia

- Akun orang tua dengan kata sandi; profil anak dihubungkan melalui kode enam angka, sekali pakai, kedaluwarsa 10 menit.
- Tugas surah Al-Ikhlas, Al-Falaq, atau An-Nas; teks Arab, perekam mikrofon, pratinjau audio, dan unggah M4A.
- **Manual:** salinan audio masuk ke akun orang tua. Orang tua mendengar, menyetujui, atau meminta ulang dari HP lain.
- **AI opsional:** Gemini menerima audio setelah persetujuan orang tua. Hanya bacaan yang dikenali lengkap dengan confidence dan coverage ≥95% yang disetujui otomatis. Kegagalan, hasil tidak valid, dan keraguan diteruskan ke antrean manual.
- Izin server berlaku 1–60 menit setelah disetujui dan dikonsumsi atomik sekali saja. Mengganti aturan, mencabut perangkat, atau menghapus rekaman membatalkan izin yang belum dipakai.
- Kontrol nyata melalui Android `ConsumerIrManager`, dengan profil power Samsung/LG 38 kHz. Pemeriksaan dukungan hardware dilakukan sebelum meminta izin power.
- Riwayat, pemutaran audio privat, penghapusan oleh orang tua, pencabutan perangkat anak, serta pembaruan antrean setiap 10 detik.

## Batas kontrol TV

Aplikasi mengunci **tombol power di JagaBocil**. Tombol fisik TV, remote lain, atau aplikasi remote lain tetap dapat digunakan. Penguncian menyeluruh memerlukan integrasi yang sesuai dengan perangkat TV atau pengendali daya khusus.

MVP memakai **HP dengan IR blaster**, bukan Android TV melalui Wi-Fi. Profil Samsung/LG merupakan kode umum; kecocokan perlu diuji pada model TV yang dipakai. Power bersifat toggle: sinyal yang sama dapat menyalakan maupun mematikan. Aplikasi tidak mengetahui kondisi TV dan tidak otomatis mematikannya setelah durasi tertentu. Izin yang sudah dikonsumsi tidak dipulihkan saat sinyal gagal mencapai TV.

AI membantu mengenali isi bacaan, belum menjamin tajwid, identitas pembaca, atau membedakan bacaan langsung dengan pemutaran rekaman. Untuk pengawasan ketat, gunakan pemeriksaan orang tua.

## Menjalankan API

Prasyarat: **Node.js 24+**, npm, dan `ffprobe` dari paket **FFmpeg** di PATH.

```sh
cd server
npm ci
cp .env.example .env
npm run dev
```

API tersedia di `http://localhost:8787`; `GET /health` memeriksa koneksi tanpa login. Data tersimpan di `server/data/`. Untuk mode AI, isi `GEMINI_API_KEY` pada `.env`, lalu restart. `AI_MODEL` dapat diganti dengan model Gemini yang mendukung audio dan structured output pada Interactions API; default `gemini-4-hydrogen` mengikuti dokumentasi audio Google saat implementasi. Interaksi dikirim dengan `store: false`. Jika provider/model tidak tersedia, rekaman tetap masuk antrean orang tua. Key tidak pernah dimasukkan ke APK.

Alternatif Docker dari root repo:

```sh
docker compose up --build -d
```

Compose menyimpan data dalam volume `family-data` dan membuka port hanya pada loopback. Pasang reverse proxy HTTPS untuk pemakaian dari dua HP. Ini server dengan disk persisten dan satu instance; jangan deploy SQLite/audio lokal ke filesystem serverless sementara. Gunakan backup volume dan TLS pada host yang dipilih. Deployment hosting belum disertakan.

Untuk AI pada Docker, ekspor `GEMINI_API_KEY` di shell atau isi `.env` di **root repo** sebelum menjalankan Compose. File `server/.env` dipakai oleh perintah npm.

## Menjalankan Android

Buka folder `android/` di Android Studio. Gunakan JDK 17 atau 21, SDK platform 35, dan build tools 35.0.0. Gradle wrapper disertakan.

```sh
cd android
./gradlew :app:assembleDebug
```

APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`. Minimum Android 8.0 (API 26). Build release membutuhkan signing key milikmu; key tidak disimpan dalam repo.

1. Jalankan API, lalu buka APK pada emulator atau HP.
2. Isi **Alamat server keluarga**. Emulator Android Studio memakai `http://10.0.2.2:8787`. Untuk HP asli gunakan URL **HTTPS** yang dapat diakses kedua perangkat.
3. Pilih **Orang tua → Daftar**, buat profil anak di tab **Keluarga**, dan simpan aturan serta profil TV.
4. Buat kode pairing. Di HP anak pilih **Aku anak**, gunakan server yang sama, masukkan kode.
5. Anak merekam surah sesuai tugas. Orang tua memutar salinannya di antrean, lalu memilih **Setujui** atau **Ulangi**. Mode AI dapat diaktifkan pada aturan anak jika server sudah dikonfigurasi dan orang tua menyetujui pengiriman audio.
6. Setelah disetujui, arahkan IR blaster HP anak ke TV dan tekan power. Tanpa IR, alur ngaji dan pemeriksaan tetap dapat dipakai; power tidak tersedia.

HTTP hanya diizinkan untuk loopback/alamat emulator pada build debug. Release memerlukan HTTPS. Akun yang sama bisa digunakan di dua HP untuk menguji antrean orang tua; satu instalasi menyimpan satu sesi. Pemulihan kata sandi, notifikasi push, kontrol TV Wi-Fi, dan distribusi Play Store belum tersedia.

## Privasi dan akses

Password di-hash dengan scrypt + salt. Token acak disimpan dalam bentuk hash di server, berlaku 30 hari, dan dienkripsi dengan Android Keystore di HP. API audio memerlukan autentikasi dan pemeriksaan kepemilikan keluarga; folder audio tidak dipublikasikan. Upload dibatasi 10 MB, 180 detik, serta format M4A/WAV; durasi diperiksa dari berkas oleh ffprobe, bukan dari klaim HP.

Audio dan riwayat otomatis dihapus setelah 7 hari, melalui cleanup saat startup dan setiap jam. Orang tua dapat menghapus lebih awal di riwayat. Cache audio HP dibersihkan setelah dipakai, logout, atau startup berikutnya; rekaman terputus saat aplikasi masuk background dibatalkan. Backup host, disk encryption, dan kebijakan penyedia AI mengikuti konfigurasi operator server. Jangan mengaktifkan AI tanpa memahami pemrosesan audio anak oleh penyedia.

## Verifikasi

```sh
cd server
npm run typecheck
npm test
npm run build
```

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Tes integrasi API memakai WAV sungguhan untuk memeriksa ffprobe, hak akses keluarga, pairing, persetujuan manual, izin sekali pakai/race, kedaluwarsa, pencabutan, penghapusan, dan fallback AI. Provider AI di-mock pada tes agar tidak mengirim audio atau memakai kuota. Unit test Android memeriksa status izin dan struktur kode IR. GitHub Actions membangun API dan APK debug yang dapat diunduh dari artifact workflow.

## Struktur

```text
android/       Kotlin, Compose, perekam, Keystore, klien API, infrared
server/src/    autentikasi, keluarga, rekaman privat, keputusan, izin, adapter AI
server/test/   pengujian integrasi API
compose.yaml   API dengan volume persisten untuk self-hosting
```
