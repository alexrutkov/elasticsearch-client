param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $false)]
    [string]$OutputPath,

    [Parameter(Mandatory = $false)]
    [int]$CanvasWidth = 1200,

    [Parameter(Mandatory = $false)]
    [int]$CanvasHeight = 760
)

# Creates a Marketplace screenshot canvas without stretching the source image.
# The source is scaled proportionally to fit the canvas height, then centered.
# Usage:
#   .\make-1200x760.ps1 -InputPath .\connection.png
#   .\make-1200x760.ps1 -InputPath .\connection.png -OutputPath .\connection-1200x760.png

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$inputFile = (Resolve-Path $InputPath).Path
if (-not $OutputPath) {
    $dir = Split-Path $inputFile
    $name = [System.IO.Path]::GetFileNameWithoutExtension($inputFile)
    $OutputPath = Join-Path $dir "$name-${CanvasWidth}x${CanvasHeight}.png"
}

$img = [System.Drawing.Image]::FromFile($inputFile)
try {
    $canvas = New-Object System.Drawing.Bitmap $CanvasWidth, $CanvasHeight
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

        $rect = New-Object System.Drawing.Rectangle 0, 0, $CanvasWidth, $CanvasHeight
        $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect,
            ([System.Drawing.Color]::FromArgb(30, 31, 34)),
            ([System.Drawing.Color]::FromArgb(43, 45, 48)),
            45.0
        try { $g.FillRectangle($bg, $rect) } finally { $bg.Dispose() }

        # Leave a 30 px margin at the top and bottom.
        $targetHeight = [Math]::Min($CanvasHeight - 60, [int]($img.Height * ($CanvasWidth - 80) / $img.Width))
        $targetWidth = [int][Math]::Round($img.Width * $targetHeight / $img.Height)
        $x = [int](($CanvasWidth - $targetWidth) / 2)
        $y = [int](($CanvasHeight - $targetHeight) / 2)

        $shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(95, 0, 0, 0))
        try {
            $g.FillRectangle($shadow, $x + 12, $y + 12, $targetWidth, $targetHeight)
            $g.FillRectangle($shadow, $x + 6, $y + 6, $targetWidth, $targetHeight)
        } finally { $shadow.Dispose() }

        $dest = New-Object System.Drawing.Rectangle $x, $y, $targetWidth, $targetHeight
        $g.DrawImage($img, $dest)

        $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(90, 255, 255, 255)), 1
        try { $g.DrawRectangle($pen, $x, $y, $targetWidth - 1, $targetHeight - 1) } finally { $pen.Dispose() }

        $canvas.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $g.Dispose()
        $canvas.Dispose()
    }
} finally {
    $img.Dispose()
}

$result = [System.Drawing.Image]::FromFile($OutputPath)
try {
    Write-Host "Created: $OutputPath"
    Write-Host "Size: $($result.Width)x$($result.Height)"
} finally {
    $result.Dispose()
}
