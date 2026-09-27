# Admin push – új klíma-igény értesítés

Az app (admin módban) a telefon ikonján számmal és push értesítéssel jelzi, ha új ügyfél kerül a
https://dkc.hu/admin?tab=uj-klima listába.

## Szerver teendők
1. `fcm.php`, `admin_push.php`, `admin-push-token-endpoint.php` → pl. `/home/dkc/app/push/` (webrooton kívül is lehet).
2. `fcm.php` elején: `DKC_FCM_SERVICE_ACCOUNT` = a Service Account JSON útvonala (webrooton kívül, chmod 600).
   `admin_push.php`: `DKC_ADMIN_TOKENS_FILE` = írható fájl a webrooton kívül.
3. Útvonal: `POST /api/app/admin-push-token` → `admin-push-token-endpoint.php`
   (a benne lévő két TODO: admin session kulcs és CSRF – igazítsd a meglévő admin belépéshez).
4. Új klíma-igény mentése után:
   ```php
   require_once '/home/dkc/app/push/admin_push.php';
   dkc_notify_admins_new_registration($pendingCount, $customerName);
   ```
   `$pendingCount` = hány tétel van most az "Új klímát szeretne" listában.

## Üzenet formátum (FCM data, notification NÉLKÜL, android.priority=high)
`type=new_registration`, `count=<db>`, `title`, `body`, `url=https://dkc.hu/admin?tab=uj-klima`

## Tartalék az appban
Push nélkül is: az app 10 percenként (Android WorkManager) lekéri az admin oldalt az admin munkamenettel,
és ha az "Új klímát szeretne" szám nőtt, értesít. Ez csak addig működik, amíg az admin munkamenet él.
