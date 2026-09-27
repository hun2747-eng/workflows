<?php
/**
 * DKC – Firebase Cloud Messaging (HTTP v1) küldés, külső könyvtár nélkül.
 *
 * Beállítás:
 *   - A Service Account JSON (Firebase Console → Project settings → Service accounts → Generate new private key)
 *     a webrooton KÍVÜL legyen, pl. /home/dkc/private/hudkcapp-firebase-adminsdk.json, chmod 600.
 *   - Az alábbi konstansban add meg az útvonalát (vagy definiáld előbb a saját config fájlodban).
 */
if (!defined('DKC_FCM_SERVICE_ACCOUNT')) {
    define('DKC_FCM_SERVICE_ACCOUNT', '/home/dkc/private/hudkcapp-firebase-adminsdk.json');
}

function dkc_b64url(string $s): string {
    return rtrim(strtr(base64_encode($s), '+/', '-_'), '=');
}

/** OAuth2 access token a service accountból (55 percig cache-elve). */
function dkc_fcm_access_token(): ?string {
    $cache = sys_get_temp_dir() . '/dkc_fcm_token_' . md5(DKC_FCM_SERVICE_ACCOUNT) . '.json';
    if (is_file($cache)) {
        $c = json_decode((string)file_get_contents($cache), true);
        if (!empty($c['token']) && ($c['exp'] ?? 0) > time() + 60) return $c['token'];
    }
    $sa = json_decode((string)@file_get_contents(DKC_FCM_SERVICE_ACCOUNT), true);
    if (empty($sa['private_key']) || empty($sa['client_email'])) {
        error_log('DKC FCM: service account JSON hiányzik vagy hibás: ' . DKC_FCM_SERVICE_ACCOUNT);
        return null;
    }
    $now = time();
    $header = dkc_b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $claims = dkc_b64url(json_encode([
        'iss' => $sa['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => 'https://oauth2.googleapis.com/token',
        'iat' => $now,
        'exp' => $now + 3600,
    ]));
    $sig = '';
    if (!openssl_sign("$header.$claims", $sig, $sa['private_key'], OPENSSL_ALGO_SHA256)) {
        error_log('DKC FCM: JWT aláírás sikertelen');
        return null;
    }
    $jwt = "$header.$claims." . dkc_b64url($sig);
    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_POSTFIELDS => http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion' => $jwt,
        ]),
    ]);
    $res = json_decode((string)curl_exec($ch), true);
    curl_close($ch);
    if (empty($res['access_token'])) {
        error_log('DKC FCM: OAuth hiba: ' . json_encode($res));
        return null;
    }
    @file_put_contents($cache, json_encode(['token' => $res['access_token'], 'exp' => $now + (int)($res['expires_in'] ?? 3600)]), LOCK_EX);
    @chmod($cache, 0600);
    return $res['access_token'];
}

/**
 * Egy üzenet küldése egy eszköznek.
 * @param array $data  csak string értékek! (pl. ['type'=>'new_registration','count'=>'3'])
 * @param array|null $notification  ['title'=>..,'body'=>..] – NE add meg, ha az appnak kell kezelnie (admin értesítés)
 * @return string 'ok' | 'unregistered' (a tokent törölni kell) | 'error'
 */
function dkc_fcm_send(string $deviceToken, array $data, ?array $notification = null): string {
    $sa = json_decode((string)@file_get_contents(DKC_FCM_SERVICE_ACCOUNT), true);
    $projectId = $sa['project_id'] ?? 'hudkcapp';
    $access = dkc_fcm_access_token();
    if (!$access) return 'error';

    $msg = [
        'token' => $deviceToken,
        'data' => array_map('strval', $data),
        'android' => ['priority' => 'high'],
    ];
    if ($notification) $msg['notification'] = $notification;

    $ch = curl_init("https://fcm.googleapis.com/v1/projects/$projectId/messages:send");
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 15,
        CURLOPT_HTTPHEADER => ['Authorization: Bearer ' . $access, 'Content-Type: application/json'],
        CURLOPT_POSTFIELDS => json_encode(['message' => $msg]),
    ]);
    $body = (string)curl_exec($ch);
    $code = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($code === 200) return 'ok';
    if ($code === 404 || strpos($body, 'UNREGISTERED') !== false || strpos($body, 'registration-token-not-registered') !== false) {
        return 'unregistered';
    }
    error_log("DKC FCM: küldési hiba ($code): $body");
    return 'error';
}
