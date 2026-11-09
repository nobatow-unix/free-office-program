# ICQ New 3.0

Android messenjer (Kotlin + Jetpack Compose) + Python serwer (FastAPI, SQLite, WebSocket).

## Täze zatlar (3.0)
* **Kanallar** — aşaky paneliň ortasynda. Diňe adminler ýazýar, beýlekiler abuna bolýar. Açyk (public) kanallary gözläp tapyp bolýar.
* **Gözleg** — adamlar, açyk toparlar we kanallar, öz çatlaryň we habarlaryň.
* **Toparlarda we kanallarda adminler**: profil sahypasynda «⋮» → ýazmagy gadagan etmek (5 min / 1 sagat / 1 gün / ömürlik), admin bellemek, çykarmak, blokirlemek.
* **Buýruklar** (admin, habara jogap edip ýa-da ID ýazyp):
  `.mute 5m`, `.mute 1h`, `.mute 1d`, `.mute` (ömürlik), `.unmute`, `.ban`, `.unban ID`, `.kick`, `.admin`, `.unadmin`, `.del`, `.help`
* **Audio we wideo jaň** (WebRTC) + jaňlar sanawy («Звонки» bölümi).
* **Firebase bildirişleri** — programma ýapyk bolsa-da habar/jaň gelýär (aşakda sazlamak).
* **Emojiler** — 1595 sany 3D emoji (Microsoft Fluent Emoji, MIT ygtyýarnamasy), ähli telefonda birmeňzeş.
* **Emoji klawiaturasy** klawiatura bilen çalyşýar: emoji açylanda klawiatura ýapylýar, klawiatura açylanda emoji ýapylýar, beýikligi birmeňzeş.
* **Surat redaktory** — profil/topar suraty we ugradylýan suratlar: kesmek, ulaltmak, aýlamak, şöhlelendirmek, 7 süzgüç.
* Çaty çepe çekip pozmak, owadan habar köpürjikleri, uly emoji habarlary, ses tolkuny.
* **Onlaýn**: programmada bolsaň «в сети», çykanyňda wagt ýa-da (gizlän bolsaň) «был(а) недавно».
* **Tema**: Awto / Ýagty / Garaňky + 7 reňk + tekst ölçegi. Ýagdaý paneli hem tema bilen üýtgeýär.
* Täze sazlamalar: wibrasiýa, bildirişde teksti görkezmek, blokirlenenler, keşi arassalamak.
* Öz ikonkalarym (çatlar, kanallar, jaňlar, kontaktlar, sazlamalar). Çepdäki menýu aýryldy.

## 1. Serwer (VPS 185.159.128.234)
```
scp -r server root@185.159.128.234:/root/
ssh root@185.159.128.234 "cd /root/server && bash install.sh"
```
* Baza awtomatik täzelenýär — ulanyjylar we hatlar saklanýar.
* Portlar: **TCP 8000**, jaňlar üçin **UDP+TCP 3478** we **UDP 49160-49200** (install.sh coturn gurýar).

## 2. Firebase (bildirişler) — islege görä
1. https://console.firebase.google.com → täze taslama döret.
2. «Add app» → Android → paket ady: `com.icqnew.app` → `google-services.json` faýlyny ýükle
   → ony **`app/`** bukjasyna goý (`icq-new/app/google-services.json`).
3. Project settings → Service accounts → «Generate new private key» → ýüklenen faýly
   **`server/firebase.json`** diýip at bilen goý we serweri täzeden gur (1-nji ädim).
Bu faýllar bolmasa-da programma işleýär, diňe ýapyk wagty bildiriş gelmeýär.

## 3. Admin panel
`http://185.159.128.234:8000/admin` — login `admin`, parol: `/opt/icq-server/admin_password.txt`.

## 4. APK (Kali Linux)
```
bash build-apk.sh
```
Netije: `~/Загрузки/icq-new.apk`.

## 3.3 täzelikler
* **ICQ Bot** (ID 10000000, @icqbot) — çatlarda adam ýaly ýazýar: `/start`, `/help`, `/id`, `/me`, `/username`, `/groups`, `/stories`, `/time`, `/stats`.
* **Istoriýalar** — Çatlar sahypasynyň ýokarsynda; 24 sagat görünýär, görenleriň sanawy, pozmak.
* **Çat fony** — her çat üçin aýratyn ýa-da hemme çatlar üçin (Sazlamalar → Фон чатов), reňk, gradient ýa-da öz suratyňyz.
* Öz ýaşyl galoçkaňyz Sazlamalarda görünýär; profil suratyndaky ýarym ýaşyl tegelek düzedildi; "UIN" ýerine diňe "ID".

## 3.4 täzelikler
* Kontakt goşmak: nomer ýa-da ID / @at bilen, öz adyňyz bilen, "Показать мой номер" (nomeriňizi görkezip ýa-da görkezmän).
* Okjagazlar: ⇄ ikiňiz hem goşan, → diňe siz goşan, ← ol sizi goşan. "Добавили вас" bölümi + "Добавить в ответ".
* Gizlinlik: "Кто видит фото профиля" we "Кто видит мой номер" (Все / Мои контакты / Никто).
* Istoriýa: awataryň daşynda ýaşyl tegelek, profilde surata basyp görmek, reaksiýalar, görenler aşakdan çykýan shtorkada.
* Bot: `.help` — bot şahsy çata ýazýar; bot toparda agza ýaly görünýär; `/stats`, `/time` aýryldy; "в сети" ýazmaýar.
* 4 GB faýl (diskden akdyrylyp iberilýär), programmanyň içindäki faýl açyjy (surat, wideo, aýdym, PDF, tekst).
* Iki tarap hem pozan çatlar serwerde-de pozulýar; 6 sagatdan bir ulanylmaýan faýllar arassalanýar we baza gysylýar (VACUUM).
* Login: telefon kody IP boýunça (ýa-da SIM boýunça) özi goýulýar.
* Animasiýalar: ekran geçişleri, tablar, habarlaryň peýda bolşy, mikrofon/ibermek düwmesi.

## 3.5 täzelikler
* Aşaky menýu: Личные · Группы · Каналы · Боты · Профиль (öz suratyňyz bilen). Zwonkilar indi çatyň içinde görünýär.
* Profil sahypasy: surat, "Изменить", "Настройки", doglan gün, öz kanalyňyz, profil reňki, istoriýalar, Kontaktlar, Web wersiýa (QR skaner).
* Sazlamalar tertiplendi: Аккаунт, Конфиденциальность, Уведомления и звуки, Чаты, Оформление, Данные.
* Arhiw + "Свайп влево" sazlamasy (Удалить / В архив / Закрепить / Без звука / Прочитано).
* Bildiriş sesi: ICQ «О-оу!» / Klassik / Telefonyň sesi / Sessiz — indi "media" däl, bildiriş sesi bilen çykýar.
* Tekst formatlamak (Жирный, Курсив, Моно, Зачёркнутый, Подчёркнутый, Скрытый, Код), @bellik, salgy (URL) öňünden görkezmek, nomere basanda ICQ-da barmy barlamak.
* Tegelek wideo habarlar, ses ýazylanda göterilýän taýajyklar, habarlary awtomatik pozmak (1 sag / 1 gün / 1 hepde / 1 aý).
* Hasaba alynanda ýurt saýlamak (baýdaklar bilen), kod IP boýunça özi goýulýar.
* ID-ler tötänleýin (9 sanly), täze adam hiç kime görkezilmeýär, nomer başgalara gizlin.
* Serwer habarlary diňe 14 gün saklaýar (ICQ_KEEP_DAYS), taryh telefonda.
* Saýt: http://IP/ (programmany ýüklemek), web wersiýa: http://IP/web (QR bilen girmek). 80-nji port açylýar.

## 3.6 täzelikler
* Domen: programma `https://icq-new.online` bilen işleýär (açylmasa özi `http://icq-new.online`, soňra IP-ä geçýär). install.sh nginx + Let's Encrypt (HTTPS) goýýar.
* Öz QR skaneri (telefony gapdala öwürmeli däl).
* Täze bot suraty. Başgalaryň profilinde: Чат · Звонок · Видео · Звук düwmeleri, "Ссылка" (paýlaşmak).
* Formatlamak: tekst saýlananda ýokarda B / I / Моно / … paneli çykýar.
* @bellik: diňe @username ýazylýar; sizi bellik etseler bildiriş gelýär, çatlarda "@" nyşany, çatda "@" düwmesi şol habara geçirýär.
* Ssylkalar: icq-new.online/@username we /g/ID — çatda profil/kanal kartoçkasy, basanyňda programmanyň içinde açylýar; saýtda hem sahypa bar.
* Emoji panelinde baýdaklar.
* Web wersiýa: nomer bilen girmek/hasaba alynmak (ýurt baýdaklary bilen) + QR.
* Täze wersiýa: aşakda "Доступна новая версия" çykýar, şol ýerden ýüklenip gurnalýar.
  Serwere goýmak: server/apk/icq-new.apk + server/apk/version.json (code, name, notes).

## 3.7 täzelikler
* HTTPS: `bash /opt/icq-server/ssl.sh icq-new.online` — sertifikat alýar ýa-da näme üçin alynmaýandygyny görkezýär.
* Habar ýazylýan meýdança kiçeldildi. Aşaky menýuda täze bot nyşany, botlaryň galoçkasy gök.
* Ses habary: basyp saklamak (pauza), taýajyklaryň üstünde barmak bilen yza/öňe, tizlik 1x/1.5x/2x.
* Suratlar çatda doly görünýär (kesilmeýär).
* Istoriýalar: kommentariýalar (ýazmak, okamak, pozmak) we "Поделиться".

## Domenler
* **icq-new.online** — programma (API) we web wersiýa (`https://icq-new.online` açylanda web wersiýa).
* **icq-new.ru** — saýt: programmany ýüklemek (`/download`), profil/kanal sahypalary.
* Iki domende hem A ýazgy: `@ -> 185.159.128.234` we `www -> 185.159.128.234`. Soň: `bash /opt/icq-server/ssl.sh`.
