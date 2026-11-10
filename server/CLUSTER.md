# ICQ New — birnäçe serwer (klaster)

Bir domen (icq-new.online), islendik sanda serwer. Adamlar serwerlere paýlanýar,
hemmesi şol bir bazany görýär, bir serwer öçse programma beýlekisine geçýär.

```
              icq-new.online  (DNS: her serweriň IP-si üçin bir A ýazgy)
          ┌──────────────┬──────────────┬──────────────┐
          ▼              ▼              ▼              ▼
     ESASY serwer     serwer 2       serwer 3   ...  serwer N
     server.py        server.py      server.py       server.py
     PostgreSQL ◄─────┴──────────────┴───────────────┘  (ähli maglumat)
     Redis      ◄─────┴──────────────┴───────────────┘  (kim onlaýn, habarlary geçirmek)
```

* **Baza** — PostgreSQL esasy serwerde. Ähli serwerler şoňa ýazýar → sinhronizasiýa öz-özünden.
* **Redis** — 2-nji serwere birikdirilen adama 1-nji serwerden gelen habar şu ýoldan ýetýär;
  onlaýn ýagdaý, jaňlar, sessiýalary ýapmak hem şu ýoldan.
* **Faýllar** (surat, wideo, golos, awatar) — ýüklenen serwer ony beýlekilere göçürýär;
  bir serwerde ýok bolsa, sorananda beýleki serwerden alýar.
* **APK** — täze wersiýany diňe bir serwere goý (install.sh), beýlekiler 2 minudyň içinde özleri alýar.
* **Arassalaýyş, awto-pozmak** — diňe bir serwer (lider) edýär; lider öçse, beýlekisi lider bolýar.
* **HTTPS** — her serwer öz Let's Encrypt sertifikatyny alýar (barlag Redis arkaly, islendik serwer jogap berýär).

## 1. Esasy serweri taýýarlamak (bir gezek)

Häzirki serwerde (185.159.128.234), täze `server` bukjasy bilen:

```bash
cd /root/server && bash cluster-main.sh
```

Näme edýär: PostgreSQL + Redis gurýar, parollary döredýär, **häzirki SQLite bazany doly
PostgreSQL-e geçirýär** (ulanyjylar, çatlar, habarlar, toparlar, kanallar, publikasiýalar,
balans, sessiýalar — hemmesi; köne faýl `icq_v2.db.bak-…` bolup galýar), serweri klaster
režiminde açýar. Adamlar täzeden girmeli bolmaýar.

## 2. Täze serwer goşmak (islän wagtyň)

1. **Esasy** serwerde:  `bash cluster-add.sh TÄZE_IP`  → kody görkezýär
2. **Täze** serwere `server` bukjasyny göçür (`scp -r server root@TÄZE_IP:/root/`) we:
   `cd /root/server && bash cluster-join.sh KOD`
3. Domen panelinde (DNS) goş:  `A  icq-new.online  TÄZE_IP`  we  `A  www.icq-new.online  TÄZE_IP`
   (köne A ýazgylary pozma).

Admin panel → **«Серверы»**: ähli serwerler, haýsysy işleýär, haýsysy lider, näçe adam onlaýn.

Serweri aýyrmak: DNS-den onuň A ýazgysyny poz, esasy serwerde `bash cluster-remove.sh IP`.

## Bilmeli zatlar

* Hosting firewall-ynda her serwerde aç: TCP 80, 443, 8000; UDP+TCP 3478, UDP 49160-49200.
  Esasy serwerde 5432 (PostgreSQL) we 6379 (Redis) diňe klasteriň serwerlerine açylýar (awtomatik).
* Esasy serwer — bazanyň ýeri. Ol öçse beýleki serwerler hem işläp bilmeýär (baza ýok).
  Her gün 03:30-da baza nusgasy: `/var/backups/icq/` (soňky 7 gün).
* Programmanyň täze APK-syny diňe esasy serwere goýmak ýeterlik.
