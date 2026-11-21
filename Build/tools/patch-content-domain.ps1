# Rewrites the legacy deployment host names in content/settings files and
# (optionally) in binaries, in place.
#
# Text files:
#   roblox.com        -> karblox.cc             (same length)
#   www.better16.xyz  -> doodooblox.karblox.cc
#   better16.xyz      -> doodooblox.karblox.cc
#   www.karblox.ru    -> doodooblox.karblox.cc
#   karblox.ru        -> doodooblox.karblox.cc
#
# Binaries keep the same byte length, so the registrable domain is used:
#   roblox.com        -> karblox.cc
#   www.better16.xyz  -> www.karblox.cc
#   better16.xyz      -> karblox.cc
#   www.karblox.ru    -> www.karblox.cc
#   karblox.ru        -> karblox.cc
param(
    [string[]]$Roots = @(),
    [string[]]$BinaryRoots = @(),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$textExts = @('.lua','.txt','.json','.xml','.rbxmx','.js','.cfg','.ini','.html','.css','.md','.xsd','.csv','.yml','.yaml','.config')
$binExts  = @('.dll','.exe','.so')

$enc = [Text.Encoding]::GetEncoding(28591)
$encAscii = [Text.Encoding]::ASCII

function Swap-Bytes([byte[]]$bytes, [bool]$allowBetter16) {
    $s = $enc.GetString($bytes)
    $orig = $s
    $s = $s.Replace('roblox.com', 'karblox.cc')
    if ($allowBetter16) {
        $s = $s.Replace('www.better16.xyz', 'doodooblox.karblox.cc')
        $s = $s.Replace('better16.xyz', 'doodooblox.karblox.cc')
        $s = $s.Replace('www.karblox.ru', 'doodooblox.karblox.cc')
        $s = $s.Replace('karblox.ru', 'doodooblox.karblox.cc')
    } else {
        $s = $s.Replace('www.better16.xyz', 'www.karblox.cc')
        $s = $s.Replace('better16.xyz', 'karblox.cc')
        $s = $s.Replace('www.karblox.ru', 'www.karblox.cc')
        $s = $s.Replace('karblox.ru', 'karblox.cc')
    }
    if ($s -eq $orig) { return $null }
    return $enc.GetBytes($s)
}

$totalFiles = 0
$totalSwaps = 0

foreach ($root in $Roots) {
    if (-not (Test-Path $root)) { Write-Host ("root missing: $root"); continue }
    Write-Host ("=== text: $root ===")
    Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $textExts -contains $_.Extension.ToLower() -and $_.Name -notmatch '(?i)\.(bak|karblox-bak|roblox-domain-bak)$'
    } | ForEach-Object {
        $b = [IO.File]::ReadAllBytes($_.FullName)
        $new = Swap-Bytes $b $true
        if ($null -ne $new) {
            $count = ([regex]::Matches($enc.GetString($b), 'roblox\.com|better16|karblox\.ru')).Count
            $totalFiles++
            $totalSwaps += $count
            Write-Host ('  ' + $_.FullName + '   (' + $count + ' hits)')
            if (-not $DryRun) {
                if ($_.IsReadOnly) { $_.IsReadOnly = $false }
                [IO.File]::WriteAllBytes($_.FullName, $new)
            }
        }
    }
}

foreach ($root in $BinaryRoots) {
    if (-not (Test-Path $root)) { Write-Host ("root missing: $root"); continue }
    Write-Host ("=== binaries: $root ===")
    Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $binExts -contains $_.Extension.ToLower() -and $_.Name -notmatch '(?i)\.(bak|roblox-domain-bak)$'
    } | ForEach-Object {
        $b = [IO.File]::ReadAllBytes($_.FullName)
        $new = Swap-Bytes $b $false
        if ($null -ne $new) {
            $count = ([regex]::Matches($enc.GetString($b), 'roblox\.com')).Count
            $totalFiles++
            $totalSwaps += $count
            Write-Host ('  ' + $_.FullName + '   (' + $count + ' hits)')
            if (-not $DryRun) {
                if ($_.IsReadOnly) { $_.IsReadOnly = $false }
                [IO.File]::WriteAllBytes($_.FullName, $new)
            }
        }
    }
}

Write-Host ("done: files changed=$totalFiles  replacements=$totalSwaps  dryRun=$DryRun")
