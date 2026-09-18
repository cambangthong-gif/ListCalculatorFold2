$ErrorActionPreference = 'Stop'

& "$PSScriptRoot/patch-alad-windows-v6_0-hybrid.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw

$c = $c.Replace('ALAD Windows v6.0 Hybrid Dubbing', 'ALAD Windows v6.1 Universal Browser')
$c = $c.Replace('Hybrid YouTube / Browser — phụ đề + sync trình phát', 'Hybrid Browser — phụ đề + sync + Live fallback')
$c = $c.Replace('Hybrid: chờ YouTube / phụ đề', 'Hybrid: chờ trình phát / phụ đề')
$c = $c.Replace('Hybrid: video mới', 'Hybrid: nội dung mới')

Set-Content $p $c -Encoding UTF8

$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v6\.1 Universal Browser') { throw 'v6.1 label missing' }
if ($c -notmatch 'Hybrid Browser — phụ đề \+ sync \+ Live fallback') { throw 'universal source label missing' }
if ($c -notmatch 'Live audio fallback') { throw 'live fallback lost' }
if ($c -notmatch 'BrowserBridge\(37921') { throw 'browser bridge lost' }
if ($c -notmatch 'contextWindowCompression') { throw 'long session compression lost' }

Write-Host 'ALAD Windows v6.1 Universal Browser patch verified.'
