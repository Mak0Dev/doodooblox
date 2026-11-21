# Swaps the roblox.com legacy host names in a player executable for the
# deployment domain. "roblox.com" -> "karblox.cc" is a same-length swap, so it
# is done as raw byte replacement in both ANSI and UTF-16LE encodings.
#
# karblox.cc (the registrable domain) is used rather than the 21-character
# public host doodooblox.karblox.cc: the client's Http trust checks compare
# subdomains, and the public host is a subdomain of karblox.cc. The literal
# fallback URLs therefore become www.karblox.cc / assetgame.karblox.cc; normal
# joins use the doodooblox.karblox.cc URLs supplied in the signed join script.
#
# The UTF-16LE pass skips the Authenticode certificate blob (the exe's embedded
# code-signing description), which sits below the 15 MB mark; everything after
# it is ordinary application data.
param(
    [string]$Exe = 'D:\Trunk2012\Build\UploadBits\Win32-Release-RobloxPlayer\RobloxPlayerBeta.exe',
    [string]$BackupSuffix = '.roblox-domain-cc-bak'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Exe)) { throw "not found: $Exe" }

$backup = $Exe + $BackupSuffix
if (-not (Test-Path $backup)) {
    Copy-Item $Exe $backup
    Write-Host ("backup written: " + $backup)
} else {
    Write-Host ("backup already present: " + $backup)
}

$bytes = [IO.File]::ReadAllBytes($Exe)
$enc = [Text.Encoding]::GetEncoding(28591)

# ------------------------------------------------------------------ ASCII --
$pat = 'roblox.com'
$rep = 'karblox.cc'          # 10 chars each
if ($pat.Length -ne $rep.Length) { throw 'replacement must be same length' }
$repBytes = [Text.Encoding]::ASCII.GetBytes($rep)

$ascii = $enc.GetString($bytes)
$asciiHits = 0
$idx = 0
while (($idx = $ascii.IndexOf($pat, $idx, [StringComparison]::Ordinal)) -ge 0) {
    for ($i = 0; $i -lt $repBytes.Length; $i++) { $bytes[$idx + $i] = $repBytes[$i] }
    $asciiHits++
    $idx += $pat.Length
}
Write-Host ("ascii hits patched: $asciiHits")

# --------------------------------------------------------------- UTF-16LE --
$upat = [Text.Encoding]::Unicode.GetBytes('www.roblox.com')     # 28 bytes
$urep = [Text.Encoding]::Unicode.GetBytes('www.karblox.cc')     # 28 bytes
$uni = [Text.Encoding]::Unicode.GetString($bytes)
$matches = [regex]::Matches($uni, 'www\.roblox\.com')
Write-Host ("utf16 candidate hits: " + $matches.Count)
$uniHits = 0
foreach ($m in $matches) {
    $off = $m.Index * 2
    if ($off -lt 15000000) {
        Write-Host ("  skip @$off (certificate blob)")
        continue
    }
    for ($i = 0; $i -lt $urep.Length; $i++) { $bytes[$off + $i] = $urep[$i] }
    $uniHits++
    Write-Host ("  patched @$off")
}
Write-Host ("utf16 hits patched: $uniHits")

[IO.File]::WriteAllBytes($Exe, $bytes)

# ------------------------------------------------------------- self-check --
$check = [IO.File]::ReadAllBytes($Exe)
$checkAscii = $enc.GetString($check)
$leftA = ([regex]::Matches($checkAscii, 'roblox\.com')).Count
$leftU = ([regex]::Matches([Text.Encoding]::Unicode.GetString($check), 'roblox\.com')).Count
$newA = ([regex]::Matches($checkAscii, 'karblox\.cc')).Count
Write-Host ("after: ascii roblox.com=$leftA utf16 roblox.com=$leftU ascii karblox.cc=$newA")
Write-Host ("md5: " + (Get-FileHash $Exe -Algorithm MD5).Hash)
