$ErrorActionPreference = "Stop"

$Repo = "https://github.com/L33kr/tg-ws-proxy-android.git"
$Ref = "94d0620aff9a9e0dd08a1f9688a904da09df497e"
$Root = Split-Path -Parent $PSScriptRoot
$Destination = Join-Path $Root "third_party/tg-ws-proxy-android"

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null

if (Test-Path (Join-Path $Destination ".git")) {
    git -C $Destination fetch --all --tags --prune
} else {
    if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
    git clone --no-checkout $Repo $Destination
}

git -C $Destination checkout --detach $Ref
Write-Host "TG WS dependency ready at $Destination"
Write-Host "Pinned commit: $Ref"
