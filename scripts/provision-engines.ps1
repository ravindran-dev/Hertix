$ErrorActionPreference = 'Stop'
$taskBin = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\src-tauri\binaries'))
New-Item -ItemType Directory -Force -Path $taskBin | Out-Null
$taskHeaders = @{ 'User-Agent' = 'YTHub-build'; 'Accept' = 'application/vnd.github+json' }
function Get-VerifiedAsset($taskAsset, $taskDestination) {
    Write-Host "Downloading $($taskAsset.name)"
    & curl.exe --fail --location --retry 3 --silent --show-error --output $taskDestination $taskAsset.browser_download_url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $($taskAsset.name)" }
    if ($taskAsset.digest -and $taskAsset.digest.StartsWith('sha256:')) {
        $taskStream = [System.IO.File]::OpenRead($taskDestination)
        try { $taskActual = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($taskStream)).Replace('-','').ToLowerInvariant() } finally { $taskStream.Dispose() }
        if ($taskActual -ne $taskAsset.digest.Substring(7)) { throw "SHA256 mismatch: $($taskAsset.name)" }
    } else { throw "GitHub did not supply an SHA256 digest for $($taskAsset.name). Refusing unverified asset." }
}
$taskYt = Invoke-RestMethod 'https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest' -Headers $taskHeaders
$taskYtAsset = $taskYt.assets | Where-Object name -eq 'yt-dlp.exe' | Select-Object -First 1
Get-VerifiedAsset $taskYtAsset (Join-Path $taskBin 'yt-dlp.exe')
$taskDeno = Invoke-RestMethod 'https://api.github.com/repos/denoland/deno/releases/latest' -Headers $taskHeaders
$taskDenoAsset = $taskDeno.assets | Where-Object name -eq 'deno-x86_64-pc-windows-msvc.zip' | Select-Object -First 1
$taskDenoZip = Join-Path $taskBin 'deno.zip'
Get-VerifiedAsset $taskDenoAsset $taskDenoZip
Expand-Archive -LiteralPath $taskDenoZip -DestinationPath $taskBin -Force
Remove-Item -LiteralPath $taskDenoZip -Force
$taskFf = Invoke-RestMethod 'https://api.github.com/repos/yt-dlp/FFmpeg-Builds/releases/latest' -Headers $taskHeaders
$taskFfAsset = $taskFf.assets | Where-Object name -eq 'ffmpeg-master-latest-win64-gpl.zip' | Select-Object -First 1
if (-not $taskFfAsset) { throw 'Windows FFmpeg archive was not found in the official yt-dlp FFmpeg builds.' }
$taskArchive = Join-Path $taskBin 'ffmpeg-download.zip'
Get-VerifiedAsset $taskFfAsset $taskArchive
$taskExtract = Join-Path $taskBin ('extract-' + [guid]::NewGuid().ToString())
Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskExtract
foreach ($taskName in @('ffmpeg.exe','ffprobe.exe')) {
    $taskFile = Get-ChildItem -LiteralPath $taskExtract -Recurse -Filter $taskName | Select-Object -First 1
    if (-not $taskFile) { throw "Missing $taskName in the archive" }
    Copy-Item -LiteralPath $taskFile.FullName -Destination (Join-Path $taskBin $taskName) -Force
}
# Keep third-party license texts beside the engines in installed resources.
Get-ChildItem -LiteralPath $taskExtract -Recurse -File | Where-Object { $_.Name -match '^(LICENSE|COPYING)' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskBin ('ffmpeg-' + $_.Name)) -Force }
Invoke-WebRequest 'https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/LICENSE' -OutFile (Join-Path $taskBin 'yt-dlp-LICENSE.txt') -UseBasicParsing
Invoke-WebRequest 'https://raw.githubusercontent.com/denoland/deno/main/LICENSE.md' -OutFile (Join-Path $taskBin 'deno-LICENSE.txt') -UseBasicParsing
# whisper.cpp prebuilt x64 CPU runtime; pin the release and independently verify its digest.
$taskWhisperArchive = Join-Path $taskBin 'whisper-download.zip'
& curl.exe --fail --location --retry 3 --silent --show-error --output $taskWhisperArchive 'https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-x64.zip'
if ($LASTEXITCODE -ne 0) { throw 'whisper.cpp download failed.' }
if ((Get-FileHash -LiteralPath $taskWhisperArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a') { throw 'whisper.cpp archive SHA256 mismatch.' }
$taskWhisperExtract = Join-Path $taskBin ('whisper-extract-' + [guid]::NewGuid().ToString())
Expand-Archive -LiteralPath $taskWhisperArchive -DestinationPath $taskWhisperExtract
$taskWhisperRelease = Join-Path $taskWhisperExtract 'Release'
foreach ($taskName in @('whisper-cli.exe','whisper.dll','ggml.dll','ggml-base.dll')) {
    $taskFile = Join-Path $taskWhisperRelease $taskName
    if (-not (Test-Path -LiteralPath $taskFile)) { throw "Missing whisper runtime file: $taskName" }
    Copy-Item -LiteralPath $taskFile -Destination (Join-Path $taskBin $taskName) -Force
}
Get-ChildItem -LiteralPath $taskWhisperRelease -Filter 'ggml-cpu-*.dll' -File | Copy-Item -Destination $taskBin -Force
Invoke-WebRequest 'https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.9.2/LICENSE' -OutFile (Join-Path $taskBin 'whisper-LICENSE.txt') -UseBasicParsing
$taskManifest = @{ ytDlp = @{ version = $taskYt.tag_name; url = $taskYtAsset.browser_download_url; digest = $taskYtAsset.digest }; ffmpeg = @{ version = $taskFf.tag_name; url = $taskFfAsset.browser_download_url; digest = $taskFfAsset.digest }; deno = @{ version = $taskDeno.tag_name; url = $taskDenoAsset.browser_download_url; digest = $taskDenoAsset.digest }; whisperCli = @{ version = 'v1.9.2'; url = 'https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-x64.zip'; digest = 'sha256:49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a' }; provisionedAt = [DateTime]::UtcNow.ToString('o') }
$taskManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskBin 'manifest.json') -Encoding UTF8
$taskResolvedExtract = [System.IO.Path]::GetFullPath($taskExtract)
if (-not $taskResolvedExtract.StartsWith($taskBin + [System.IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe extraction cleanup path' }
Remove-Item -LiteralPath $taskResolvedExtract -Recurse -Force
Remove-Item -LiteralPath $taskArchive -Force
Remove-Item -LiteralPath ([System.IO.Path]::GetFullPath($taskWhisperExtract)) -Recurse -Force
Remove-Item -LiteralPath $taskWhisperArchive -Force
Write-Host 'yt-dlp, FFmpeg, FFprobe, whisper.cpp and the YouTube JavaScript runtime are provisioned and verified.'
