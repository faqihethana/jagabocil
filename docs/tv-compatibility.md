# Infrared dan Wi-Fi TV

JagaBocil menyediakan Wi-Fi dan infrared di **TV saya**. Anak memilih TV. Setelah bacaan disetujui, anak mendapat satu kali izin menggunakan tombol power di aplikasi.

Driver berikut sudah diimplementasikan dan memiliki tes format protokol/akses. **Kompatibilitas pada perangkat fisik belum diverifikasi.** Pilihan platform mencakup keluarga sistem TV, bukan jaminan seluruh model, merek, tahun, atau firmware.

| Pilihan | Jalur | Pairing di HP anak | Menyalakan TV |
| --- | --- | --- | --- |
| Infrared Samsung/LG | ConsumerIrManager, 38 kHz | Tidak ada | Kode IR power toggle; perlu IR blaster dan model yang cocok. |
| Android TV / Google TV | Remote Service v2; TLS 6466, pairing TLS 6467 | Masukkan enam karakter heksadesimal yang muncul pada layar TV | `KEYCODE_WAKEUP` saat layanan masih tersedia; Wake-on-LAN opsional jika layanan tidak terjangkau. |
| Samsung Tizen | WebSocket TLS 8002, API remote v2 | Setujui akses remote pada layar TV | `KEY_POWERON`, jika model mendukungnya; Wake-on-LAN opsional saat layanan tidak terjangkau. |
| LG webOS | SSAP melalui WebSocket TLS 3001 | Setujui registrasi remote pada layar TV | `turnOnScreen` saat layanan masih hidup; bangun dari standby penuh membutuhkan Wake-on-LAN jika didukung. |
| Wake-on-LAN saja | UDP magic packet ke directed broadcast subnet Wi-Fi, port 9 | Isi MAC di **Opsi TV lainnya** | Hanya bangun dari standby pada TV yang mendukung wake melalui jaringan. |

## Menyiapkan Wi-Fi

1. Hubungkan HP anak ke Wi-Fi rumah. TV harus berada pada subnet IPv4 yang sama; TV dengan kabel LAN pada router yang sama juga dapat bekerja jika router mengizinkan akses/broadcast antarperangkat.
2. Pada akun anak, buka **Panduan → TV saya**. Saat bacaan belum direkam, layar **Beranda** juga menyediakan pintasan ke pilihan TV.
3. Tekan **Cari TV di Wi-Fi** saat TV menyala. Pilih perangkat untuk mengisi platform dan IP lokal. Pencarian membaca iklan mDNS Android/Google TV serta iklan SSDP Samsung dan LG. Pilih platform dan isi IP secara manual jika TV tidak muncul. Mode **Wake-on-LAN saja** memakai MAC yang dimasukkan manual.
4. Isi IPv4 lokal TV (misalnya `192.168.1.20`) untuk tiga driver remote. Cari alamat tersebut pada pengaturan jaringan TV. Reservasi DHCP pada router membantu menjaga IP tetap sama.
5. Isi MAC dari antarmuka jaringan TV yang sedang dipakai bila ingin mencoba wake jaringan. Untuk TV tersambung lewat Wi-Fi, gunakan MAC Wi-Fi, bukan MAC Ethernet. Mode Wake-on-LAN saja mewajibkan MAC.
6. Nyalakan TV memakai remote fisik dan ikuti petunjuk **Hubungkan TV** di HP anak. Untuk Google TV, masukkan kode di layar. Untuk Samsung/LG, setujui permintaan akses.
7. Rekam ngaji, tunggu persetujuan, lalu gunakan tombol power. Backend untuk ngaji tetap harus dapat diakses; perintah TV dikirim langsung dari HP ke TV melalui jaringan lokal.

Di **TV saya**, tekan **Cek sambungan** saat TV menyala. Aplikasi mengecek sesi remote yang sudah dipairing, Wi-Fi dan MAC untuk Wake-on-LAN, atau dukungan IR 38 kHz sesuai pilihan. Izin power tetap tersedia setelah pengecekan.

Nama pengaturan wake bervariasi: Wake-on-LAN, Wake on Wireless LAN, Mobile TV On, IP Remote, atau opsi menyalakan lewat perangkat seluler. Dukungan saat TV standby berbeda dari dukungan saat TV menyala. Aplikasi tidak dapat mengaktifkan kemampuan wake yang tidak dimiliki TV.

## Batas kompatibilitas

- Android/Google TV memerlukan **Android TV Remote Service v2** aktif. Perangkat dengan remote lama atau tanpa layanan ini belum didukung oleh driver v2.
- Samsung memerlukan remote API v2 melalui TLS 8002. Driver legacy terenkripsi atau model yang hanya melayani port 8001 belum didukung.
- LG memerlukan SSAP melalui TLS 3001. TV yang hanya menerima koneksi plaintext port 3000 belum didukung. `turnOnScreen` tidak boleh dianggap sebagai kemampuan menyalakan TV yang layanan jaringannya sudah mati.
- TV dengan sistem lain, misalnya Roku, VIDAA, atau Apple TV, belum memiliki driver khusus. Wake-on-LAN dapat dicoba hanya jika perangkat itu memang mendukungnya.
- Jaringan tamu, AP/client isolation, VLAN terpisah, IPv6-only, atau router yang memblokir broadcast dapat menghalangi kontrol. Aplikasi memeriksa subnet lokal, tetapi tidak mengklaim telah menemukan TV atau menerima konfirmasi dari paket Wake-on-LAN.
- Wake-on-LAN biasanya memerlukan standby dan daya listrik tetap tersambung. TV yang benar-benar mati atau dicabut listriknya tidak dapat dibangunkan dengan cara ini.
- Perintah yang sudah dikirim tidak menjamin TV menyala. Samsung dan Wake-on-LAN tidak memberikan konfirmasi keadaan power di implementasi ini. Percobaan yang sudah diotorisasi menghabiskan izin sekali pakai, termasuk bila TV tidak merespons.

## Akses dan identitas

Server menyimpan pilihan TV pada profil anak dan memasukkannya ke izin power. Aplikasi memeriksa bahwa target masih sesuai dengan izin tersebut. Alamat remote dibatasi IPv4 privat literal pada subnet Wi-Fi HP. Port dan protokol ditentukan driver.

Token Samsung, client key LG, dan pin identitas TV disimpan terenkripsi dengan Android Keystore di HP. Identitas pertama didaftarkan melalui pairing lokal (trust on first use), lalu public key sertifikat diperiksa pada koneksi berikutnya. Android TV menggunakan sertifikat klien dari Android Keystore dan challenge kode di layar. Tidak ada pematian pemeriksaan TLS secara global untuk API aplikasi.

Jika identitas TV berubah, hubungkan ulang melalui pairing di layar. Logout menghapus kredensial pairing lokal yang disimpan aplikasi; daftar remote yang diizinkan pada TV dapat dikelola dari pengaturan TV. Mengganti aturan atau mencabut perangkat anak juga mencabut izin ngaji yang belum digunakan.

## Uji yang masih harus dilakukan pada perangkat

- Pairing diterima, ditolak, kode salah, dan kode kedaluwarsa pada tiap platform.
- TV menyala, screen-off, standby ringan, standby penuh, serta opsi wake dinonaktifkan.
- HP tanpa IR blaster menggunakan Wi-Fi; HP dengan IR mengirim kode ke model yang sesuai.
- IP TV berubah, Wi-Fi HP terputus/berpindah, router melakukan client isolation, dan identitas TV berbeda.
- Rekaman pending/ditolak/kedaluwarsa tidak mengirim power; satu izin tidak dapat dipakai dua kali.

## Referensi protokol

Implementasi Kotlin menggunakan format protokol yang juga didokumentasikan pada:

- [Android TV Remote Service v2 / androidtvremote2](https://github.com/tronikos/androidtvremote2)
- [Samsung remote API v2 / samsung-tv-ws-api](https://github.com/xchwarze/samsung-tv-ws-api)
- [LG SSAP / PyWebOSTV](https://github.com/supersaiyanmode/PyWebOSTV)

Tidak ada paket Python atau jembatan Python yang diperlukan pada HP maupun server JagaBocil.
