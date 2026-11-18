# JagaBocil

Aplikasi Android untuk membangun kebiasaan **ngaji sebelum memakai tombol power TV**. Anak merekam bacaan, AI atau orang tua memeriksanya, lalu server memberikan izin satu kali power. Antarmuka berbahasa Indonesia, dibuat dengan Kotlin dan Jetpack Compose.

Stack: aplikasi Android + API Node.js 24/Fastify, dengan **Neon PostgreSQL dan S3 privat** untuk penyimpanan cloud. Mode SQLite + filesystem lokal tetap tersedia untuk pengembangan dan instalasi lama. AI bersifat opsional.

## Yang sudah tersedia

- Daftar dan masuk dengan username dan kata sandi. Username 3–32 karakter, kata sandi minimal 4 karakter. Profil anak dihubungkan melalui kode enam angka, sekali pakai, kedaluwarsa 10 menit.
- Tugas surah Al-Ikhlas, Al-Falaq, atau An-Nas; teks Arab, perekam mikrofon, pratinjau audio, dan unggah M4A.
- **Manual:** salinan audio masuk ke akun orang tua. Orang tua mendengar, menyetujui, atau meminta ulang dari HP lain.
- Notifikasi Android untuk rekaman baru dan hasil review. Aktifkan di **Keluarga** pada HP orang tua dan di **Panduan** pada HP anak. Aplikasi memeriksa saat dibuka dan memakai pekerjaan berkala saat di latar belakang. Android menjadwalkan pekerjaan tersebut dengan interval minimal 15 menit dan bisa menundanya.
- Kode pemulihan sekali pakai tanpa email. Orang tua membuat kode di **Keluarga → Pemulihan akun** dengan kata sandi saat ini. Simpan kode saat ditampilkan. Untuk menggunakannya, pilih **Lupa kata sandi?** di layar masuk. Setelah berhasil, sesi lama akun orang tua berakhir dan perangkat anak tetap terhubung.
- **AI opsional:** DeepSeek mencocokkan teks hasil transkripsi dengan surah tugas. Orang tua tetap mendengar rekaman dan memutuskan. Jika server memakai Gemini, bacaan yang dikenali lengkap dengan confidence dan coverage ≥95% bisa disetujui otomatis. Orang tua perlu menyetujui pengiriman audio ke penyedia yang aktif.
- Izin server berlaku 1–60 menit setelah disetujui dan dikonsumsi atomik sekali saja. Mengganti aturan, mencabut perangkat, atau menghapus rekaman membatalkan izin yang belum dipakai.
- Dua metode koneksi: **infrared** Samsung/LG 38 kHz atau **Wi-Fi lokal**. Pilihan platform Wi-Fi: Android TV/Google TV (Remote Service v2), Samsung Tizen (WSS), LG webOS (SSAP/WSS), dan Wake-on-LAN. Driver jaringan masih perlu pengujian pada TV fisik; lihat [matriks kompatibilitas](docs/tv-compatibility.md).
- Riwayat, pemutaran audio privat, penghapusan oleh orang tua, pencabutan perangkat anak, serta pembaruan antrean setiap 10 detik.

## Batas kontrol TV

Aplikasi mengunci **tombol power di JagaBocil**. Tombol fisik TV, remote lain, atau aplikasi remote lain tetap dapat digunakan. Penguncian menyeluruh memerlukan integrasi yang sesuai dengan perangkat TV atau pengendali daya khusus.

**Infrared:** perlu HP dengan IR blaster. Profil Samsung/LG merupakan kode umum dan harus diuji pada model TV yang dipakai. Power IR bersifat toggle: dapat menyalakan maupun mematikan TV.

**Wi-Fi:** orang tua bisa memilih TV dari **Cari TV di Wi-Fi** atau mengisi platform dan IP lokal secara manual. Pairing dilakukan di HP anak yang menjadi remote. Android/Google TV memakai kode di layar, Samsung/LG memakai persetujuan registrasi TV. HP harus terhubung ke Wi-Fi pada subnet IPv4 privat yang sama dengan TV. Tidak membutuhkan IR blaster atau jembatan server lokal tambahan. Mode Wake-on-LAN menggunakan MAC jaringan TV.

Menyalakan dari standby bergantung pada kemampuan dan pengaturan model TV. Isi MAC opsional untuk mencoba Wake-on-LAN ketika layanan remote TV yang sudah dipairing tidak dapat dihubungi. Tidak ada jaminan semua model/firmware dapat dibangunkan. LG SSAP `turnOnScreen` menyalakan layar saat layanan TV masih aktif; membangunkan TV dari standby penuh perlu wake jaringan yang didukung. Wi-Fi mengirim permintaan nyalakan; tidak otomatis mematikan TV setelah durasi tertentu. Izin yang sudah dikonsumsi tidak dipulihkan jika pengiriman selanjutnya gagal atau TV tidak merespons.

Detail port, pairing, batas kompatibilitas, dan langkah uji perangkat ada di [panduan TV](docs/tv-compatibility.md).

AI membantu mengenali isi bacaan, belum menjamin tajwid, identitas pembaca, atau membedakan bacaan langsung dengan pemutaran rekaman. Untuk pengawasan ketat, gunakan pemeriksaan orang tua.

## Menjalankan API

Prasyarat: **Node.js 24.5+**, npm, dan `ffprobe` dari paket **FFmpeg** di PATH. Mode DeepSeek juga memakai `ffmpeg`.

```sh
cd server
npm ci
cp .env.example .env
npm run dev
```

API tersedia di `http://localhost:8787`. `GET /health` menampilkan jenis database, penyimpanan audio, dan penyedia AI aktif tanpa login. `.env.local` yang gitignored dapat menimpa konfigurasi `.env`. Tanpa `DATABASE_URL`, data lokal tersimpan di `server/data/`. Dengan URL, API memakai schema `jagabocil` di PostgreSQL. Lihat [setup Neon dan S3](docs/backend-storage.md).

Isi `DEEPSEEK_API_KEY` di environment server untuk menggunakan [DeepSeek Speech to Text](https://api-docs.deepseek.com/guides/asr/). Server mengubah rekaman hingga 120 detik ke PCM WAV sebelum dikirim dan menyimpan hasil cocok atau tidak cocok sebagai catatan untuk orang tua. Teks transkripsi tidak disimpan. Rekaman yang lebih panjang atau gagal ditranskripsi tetap masuk antrean orang tua. Orang tua yang sebelumnya mengaktifkan Gemini harus menyimpan ulang persetujuan mode AI setelah penyedia berganti.

Jika `DEEPSEEK_API_KEY` kosong, server dapat memakai `GEMINI_API_KEY`. `AI_MODEL` memilih model Gemini yang mendukung audio dan structured output pada Interactions API. Default-nya `gemini-4-hydrogen` dan interaksi dikirim dengan `store: false`. Simpan semua key di environment server.

Alternatif Docker dari root repo:

```sh
docker compose up --build -d
```

Compose membuka port hanya pada loopback. Pasang reverse proxy HTTPS untuk pemakaian dari dua HP. Untuk cloud, isi `DATABASE_URL`, jalankan migrasi dengan URL direct, dan set `AUDIO_STORE=s3` beserta konfigurasi AWS. SQLite/audio lokal menggunakan volume `family-data` dan membutuhkan satu instance dengan disk persisten. Pada Neon/S3, disk lokal dipakai sementara oleh ffprobe; data utama tersimpan di layanan cloud. Backup, HTTPS, dan deployment hosting tetap perlu disiapkan.

Untuk AI pada Docker, ekspor `DEEPSEEK_API_KEY` atau `GEMINI_API_KEY` di shell atau isi `.env` di **root repo** sebelum menjalankan Compose. File `server/.env` dipakai oleh perintah npm.

## Menjalankan Android

Buka folder `android/` di Android Studio. Gunakan JDK 17 atau 21, SDK platform 35, dan build tools 35.0.0. Gradle wrapper disertakan.

```sh
cd android
./gradlew :app:assembleDebug
```

APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`. Minimum Android 8.0 (API 26). Build release membutuhkan signing key milikmu; key tidak disimpan dalam repo.

1. Jalankan API, lalu buka APK pada emulator atau HP.
2. **Alamat server JagaBocil** otomatis diisi dengan `https://jagabocil.onrender.com` pada instalasi baru. Gunakan alamat yang sama pada kedua HP. Untuk menjalankan backend sendiri, ubah alamat di layar masuk. Emulator Android Studio bisa memakai `http://10.0.2.2:8787` ketika API berjalan di komputer yang sama.
3. Pilih **Orang tua → Daftar**, buat profil anak di tab **Keluarga**, dan simpan aturan serta profil TV.
4. Buat kode pairing. Di HP anak pilih **Aku anak**, gunakan server yang sama, masukkan kode.
5. Anak merekam surah sesuai tugas. Orang tua memutar salinannya di antrean, lalu memilih **Setujui** atau **Ulangi**. Mode AI dapat diaktifkan pada aturan anak jika server sudah dikonfigurasi dan orang tua menyetujui pengiriman audio.
6. Untuk infrared, arahkan IR blaster HP anak ke TV. Untuk Wi-Fi, pilih platform/IP/MAC pada aturan anak, lalu gunakan **Hubungkan TV** di HP anak saat TV menyala (kecuali mode Wake-on-LAN yang tidak memakai pairing). Setelah bacaan disetujui, tombol mengirim satu permintaan power melalui metode yang dipilih.

HTTP hanya diizinkan untuk loopback/alamat emulator pada build debug. Release memerlukan HTTPS. Akun yang sama bisa digunakan di dua HP untuk menguji antrean orang tua; satu instalasi menyimpan satu sesi. Distribusi Play Store belum tersedia.

Akun dari versi lama dapat memasukkan pengenal masuk yang sebelumnya mereka pakai ke kolom Username. Nama tampilan dan kata sandi akun lama tetap bisa digunakan.

## Privasi dan akses

Password di-hash dengan scrypt + salt. Token acak disimpan dalam bentuk hash di server, berlaku 30 hari, dan dienkripsi dengan Android Keystore di HP. API audio memerlukan autentikasi dan pemeriksaan kepemilikan keluarga. S3 hanya menggunakan prefix `jagabocil/`, enkripsi SSE-S3, dan respons audio melalui API keluarga; tidak ada URL publik rekaman. Saat startup mode S3, server memakai probe tanpa data anak pada prefix rekaman untuk memeriksa penolakan akses anonim sebelum menerima upload. Upload dibatasi 10 MB, 180 detik, serta format M4A/WAV; durasi diperiksa dari berkas oleh ffprobe, bukan dari klaim HP.

Kode pemulihan memiliki entropi 128 bit, disimpan sebagai hash, dan dikonsumsi atomik saat dipakai. Membuat kode baru membatalkan kode sebelumnya. Pemberitahuan Android hanya menyertakan status umum tanpa nama anak atau isi rekaman.

Audio dan riwayat otomatis dihapus setelah 7 hari, melalui cleanup saat startup dan setiap jam. Jika penghapusan S3 gagal, metadata dipertahankan agar cleanup dapat mencoba ulang; rekaman yang sedang dihapus langsung diblokir dari playback dan izin power. Orang tua dapat menghapus lebih awal di riwayat. Cache audio HP dibersihkan setelah dipakai, logout, atau startup berikutnya; rekaman terputus saat aplikasi masuk background dibatalkan. Backup host, disk encryption, dan kebijakan penyedia AI mengikuti konfigurasi operator server. Jangan mengaktifkan AI tanpa memahami pemrosesan audio anak oleh penyedia.

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

CI menjalankan suite pada SQLite dan PostgreSQL. Tes S3 memakai layanan HTTP tiruan dengan AWS SDK, tanpa key AWS sungguhan. Tes integrasi API memakai WAV sungguhan untuk memeriksa ffprobe, hak akses keluarga, pairing, persetujuan manual, izin sekali pakai/race, kedaluwarsa, pencabutan, penghapusan, dan fallback AI. Provider AI di-mock pada tes agar tidak mengirim audio atau memakai kuota. Unit test Android memeriksa status izin, kode IR, paket Wake-on-LAN, subnet Wi-Fi, framing Remote v2, kode pairing, payload Samsung/LG, dan penolakan sertifikat TV yang berbeda. Tes tidak menggantikan pengujian pada TV fisik. Migrasi database menambahkan konfigurasi Wi-Fi tanpa mengubah keluarga atau pengaturan infrared lama. GitHub Actions membangun API dan APK debug yang dapat diunduh dari artifact workflow.

## Struktur

```text
android/       Kotlin, Compose, perekam, Keystore, klien API, infrared
server/src/    autentikasi, keluarga, rekaman privat, keputusan, izin, adapter AI
server/test/   pengujian integrasi API
compose.yaml   API dengan volume persisten untuk self-hosting
```
