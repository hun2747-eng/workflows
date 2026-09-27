# DKC Android app (hu.dkc.app)

WebView alapú Android app a https://dkc.hu oldalhoz, natív "Fiókom" résszel.

- App Links: `https://dkc.hu/app/belepes?uuid=…`, `https://dkc.hu/app/ajanlas?ref=…`
- Bearer token: a `uuid` mentve, minden API hívás `Authorization: Bearer <uuid>`
- Ajánlás: `GET /api/app/customer` → `referral_code` → `https://dkc.hu/app/ajanlas?ref=<kód>` megosztás
- Regisztráció: `POST /api/app/register` (a deep linkből kapott `ref` a `referral_code` mezőben)
- Push: FCM → `POST /api/app/push-token`, `POST /api/app/push-consent`
- JS híd a weboldalnak: `window.DKCApp.isApp()`, `getToken()`, `getReferralCode()`, `openAccount()`

## Build
GitHub Actions (`.github/workflows/build.yml`), ág: `dkc-android`. Eredmény a `dkc-builds` ágon.

Opcionális repository secretek:
- `GOOGLE_SERVICES_JSON` – a Firebase konzolból letöltött google-services.json tartalma (ettől aktív a push)
- `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` – aláírás a CI-ban (ha nincs, unsigned build készül)

A szerverre: `server/assetlinks.json` → `https://dkc.hu/.well-known/assetlinks.json`
