$ErrorActionPreference = 'Stop'

# Start from the proven v4.2 UI/audio-volume patch.
& "$PSScriptRoot/patch-alad-windows-v4_2.ps1"

$p = 'alad-windows-v2/Program.cs'
$c = Get-Content $p -Raw
$nl = [Environment]::NewLine

# Version labels.
$c = $c.Replace('ALAD Windows v4.2', 'ALAD Windows v4.3')

# Gemini 3.5: match the working Android ALAD handshake as closely as possible.
# Do not request transcription fields during setup; keep only AUDIO + translationConfig + session resumption.
$fast = @'
        if (mode == LiveMode.FastTranslate)
        {
            setup = new
            {
                model = "models/gemini-3.5-live-translate-preview",
                generationConfig = new
                {
                    responseModalities = new[] { "AUDIO" },
                    translationConfig = new
                    {
                        targetLanguageCode = targetLang.Split('-')[0],
                        echoTargetLanguage = true
                    }
                },
                sessionResumption = new { handle = sessionHandle }
            };
        }
        else
'@
$fastPattern = '(?s)        if \(mode == LiveMode\.FastTranslate\)\s*\{\s*setup = new\s*\{.*?\r?\n            \};\r?\n        \}\r?\n        else'
$c2 = [regex]::Replace($c, $fastPattern, $fast.TrimEnd(), 1)
if ($c2 -eq $c) { throw 'Gemini 3.5 Android-style setup replacement failed' }
$c = $c2

# Gemini 3.5 official guide recommends ~100 ms PCM chunks. 3.8 can continue with 40 ms.
$c = $c.Replace('    private readonly PcmChunker chunker = new(40);', '    private readonly PcmChunker chunker = new(40);')
$c = $c.Replace('            chunker.Reset();', '            chunker.SetChunkMs(SelectedLiveMode() == LiveMode.FastTranslate ? 100 : 40);' + $nl + '            chunker.Reset();')
$c = $c.Replace('    private readonly int chunkBytes;', '    private int chunkBytes;')
$ctorNeedle = @'
    public PcmChunker(int chunkMs)
    {
        chunkBytes = 16000 * 2 * chunkMs / 1000;
        pending = new List<byte>(chunkBytes * 3);
    }
'@
$ctorReplacement = @'
    public PcmChunker(int chunkMs)
    {
        chunkBytes = 16000 * 2 * chunkMs / 1000;
        pending = new List<byte>(chunkBytes * 3);
    }

    public void SetChunkMs(int chunkMs)
    {
        chunkBytes = 16000 * 2 * Math.Clamp(chunkMs, 20, 500) / 1000;
        pending.Clear();
    }
'@
if (-not $c.Contains($ctorNeedle)) { throw 'PcmChunker constructor marker missing' }
$c = $c.Replace($ctorNeedle, $ctorReplacement)

# Do not surface a generic TaskCanceledException when setupComplete never arrives.
$waitNeedle = @'
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(25));
        await setupReady.Task.WaitAsync(timeout.Token);
        status("Gemini sẵn sàng · continuous stream");
'@
$waitReplacement = @'
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(12));
        try
        {
            await setupReady.Task.WaitAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new TimeoutException("Gemini không trả setupComplete trong 12 giây. Nếu 3.8 vào được nhưng 3.5 không vào, hãy thử lại hoặc kiểm tra quyền/trạng thái model 3.5 Live Translate của API key.");
        }
        status("Gemini sẵn sàng · continuous stream");
'@
if (-not $c.Contains($waitNeedle)) { throw 'OpenSocket wait marker missing' }
$c = $c.Replace($waitNeedle, $waitReplacement)

# If the server sends an error during setup, fail the pending setup immediately with the actual message.
$errorNeedle = @'
            if (root.TryGetProperty("error", out var error))
            {
                status("Gemini API: " + error.ToString());
                return;
            }
'@
$errorReplacement = @'
            if (root.TryGetProperty("error", out var error))
            {
                string message = error.TryGetProperty("message", out var m) ? (m.GetString() ?? error.ToString()) : error.ToString();
                status("Gemini API: " + message);
                setupReady?.TrySetException(new InvalidOperationException("Gemini API: " + message));
                return;
            }
'@
if (-not $c.Contains($errorNeedle)) { throw 'Server error marker missing' }
$c = $c.Replace($errorNeedle, $errorReplacement)

# Capture WebSocket close status/reason during handshake instead of silently waiting for timeout.
$closeLine = '                if (result.MessageType == WebSocketMessageType.Close) break;'
$closeBlock = @'
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    string reason = $"{result.CloseStatus} {result.CloseStatusDescription}".Trim();
                    setupReady?.TrySetException(new InvalidOperationException("Gemini đóng kết nối trước setupComplete: " + reason));
                    if (!stopping) status("Gemini đóng kết nối: " + reason);
                    break;
                }
'@
if (-not $c.Contains($closeLine)) { throw 'WebSocket close marker missing' }
$c = $c.Replace($closeLine, $closeBlock.TrimEnd())

$catchNeedle = @'
        catch (Exception ex)
        {
            if (!stopping) status("Gemini mất kết nối: " + ex.Message);
        }
'@
$catchReplacement = @'
        catch (Exception ex)
        {
            setupReady?.TrySetException(ex);
            if (!stopping) status("Gemini mất kết nối: " + ex.Message);
        }
'@
if (-not $c.Contains($catchNeedle)) { throw 'ReceiveLoop catch marker missing' }
$c = $c.Replace($catchNeedle, $catchReplacement)

Set-Content $p $c -Encoding UTF8

# Verification.
$c = Get-Content $p -Raw
if ($c -match 'inputAudioTranscription = new \{ \},\s*\r?\n\s*outputAudioTranscription = new \{ \},\s*\r?\n\s*translationConfig' -and $c -match 'gemini-3\.5-live-translate-preview') { throw '3.5 setup still contains transcription fields' }
if ($c -notmatch 'sessionResumption = new \{ handle = sessionHandle \}') { throw '3.5 session resumption missing' }
if ($c -notmatch 'SetChunkMs\(SelectedLiveMode\(\) == LiveMode\.FastTranslate \? 100 : 40\)') { throw 'dynamic chunk size missing' }
if ($c -notmatch 'Gemini đóng kết nối trước setupComplete') { throw 'close diagnostics missing' }
if ($c -notmatch 'Gemini API:') { throw 'server error diagnostics missing' }
Write-Host 'ALAD Windows v4.3 patch verified.'
