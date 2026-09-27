<?php
/**
 * DKC – admin push: eszköz tokenek tárolása és értesítés új klíma-igényről.
 *
 * Tárolás: egyszerű JSON fájl a webrooton KÍVÜL (nem kell hozzá adatbázis tábla).
 */
require_once __DIR__ . '/fcm.php';

if (!defined('DKC_ADMIN_TOKENS_FILE')) {
    define('DKC_ADMIN_TOKENS_FILE', '/home/dkc/private/admin_push_tokens.json');
}

function dkc_admin_tokens_load(): array {
    $d = json_decode((string)@file_get_contents(DKC_ADMIN_TOKENS_FILE), true);
    return is_array($d) ? $d : [];
}

function dkc_admin_tokens_save(array $tokens): void {
    @file_put_contents(DKC_ADMIN_TOKENS_FILE, json_encode($tokens, JSON_PRETTY_PRINT), LOCK_EX);
    @chmod(DKC_ADMIN_TOKENS_FILE, 0600);
}

/** Token mentése (az admin azonosítójával, pl. e-mail vagy user id). */
function dkc_admin_token_add(string $token, string $adminId, string $platform = 'android'): void {
    $t = dkc_admin_tokens_load();
    $t[$token] = ['admin' => $adminId, 'platform' => $platform, 'updated' => date('c')];
    dkc_admin_tokens_save($t);
}

function dkc_admin_token_remove(string $token): void {
    $t = dkc_admin_tokens_load();
    unset($t[$token]);
    dkc_admin_tokens_save($t);
}

/**
 * EZT HÍVD MEG, amikor új ügyfél regisztrál az "Új klímát szeretne" listába
 * (pl. a regisztráció mentése után).
 *
 * @param int    $pendingCount  hány tétel van most az "Új klímát szeretne" listában (ez lesz a szám az app ikonján)
 * @param string $customerName  opcionális, az értesítés szövegébe kerül
 */
function dkc_notify_admins_new_registration(int $pendingCount, string $customerName = ''): void {
    $title = 'Új klíma-igény érkezett';
    $body = $customerName !== ''
        ? "$customerName regisztrált. Összesen $pendingCount vár feldolgozásra."
        : "$pendingCount ügyfél vár feldolgozásra az „Új klímát szeretne” listában.";
    $data = [
        'type'  => 'new_registration',
        'count' => (string)max(1, $pendingCount),
        'title' => $title,
        'body'  => $body,
        'url'   => 'https://dkc.hu/admin?tab=uj-klima',
    ];
    foreach (array_keys(dkc_admin_tokens_load()) as $token) {
        // FONTOS: csak data üzenet (notification nélkül) – így az app mutatja a számot az ikonon
        if (dkc_fcm_send($token, $data) === 'unregistered') dkc_admin_token_remove($token);
    }
}
