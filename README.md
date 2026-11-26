# JagaBocil

Aplikasi Android untuk membangun kebiasaan **ngaji atau baca buku sebelum menyalakan TV**. Anak merekam bacaan, orang tua memeriksa, lalu server memberikan izin satu kali power. Antarmuka berbahasa Indonesia, dibuat dengan Kotlin dan Jetpack Compose.

Stack: aplikasi Android + API Node.js 24/Fastify, dengan **Neon PostgreSQL dan S3 privat** untuk penyimpanan cloud. Mode SQLite + filesystem lokal tetap tersedia untuk pengembangan dan instalasi lama. AI bersifat opsional.

## Yang sudah tersedia

- Orang tua membuat akun anak dari menu **Keluarga**. Anak masuk dengan username dan kata sandinya sendiri. Username baru terdiri dari 3–32 karakter, dengan kata sandi minimal 4 karakter.
- Orang tua membuka **Beranda → Atur bacaan** untuk menyiapkan sampai 10 tugas. Satu daftar bisa berisi surah Juz 30 penuh, satu ayat atau rentang ayat, cerita dari **Rak Buku**, dan teks sendiri. Anak mengerjakan tugas dari Beranda dalam urutan bebas. Daftar kosong memberi anak pilihan bacaan sendiri.
- **Rak Buku** menyediakan 24 cerita pendek dalam tema hewan, alam, sehari-hari, dan persahabatan. Cerita bawaan bisa dibuka tanpa login atau internet. Anak yang sudah masuk bisa membuat satu versi AI per template. Versi tersebut disimpan di akun anak dan bisa dipakai untuk tugas baca.
- Menu **Al-Qur’an** dapat dibuka sebelum masuk. Juz 30 ikut di dalam APK. Untuk 77 surah lainnya, pilih surah dan tekan **Unduh surah**. Teks yang sudah diunduh tersimpan per HP dan dapat dibaca lagi tanpa internet.
- **Manual:** salinan audio masuk ke akun orang tua. Orang tua mendengar, menyetujui, atau meminta ulang dari HP lain.
- Notifikasi Android untuk rekaman baru dan hasil review. Aktifkan di **Menu akun → Pengaturan akun** pada HP orang tua dan di **Panduan** pada HP anak. Aplikasi memeriksa saat dibuka dan memakai pekerjaan berkala saat di latar belakang. Android menjadwalkan pekerjaan tersebut dengan interval minimal 15 menit dan bisa menundanya.
- Kode pemulihan sekali pakai tanpa email. Orang tua membuat kode di **Pengaturan akun → Pemulihan akun** dengan kata sandi saat ini. Simpan kode saat ditampilkan. Untuk menggunakannya, pilih **Lupa kata sandi?** di layar masuk. Setelah berhasil, sesi lama akun orang tua berakhir dan perangkat anak tetap terhubung.
- Jika **Bantuan AI** aktif, orang tua mendapat transkripsi dan perkiraan kemiripan kata dengan teks surah atau cerita. Transkripsi ngaji memakai harakat dari teks surah pada kata yang cocok. Orang tua mengizinkan pengiriman audio dan penyimpanan transkripsi, lalu memutuskan hasil bacaan.
- Setelah semua tugas disetujui, izin TV berlaku 1–60 menit dan hanya bisa dipakai sekali. Jika izin dipakai atau kedaluwarsa, daftar tugas siap dikerjakan lagi. Mengganti tugas atau aturan, mencabut perangkat, atau menghapus rekaman terkait membatalkan izin yang belum dipakai.
- Dua metode koneksi: **infrared** Samsung/LG 38 kHz atau **Wi-Fi lokal**. Pilihan platform Wi-Fi: Android TV/Google TV (Remote Service v2), Samsung Tizen (WSS), LG webOS (SSAP/WSS), dan Wake-on-LAN. Driver jaringan masih perlu pengujian pada TV fisik; lihat [matriks kompatibilitas](docs/tv-compatibility.md).
- Riwayat, pemutaran audio privat, penghapusan oleh orang tua, dan pencabutan sesi anak. Antrean orang tua dan status bacaan yang menunggu diperbarui di belakang layar saat tampilan terkait aktif.

## Batas kontrol TV

Aplikasi mengunci **tombol power di JagaBocil**. Tombol fisik TV, remote lain, atau aplikasi remote lain tetap dapat digunakan. Penguncian menyeluruh memerlukan integrasi yang sesuai dengan perangkat TV atau pengendali daya khusus.

**Infrared:** perlu HP dengan IR blaster. Profil Samsung/LG merupakan kode umum dan harus diuji pada model TV yang dipakai. Power IR bersifat toggle: dapat menyalakan maupun mematikan TV.

**Wi-Fi:** anak memilih TV dari **TV saya → Cari TV di Wi-Fi** atau mengisi platform dan IP lokal di **Opsi TV lainnya**. Pairing dilakukan di HP yang menjadi remote. Android/Google TV memakai kode di layar, Samsung/LG memakai persetujuan registrasi TV. HP dan TV memakai subnet IPv4 privat yang sama. Mode Wake-on-LAN menggunakan MAC jaringan TV.

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

Isi `DEEPSEEK_API_KEY` di environment server untuk mengaktifkan [DeepSeek Speech to Text](https://api-docs.deepseek.com/guides/asr/) dan pembuatan cerita. Server mengubah rekaman hingga 120 detik ke PCM WAV sebelum dikirim. JagaBocil menyimpan transkripsi privat bersama metadata rekaman. Mode manual tetap tersedia saat kunci kosong, rekaman terlalu panjang, atau transkripsi gagal. Izin AI dari penyedia sebelumnya perlu diaktifkan ulang oleh orang tua.

Hasil AI menampilkan perkiraan kemiripan kata dengan teks surah atau kutipan buku. Harakat diambil dari teks surah untuk kata yang cocok dengan transkripsi. Orang tua memeriksa rekaman dan memutuskan izin TV.

Pembuatan versi cerita AI memakai kunci penyedia yang sama. Permintaan berisi judul dan tema template. Jika pembuatan gagal atau penyedia belum dikonfigurasi, anak tetap bisa memakai cerita bawaan.

Alternatif Docker dari root repo:

```sh
docker compose up --build -d
```

Compose membuka port hanya pada loopback. Pasang reverse proxy HTTPS untuk pemakaian dari dua HP. Untuk cloud, isi `DATABASE_URL`, jalankan migrasi dengan URL direct, dan set `AUDIO_STORE=s3` beserta konfigurasi AWS. SQLite/audio lokal menggunakan volume `family-data` dan membutuhkan satu instance dengan disk persisten. Pada Neon/S3, disk lokal dipakai sementara oleh ffprobe; data utama tersimpan di layanan cloud. Backup, HTTPS, dan deployment hosting tetap perlu disiapkan.

Untuk AI pada Docker, ekspor `DEEPSEEK_API_KEY` di shell atau isi `.env` di **root repo** sebelum menjalankan Compose. File `server/.env` dipakai oleh perintah npm.

## Menjalankan Android

Buka folder `android/` di Android Studio. Gunakan JDK 17 atau 21, SDK platform 35, dan build tools 35.0.0. Gradle wrapper disertakan.

```sh
cd android
./gradlew :app:assembleDebug
```

APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`. Minimum Android 8.0 (API 26). Build release membutuhkan signing key milikmu; key tidak disimpan dalam repo.

1. Buka APK pada emulator atau HP. Aplikasi otomatis terhubung ke `https://jagabocil.onrender.com`. Daftar tugas dan pilihan ayat memerlukan aplikasi dan API terbaru serta schema PostgreSQL versi 9.
2. Masuk sebagai **Orang tua** dan buat akun anak. Untuk profil lama, buka **Keluarga → Siapkan login anak** agar profil dan riwayat tetap dipakai.
3. Di HP orang tua, ketuk **Atur bacaan** di Beranda. Pilih **Ngaji** atau **Baca buku**, tentukan bacaannya, lalu ketuk **Simpan tugas**.
4. Di HP anak, masuk lewat **Aku anak**. Bacaan dari orang tua langsung tampil di Beranda. Jika orang tua mengaktifkan **Anak pilih sendiri**, anak bisa memilih surah atau memakai cerita dari Rak Buku dengan tombol **Pakai untuk izin TV**. Rekam, dengarkan bila perlu, lalu pilih **Kirim bacaan**.
5. Di HP orang tua, buka bacaan dari antrean **Beranda** atau notifikasi. Baca transkripsi atau putar audio, lalu pilih **Setujui** atau **Minta ulang**.
6. Di HP anak, pilih perangkat dari **TV saya → Cari TV di Wi-Fi**. Ikuti petunjuk di layar TV bila diminta. Anak juga bisa mengatur TV sebelum mengirim bacaan. Setelah rekaman disetujui, tombol **Nyalakan TV** menggunakan izin satu kali.

Akun yang sama bisa digunakan di dua HP untuk menguji antrean orang tua. Satu instalasi menyimpan satu sesi. Setelah login anak disiapkan atau diganti, masuk kembali di HP anak. Distribusi Play Store belum tersedia.

Akun dari versi lama dapat memasukkan pengenal masuk yang sebelumnya mereka pakai ke kolom Username. Nama tampilan dan kata sandi akun lama tetap bisa digunakan.

## Uji checkout Midtrans Sandbox

Build debug menampilkan **Keluarga → JagaBocil Plus Sandbox** untuk akun orang tua ketika backend diaktifkan dengan Server Key Sandbox. Satu pembayaran uji Rp19.000 memberi status Plus selama 30 hari, dan pembayaran berikutnya memperpanjang masa aktif. Tahap ini menguji pembayaran dan masa aktif Plus. Pembatasan profil dan kuota AI belum diterapkan.

1. Di dashboard Midtrans, pilih **Sandbox → Settings → Access Keys**. Isi `MIDTRANS_SANDBOX_SERVER_KEY` di `server/.env.local` untuk `npm run dev`, di `.env` root untuk Docker Compose, atau sebagai secret server uji. Untuk PostgreSQL, jalankan `npm run db:migrate` dengan URL direct sebelum menjalankan versi baru.
2. Untuk menguji webhook, isi `MIDTRANS_PUBLIC_ORIGIN` dengan origin HTTPS publik server uji. Backend mendaftarkan `/billing/midtrans/notify` untuk setiap transaksi dan memakai origin yang sama untuk halaman kembali dari checkout. Untuk uji lokal tanpa URL publik, gunakan **Cek pembayaran** setelah simulasi.
3. Untuk menguji dengan backend sendiri, ubah `SERVER_URL` di `Api.kt` pada salinan pengembangan dan kompilasi APK debug. Masuk sebagai orang tua, lalu tekan **Uji checkout** di tab **Keluarga**. Buat pembayaran QRIS atau GoPay dan selesaikan dengan [Midtrans Sandbox Simulator](https://simulator.midtrans.com/). Kembali ke aplikasi dan tekan **Cek pembayaran** jika status masih menunggu. Status aktif ditampilkan bersama tanggal berakhirnya.

## Privasi dan akses

Password di-hash dengan scrypt + salt. Token acak disimpan dalam bentuk hash di server, berlaku 30 hari, dan dienkripsi dengan Android Keystore di HP. API audio memerlukan autentikasi dan pemeriksaan kepemilikan keluarga. S3 hanya menggunakan prefix `jagabocil/`, enkripsi SSE-S3, dan respons audio melalui API keluarga; tidak ada URL publik rekaman. Saat startup mode S3, server memakai probe tanpa data anak pada prefix rekaman untuk memeriksa penolakan akses anonim sebelum menerima upload. Upload M4A/WAV dibatasi 10 MB. Durasi maksimal 180 detik untuk surah hingga 20 ayat dan 480 detik untuk surah yang lebih panjang. Server memeriksa durasi berkas menggunakan ffprobe.

Kode pemulihan memiliki entropi 128 bit, disimpan sebagai hash, dan dikonsumsi atomik saat dipakai. Membuat kode baru membatalkan kode sebelumnya. Pemberitahuan Android hanya menyertakan status umum tanpa nama anak atau isi rekaman.

Audio, transkripsi, dan riwayat otomatis dihapus setelah 7 hari, melalui cleanup saat startup dan setiap jam. Hanya orang tua dalam keluarga terkait yang dapat membuka transkripsi. Jika penghapusan S3 gagal, metadata dipertahankan agar cleanup dapat mencoba ulang; rekaman yang sedang dihapus langsung diblokir dari playback dan izin power. Orang tua dapat menghapus lebih awal di riwayat. Cache audio HP dibersihkan setelah dipakai, logout, atau startup berikutnya; rekaman terputus saat aplikasi masuk background dibatalkan. Backup host, disk encryption, dan kebijakan penyedia AI mengikuti konfigurasi operator server. Jangan mengaktifkan AI tanpa memahami pemrosesan audio anak oleh penyedia.

## Sumber Al-Qur’an

Katalog menyimpan 114 surah dan 6.236 ayat dari [Tanzil Quran Text](https://tanzil.net/download/) edisi Uthmani 1.1, dengan urutan ayat yang diperiksa terhadap metadata Tanzil dan katalog Kebun Ngaji. Pemberitahuan hak cipta asli tersimpan bersama edisi masing-masing di `server/src/quran.json`. Teks Arab di Android menggunakan [Amiri Quran](https://github.com/aliftype/amiri/releases/tag/1.003) dengan lisensi SIL OFL yang ikut di APK.

Build Android hanya menyalin teks Juz 30. Untuk surah di luar Juz 30, katalog lokal memuat nama, nomor, jumlah ayat, dan hash teks. APK memeriksa respons `GET /quran/surahs/:id` terhadap katalog itu sebelum menyimpan satu surah. Endpoint tersebut menyajikan teks publik tanpa memerlukan token keluarga.

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
