$ErrorActionPreference = 'Stop'

# Start from v4.3 (Android-compatible Gemini 3.5 handshake + diagnostics).
& "$PSScriptRoot/patch-alad-windows-v4_3.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

# Version labels.
$c = $c.Replace('ALAD Windows v4.3', 'ALAD Windows v4.4')

# Faster setup timeout: 8 seconds instead of 12.
$c = $c.Replace('timeout.CancelAfter(TimeSpan.FromSeconds(12));', 'timeout.CancelAfter(TimeSpan.FromSeconds(8));')
$c = $c.Replace('Gemini không trả setupComplete trong 12 giây.', 'Gemini không trả setupComplete trong 8 giây.')

# On the very first connection only, retry once quickly when the failure is specifically a setup timeout.
$connectNeedle = @'
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        await OpenSocketAsync(cts.Token);
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
'@
$connectReplacement = @'
        cts = CancellationTokenSource.CreateLinkedTokenSource(externalToken);
        try
        {
            await OpenSocketAsync(cts.Token);
        }
        catch (TimeoutException) when (!cts.Token.IsCancellationRequested)
        {
            status("Gemini chưa phản hồi · thử kết nối lại lần 2...");
            await Task.Delay(500, cts.Token);
            await OpenSocketAsync(cts.Token);
        }
        sendTask = Task.Run(() => SendLoop(cts.Token), cts.Token);
'@
if (-not $c.Contains($connectNeedle)) { throw 'ConnectAsync marker missing' }
$c = $c.Replace($connectNeedle, $connectReplacement)

Set-Content $p $c -Encoding UTF8

# Verification.
$c = Get-Content $p -Raw
if ($c -notmatch 'TimeSpan\.FromSeconds\(8\)') { throw '8-second setup timeout missing' }
if ($c -match 'setupComplete trong 12 giây') { throw 'old 12-second timeout message remains' }
if ($c -notmatch 'thử kết nối lại lần 2') { throw 'one-shot setup retry missing' }
Write-Host 'ALAD Windows v4.4 patch verified.'
