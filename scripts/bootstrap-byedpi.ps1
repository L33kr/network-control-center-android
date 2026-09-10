$ErrorActionPreference = "Stop"

$ByeDpiRepo = "https://github.com/hufrea/byedpi.git"
$ByeDpiRef = "ba532298de7b28cfe854aea83d061369d13ca290"
$HevRepo = "https://github.com/heiher/hev-socks5-tunnel.git"
$HevRef = "941c758101385d145c66210ac88991daaf27d4b6"
$Root = Split-Path -Parent $PSScriptRoot
$ThirdParty = Join-Path $Root "third_party"

function Checkout-Repo([string]$Repo, [string]$Ref, [string]$Destination, [bool]$Recursive = $false) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    if (Test-Path (Join-Path $Destination ".git")) {
        git -C $Destination fetch --all --tags --prune
    } else {
        if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
        git clone --no-checkout $Repo $Destination
    }
    git -C $Destination checkout --detach $Ref
    if ($Recursive) {
        git -C $Destination submodule update --init --recursive
    }
}

Checkout-Repo $ByeDpiRepo $ByeDpiRef (Join-Path $ThirdParty "byedpi")
Checkout-Repo $HevRepo $HevRef (Join-Path $ThirdParty "hev-socks5-tunnel") $true

Write-Host "ByeDPI: $ByeDpiRef"
Write-Host "hev-socks5-tunnel: $HevRef"
