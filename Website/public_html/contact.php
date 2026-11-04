<?php
declare(strict_types=1);

// mail() usa Sendmail del alojamiento; no admite usuario/contraseña SMTP.
// Hostinger debe permitir enviar desde esta dirección de tu dominio.
$to = "daniel@drasite.com";
$from = "daniel@drasite.com";

ini_set("display_errors", "0");
ini_set("log_errors", "1");
header("Content-Type: application/json; charset=utf-8");
header("Cache-Control: no-store");
header("X-Robots-Tag: noindex");

function reply(
    int $status,
    bool $ok,
    string $message,
    array $details = [],
): never {
    // The form URL carries the language for both the token and submission requests.
    if (($_POST["lang"] ?? ($_GET["lang"] ?? "en")) === "es") {
        $message = strtr($message, [
            "Please check the form fields." =>
                "Revisa los campos del formulario.",
            "Method not allowed." => "Método no permitido.",
            "The enquiry is too large." => "La consulta es demasiado larga.",
            "Please email daniel@drasite.com directly." =>
                "Escribe directamente a daniel@drasite.com.",
            "Please try sending the form again." =>
                "Vuelve a intentar enviar el formulario.",
            "Thank you for your enquiry." => "Gracias por tu consulta.",
            "Please complete all fields with a valid email and a message of at least 10 characters." =>
                "Completa todos los campos con un correo válido y un mensaje de al menos 10 caracteres.",
            "Please wait 30 seconds before sending another enquiry." =>
                "Espera 30 segundos antes de enviar otra consulta.",
            "Your enquiry has been submitted. Daniel will reply by email." =>
                "Tu consulta se ha enviado. Daniel te responderá por correo electrónico.",
            "Email delivery is disabled on this server." =>
                "El envío de correo está deshabilitado en este servidor.",
            "The server could not accept your enquiry for sending." =>
                "El servidor no ha podido aceptar tu consulta para enviarla.",
            " Please email daniel@drasite.com directly. Reference: " =>
                " Escribe directamente a daniel@drasite.com. Referencia: ",
        ]);
    }
    http_response_code($status);
    echo json_encode(
        array_merge(["ok" => $ok, "message" => $message], $details),
    );
    exit();
}

// El detalle técnico queda en el log de PHP, no en la página pública.
function mailFailure(string $code, string $detail): never
{
    $reference = bin2hex(random_bytes(4));
    error_log(
        "[M111 contact " .
            $reference .
            "] " .
            json_encode(
                [
                    "code" => $code,
                    "detail" => $detail,
                    "sendmail_configured" =>
                        trim((string) ini_get("sendmail_path")) !== "",
                    "forced_mail_parameters" =>
                        trim(
                            (string) ini_get("mail.force_extra_parameters"),
                        ) !== "",
                ],
                JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE,
            ),
    );
    $message =
        $code === "mail_unavailable"
            ? "Email delivery is disabled on this server."
            : "The server could not accept your enquiry for sending.";
    reply(
        $code === "mail_rejected" ? 502 : 503,
        false,
        $message .
            " Please email daniel@drasite.com directly. Reference: " .
            $reference .
            ".",
        ["code" => $code, "reference" => $reference],
    );
}

function input(string $key, int $max): string
{
    $value = $_POST[$key] ?? "";
    if (
        !is_string($value) ||
        strlen($value) > $max * 4 ||
        !preg_match("//u", $value) ||
        preg_match_all("/./us", $value) > $max
    ) {
        reply(422, false, "Please check the form fields.");
    }
    return trim($value);
}

$method = $_SERVER["REQUEST_METHOD"] ?? "";
if (!in_array($method, ["GET", "POST"], true)) {
    header("Allow: GET, POST");
    reply(405, false, "Method not allowed.");
}
if ((int) ($_SERVER["CONTENT_LENGTH"] ?? 0) > 20000) {
    reply(413, false, "The enquiry is too large.");
}
session_name("M111CONTACT");
ini_set("session.use_strict_mode", "1");
ini_set("session.use_only_cookies", "1");
session_set_cookie_params([
    "httponly" => true,
    "samesite" => "Lax",
    "secure" => !empty($_SERVER["HTTPS"]) && $_SERVER["HTTPS"] !== "off",
]);
if (!session_start()) {
    reply(503, false, "Please email daniel@drasite.com directly.");
}

// El formulario actual pide este token antes de enviar los datos.
if ($method === "GET") {
    $_SESSION["contact_token"] ??= bin2hex(random_bytes(32));
    echo json_encode(["token" => $_SESSION["contact_token"]]);
    exit();
}
if (
    empty($_SESSION["contact_token"]) ||
    !hash_equals($_SESSION["contact_token"], input("token", 64))
) {
    reply(403, false, "Please try sending the form again.");
}
if (input("website", 200) !== "") {
    reply(200, true, "Thank you for your enquiry.");
}

$name = input("name", 100);
$email = input("email", 254);
$model = input("model", 100);
$engine = input("engine", 50);
$configuration = input("configuration", 20);
$message = input("message", 2500);
if (
    $name === "" ||
    $model === "" ||
    $engine === "" ||
    $configuration === "" ||
    !filter_var($email, FILTER_VALIDATE_EMAIL) ||
    preg_match('/[\r\n]/', $email) ||
    preg_match_all("/./us", $message) < 10
) {
    reply(
        422,
        false,
        "Please complete all fields with a valid email and a message of at least 10 characters.",
    );
}
if (time() - ($_SESSION["last_attempt"] ?? 0) < 30) {
    header("Retry-After: 30");
    reply(429, false, "Please wait 30 seconds before sending another enquiry.");
}
$_SESSION["last_attempt"] = time();

if (!function_exists("mail")) {
    mailFailure(
        "mail_unavailable",
        "PHP mail() is disabled or unavailable on this hosting account.",
    );
}
// El remitente es configuración fija, nunca un dato del formulario.
// Se restringe su formato porque también se pasa como argumento de Sendmail.
if (
    !filter_var($to, FILTER_VALIDATE_EMAIL) ||
    !filter_var($from, FILTER_VALIDATE_EMAIL) ||
    !preg_match("/\A[a-z0-9._+\-]+@[a-z0-9.\-]+\z/i", $from)
) {
    mailFailure(
        "mail_configuration",
        "Invalid fixed recipient or sender address in contact.php.",
    );
}

$body = "Name: $name\nEmail: $email\nModel: $model\nEngine: $engine\nConfiguration: $configuration\n\n$message";
// Texto con CRLF: compatible también con servicios mail() que rechazan arrays.
$headers = implode("\r\n", [
    "From: M111Kompressor <$from>",
    "Reply-To: $email",
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: base64",
]);
$warnings = [];
set_error_handler(static function (int $severity, string $warning) use (
    &$warnings,
): bool {
    $warnings[] = $warning;
    return true;
});
$sent = false;
try {
    // -f fija el remitente del sobre en Sendmail (Linux/Hostinger).
    // No inicia sesión. En Windows, el transporte nativo usa la cabecera From.
    $sent = mail(
        $to,
        "M111 Kompressor - Contact form",
        chunk_split(base64_encode($body)),
        $headers,
        "-f" . $from,
    );
} catch (Throwable $error) {
    $warnings[] = $error->getMessage();
} finally {
    restore_error_handler();
}
if (!$sent) {
    mailFailure(
        "mail_rejected",
        $warnings
            ? implode(" | ", $warnings)
            : "mail() returned false without a PHP warning. Check the hosting Sendmail service, authorised sender and sending limits.",
    );
}

unset($_SESSION["contact_token"]);
reply(
    200,
    true,
    "Your enquiry has been submitted. Daniel will reply by email.",
);
