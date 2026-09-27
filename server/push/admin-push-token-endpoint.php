<?php
/**
 * POST /api/app/admin-push-token
 *
 * Az app hívja (a WebView-ból, az admin munkamenet sütijével), amikor az admin be van jelentkezve.
 * Kérés (JSON): {"token":"<FCM token>","platform":"android","csrf_token":"<az oldal csrf_token-je>"}
 *   + fejléc: X-CSRF-Token: <csrf_token>
 * Válasz: 200 {"ok":true} | 401 {"error":"unauthorized"} | 400 {"error":"invalid"}
 *
 * A két "TODO" sort kösd be a meglévő admin belépés / CSRF ellenőrzésedhez.
 */
require_once __DIR__ . '/admin_push.php';

header('Content-Type: application/json; charset=utf-8');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); echo '{"error":"method"}'; exit; }

if (session_status() !== PHP_SESSION_ACTIVE) session_start();

// TODO: a meglévő admin belépés ellenőrzése – pl. if (empty($_SESSION['admin_id'])) ...
$adminId = $_SESSION['admin_id'] ?? $_SESSION['admin_email'] ?? null;
if (!$adminId) { http_response_code(401); echo '{"error":"unauthorized"}'; exit; }

$in = json_decode((string)file_get_contents('php://input'), true) ?: [];

// TODO: a meglévő CSRF ellenőrzés – az app a csrf_token-t fejlécben és a törzsben is elküldi
$csrf = $_SERVER['HTTP_X_CSRF_TOKEN'] ?? ($in['csrf_token'] ?? '');
if (!empty($_SESSION['csrf_token']) && !hash_equals((string)$_SESSION['csrf_token'], (string)$csrf)) {
    http_response_code(401); echo '{"error":"csrf"}'; exit;
}

$token = trim((string)($in['token'] ?? ''));
if ($token === '' || strlen($token) > 4096 || !preg_match('/^[A-Za-z0-9_:\-\.]+$/', $token)) {
    http_response_code(400); echo '{"error":"invalid"}'; exit;
}
dkc_admin_token_add($token, (string)$adminId, (string)($in['platform'] ?? 'android'));
echo '{"ok":true}';
