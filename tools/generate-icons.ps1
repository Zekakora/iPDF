# Regenerates the iPDF launcher icon from the brand artwork in the repository root.
#
# The app uses a plain (legacy) launcher icon: no adaptive-icon layers, so the bitmap is shown
# as-is instead of being stretched over the whole adaptive canvas.
#
# The artwork is used whole: the square logo.jpg (margins included) is drawn unchanged, only the
# corners of the white plate are rounded. Nothing is cropped, keyed out or zoomed in.
#
# Usage (from the repository root):
#   powershell -ExecutionPolicy Bypass -File tools\generate-icons.ps1
#   powershell -ExecutionPolicy Bypass -File tools\generate-icons.ps1 -Source path\to\logo.png
#
# Re-run it whenever the logo is replaced.

[CmdletBinding()]
param(
    [string]$Source,
    [string]$ResDir
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $ResDir) { $ResDir = Join-Path $repoRoot "app\src\main\res" }

if (-not $Source) {
    $png = Join-Path $repoRoot "logo.png"
    $jpg = Join-Path $repoRoot "logo.jpg"
    $Source = if (Test-Path $png) { $png } elseif (Test-Path $jpg) { $jpg } else { throw "No logo.png or logo.jpg in $repoRoot" }
}

$sourcePath = (Resolve-Path $Source).Path
Write-Host "Source artwork: $sourcePath"

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public static class IPdfIconGen
{
    // Pad a non-square file onto a white square instead of cutting anything off.
    public static Bitmap LoadSquare(string path)
    {
        using (var file = new Bitmap(path))
        {
            int side = Math.Max(file.Width, file.Height);
            var canvas = new Bitmap(side, side, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(canvas))
            {
                g.Clear(Color.White);
                g.CompositingMode = CompositingMode.SourceCopy;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.DrawImage(file, new Rectangle((side - file.Width) / 2, (side - file.Height) / 2, file.Width, file.Height));
            }
            return canvas;
        }
    }

    private static GraphicsPath RoundedRect(RectangleF r, float radius)
    {
        float d = radius * 2f;
        var path = new GraphicsPath();
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static void Render(Bitmap logo, int size, string output)
    {
        using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
        {
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.CompositingQuality = CompositingQuality.HighQuality;
                // The launcher normalises a legacy icon to its own cell size, so the plate always
                // fills the cell; the artwork is drawn a little smaller inside it to leave the
                // breathing room the user asked for. The whole image is still used, never cropped.
                float plateSize = size - 1f;
                using (var plate = RoundedRect(new RectangleF(0.5f, 0.5f, plateSize, plateSize), size * 0.225f))
                using (var white = new SolidBrush(Color.White))
                {
                    g.FillPath(white, plate);
                    g.SetClip(plate);
                    float art = size * ART_SCALE;
                    float offset = (size - art) / 2f;
                    g.DrawImage(logo, new RectangleF(offset, offset, art, art));
                }
            }
            bmp.Save(output, ImageFormat.Png);
        }
    }

    private const float ART_SCALE = 0.8f;
}
'@

$logo = [IPdfIconGen]::LoadSquare($sourcePath)
Write-Host ("Artwork: {0}x{1} (used whole, no crop)" -f $logo.Width, $logo.Height)

$densities = @(
    @{ Dir = "mipmap-mdpi";    Size = 48 },
    @{ Dir = "mipmap-hdpi";    Size = 72 },
    @{ Dir = "mipmap-xhdpi";   Size = 96 },
    @{ Dir = "mipmap-xxhdpi";  Size = 144 },
    @{ Dir = "mipmap-xxxhdpi"; Size = 192 }
)

foreach ($density in $densities) {
    $dir = Join-Path $ResDir $density.Dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $launcher = Join-Path $dir "ic_launcher.png"
    [IPdfIconGen]::Render($logo, $density.Size, $launcher)
    Write-Host ("  {0}  ({1}px)" -f $launcher, $density.Size)
}

$logo.Dispose()
Write-Host "Done. Rebuild the app to pick up the new launcher icon."
