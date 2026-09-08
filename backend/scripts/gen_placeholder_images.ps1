# 生成 DSMarket 演示商品占位图（纯色底 + 商品名文字）
# 用法：powershell -ExecutionPolicy Bypass -File gen_placeholder_images.ps1
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path (Split-Path $PSScriptRoot -Parent) "uploads"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$items = @(
    @{ Name = "iphone16.jpg";  Text = "iPhone 16";          R = 50;  G = 120; B = 200 },
    @{ Name = "mate70.jpg";    Text = "HUAWEI Mate 70";     R = 200; G = 80;  B = 50 },
    @{ Name = "kb87.jpg";      Text = "Mechanical KB 87";   R = 90;  G = 150; B = 90 },
    @{ Name = "mi15.jpg";      Text = "Xiaomi 15";          R = 220; G = 150; B = 40 }
)

foreach ($it in $items) {
    $bmp = New-Object System.Drawing.Bitmap 600, 600
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb($it.R, $it.G, $it.B))
    $font = New-Object System.Drawing.Font("Arial", 36, [System.Drawing.FontStyle]::Bold)
    $brush = [System.Drawing.Brushes]::White
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $rect = New-Object System.Drawing.RectangleF(0, 0, 600, 600)
    $g.DrawString($it.Text, $font, $brush, $rect, $sf)
    $path = Join-Path $outDir $it.Name
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "generated: $path"
}
