param(
  [string]$Src = 'C:\Users\Nicolas\Downloads\doodoo.png'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

Write-Host ("source: $Src")
$srcImg = [System.Drawing.Image]::FromFile($Src)
Write-Host ("source size: {0}x{1}" -f $srcImg.Width, $srcImg.Height)

# ---------------------------------------------------------------- helpers --
function New-SquareBitmap([System.Drawing.Image]$img, [int]$size) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
  $g.Clear([System.Drawing.Color]::Transparent)
  # preserve aspect ratio, centered on a transparent square
  $scale = [Math]::Min($size / $img.Width, $size / $img.Height)
  $w = [int][Math]::Round($img.Width * $scale)
  $h = [int][Math]::Round($img.Height * $scale)
  $x = [int][Math]::Round(($size - $w) / 2)
  $y = [int][Math]::Round(($size - $h) / 2)
  $g.DrawImage($img, $x, $y, $w, $h)
  $g.Dispose()
  return $bmp
}

function Get-PngBytes([System.Drawing.Bitmap]$bmp) {
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
  $bytes = $ms.ToArray()
  $ms.Dispose()
  return ,$bytes
}

function Write-Ico([string]$path, [int[]]$sizes, [System.Drawing.Image]$img) {
  $frames = @()
  foreach ($s in $sizes) {
    $bmp = New-SquareBitmap $img $s
    $frames += ,(Get-PngBytes $bmp)
    $bmp.Dispose()
  }
  # ICONDIR + entries + PNG payloads
  $count = $sizes.Count
  $headerSize = 6 + 16 * $count
  $offset = $headerSize
  $ms = New-Object System.IO.MemoryStream
  $bw = New-Object System.IO.BinaryWriter($ms)
  $bw.Write([UInt16]0)         # reserved
  $bw.Write([UInt16]1)         # type: icon
  $bw.Write([UInt16]$count)
  for ($i = 0; $i -lt $count; $i++) {
    $s = $sizes[$i]
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))  # width
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))  # height
    $bw.Write([Byte]0)         # palette
    $bw.Write([Byte]0)         # reserved
    $bw.Write([UInt16]1)       # planes
    $bw.Write([UInt16]32)      # bit count
    $bw.Write([UInt32]$frames[$i].Length)
    $bw.Write([UInt32]$offset)
    $offset += $frames[$i].Length
  }
  foreach ($f in $frames) { $bw.Write($f) }
  $bw.Flush()
  [IO.File]::WriteAllBytes($path, $ms.ToArray())
  $bw.Dispose(); $ms.Dispose()
  Write-Host ("  wrote $path  (" + (Get-Item $path).Length + " bytes, sizes: " + ($sizes -join ',') + ")")
}

# ------------------------------------------------------------------- outputs --
$targets = @(
  'D:\Trunk2012\Build\Installer\BootstrapperClient\Bootstrapper.ico',
  'D:\Trunk2012\Build\Installer\Bootstrapper\Bootstrapper.ico'
)
foreach ($t in $targets) {
  $dir = Split-Path -Parent $t
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
  if (Test-Path $t) {
    $bak = "$t.doodoo-ico-bak"
    if (-not (Test-Path $bak)) { Copy-Item $t $bak }
  }
}
$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
foreach ($t in $targets) { Write-Ico $t $icoSizes $srcImg }

# Android launcher densities
$androidOut = 'D:\DoodooAndroid\icons'
New-Item -ItemType Directory -Path $androidOut -Force | Out-Null
$densities = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192; 'playstore' = 512 }
foreach ($k in $densities.Keys) {
  $size = $densities[$k]
  $bmp = New-SquareBitmap $srcImg $size
  $out = Join-Path $androidOut ("ic_launcher-$k.png")
  $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host ("  wrote $out  ($size x $size)")
}

$srcImg.Dispose()
Write-Host 'done.'
