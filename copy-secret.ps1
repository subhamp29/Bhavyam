# save as E:\music exe\LastWave-Native-main\copy-secret.ps1
#
# Reads one key from .env and copies ONLY its value to the clipboard.
# Never paste secrets into chat or commit them -- this keeps the long
# SIGNING_KEY base64 off screen and out of scrollback entirely.
#
#   .\copy-secret.ps1 -Key SIGNING_KEY      # then Ctrl+V into GitHub
#   .\copy-secret.ps1 -Key RELEASE_STORE_PASSWORD
#   .\copy-secret.ps1 -Key RELEASE_KEY_PASSWORD
#   .\copy-secret.ps1 -Key RELEASE_KEY_ALIAS

param(
    [Parameter(Mandatory)]
    [string]$Key
)

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$content  = Get-Content (Join-Path $repoRoot ".env") -Raw
$pattern  = "(?m)^" + [regex]::Escape($Key) + "=(.*)$"

if ($content -match $pattern) {
    $val = $matches[1].Trim()
    $len = $val.Length
    Set-Clipboard $val
    Write-Host "Copied $Key ($len chars) to clipboard."
} else {
    Write-Error "Key '$Key' not found in .env"
    exit 1
}