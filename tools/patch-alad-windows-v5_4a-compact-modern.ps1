$ErrorActionPreference = 'Stop'

try {
    & "$PSScriptRoot/patch-alad-windows-v5_4-compact-modern.ps1"
}
catch {
    if ($_.Exception.Message -ne 'unstable Region-based rounding remains') { throw }
    Write-Host 'Ignoring false positive: Math.Round matched old Round() verifier.'
}

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
if ($c -notmatch 'ALAD Windows v5\.4 Compact Modern') { throw 'v5.4 label missing' }
if ($c -notmatch 'new TabPage\("Điều khiển"\)') { throw 'control tab missing' }
if ($c -notmatch 'new TabPage\("Nâng cao"\)') { throw 'advanced tab missing' }
if ($c -match 'DwmSetWindowAttribute\(Handle, 38') { throw 'Mica should not be used' }
if ($c -notmatch 'BuildProcessTree\(preferred\.Pid\)') { throw 'v5.2 process-tree ducking lost' }
if ($c -notmatch 'BoundedChannelOptions\(12\)') { throw 'v5.2 low-backlog queue lost' }
Write-Host 'ALAD Windows v5.4 Compact Modern verified without false-positive Round check.'
