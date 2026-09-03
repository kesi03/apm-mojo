$ErrorActionPreference = "Stop"

$repository = "kesi03/apm-mojo"
$email = "github-actions@kesi03.apm-mojo"
$name = "GitHub Actions Maven Publisher"
$homeDir = Join-Path ([System.IO.Path]::GetTempPath()) ("apm-mojo-gpg-" + [guid]::NewGuid())
$parameters = Join-Path $homeDir "key-parameters"
$passphrase = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))

New-Item -ItemType Directory -Path $homeDir | Out-Null
$gpgHome = "/" + $homeDir.Substring(0, 1).ToLower() + $homeDir.Substring(2).Replace("\", "/")
$gpgParameters = "/" + $parameters.Substring(0, 1).ToLower() + $parameters.Substring(2).Replace("\", "/")
$env:GNUPGHOME = $gpgHome

try {
    @"
%echo Generating Maven Central signing key
Key-Type: RSA
Key-Length: 3072
Name-Real: $name
Name-Email: $email
Expire-Date: 0
Passphrase: $passphrase
%commit
"@ | Set-Content -LiteralPath $parameters -Encoding ascii

    gpg --batch --generate-key $gpgParameters
    $fingerprint = (gpg --batch --list-secret-keys --with-colons |
        Select-String "^fpr" | Select-Object -First 1).ToString().Split(":")[9]
    if ([string]::IsNullOrWhiteSpace($fingerprint)) {
        throw "Unable to determine generated GPG fingerprint"
    }

    "$fingerprint`:6:" | gpg --batch --import-ownertrust
    gpg --batch --keyserver hkps://keyserver.ubuntu.com --send-keys $fingerprint

    $privateKey = gpg --batch --pinentry-mode loopback --passphrase $passphrase `
        --armor --export-secret-keys $fingerprint
    $privateBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($privateKey))
    $privateFile = Join-Path $homeDir "private-key.base64"
    $passphraseFile = Join-Path $homeDir "passphrase"
    [IO.File]::WriteAllText($privateFile, $privateBase64, [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText($passphraseFile, $passphrase, [Text.Encoding]::ASCII)

    gh secret set GPG_PRIVATE_KEY --repo $repository --body-file $privateFile
    gh secret set GPG_PASSPHRASE --repo $repository --body-file $passphraseFile
    Write-Output "Published GPG key fingerprint: $fingerprint"
}
finally {
    Remove-Item -LiteralPath $homeDir -Recurse -Force -ErrorAction SilentlyContinue
}
