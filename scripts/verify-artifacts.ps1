$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskRelease = Join-Path $taskRoot 'src-tauri\target\release\ythub.exe'
$taskPortable = Join-Path $taskRoot 'artifacts\YTHub-portable\YTHub.exe'
$taskZip = Join-Path $taskRoot 'artifacts\YTHub-portable.zip'
$taskInstaller = Join-Path $taskRoot 'artifacts\YTHub_1.0.0_x64-setup.exe'
$taskBinaryHash = (Get-FileHash -LiteralPath $taskRelease -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $taskPortable -Algorithm SHA256).Hash -ne $taskBinaryHash) { throw 'Portable executable differs from the final release executable.' }
$taskLatestSource = Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src'),(Join-Path $taskRoot 'src-tauri\src') -File -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ((Get-Item -LiteralPath $taskRelease).LastWriteTime -lt $taskLatestSource.LastWriteTime) { throw 'A source file is newer than the release executable. Rebuild before packaging.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [System.IO.Compression.ZipFile]::OpenRead($taskZip)
try {
    foreach ($taskName in @('YTHub.exe','binaries/yt-dlp.exe','binaries/ffmpeg.exe','binaries/ffprobe.exe','binaries/deno.exe','binaries/whisper-cli.exe','binaries/whisper.dll','binaries/ggml.dll','binaries/ggml-base.dll','binaries/whisper-LICENSE.txt','binaries/manifest.json','LICENSE','THIRD_PARTY_NOTICES.md','README.md')) {
        $taskEntry = $taskArchive.Entries | Where-Object { $_.FullName.Replace('\','/') -eq $taskName } | Select-Object -First 1
        if ($null -eq $taskEntry -or $taskEntry.Length -eq 0) { throw "Missing or empty ZIP resource: $taskName" }
        if ($taskName.EndsWith('.exe')) {
            $taskSource = if ($taskName -eq 'YTHub.exe') { $taskRelease } else { Join-Path $taskRoot ('src-tauri/' + $taskName) }
            $taskStream = $taskEntry.Open()
            $taskHasher = [System.Security.Cryptography.SHA256]::Create()
            try { $taskEntryHash = [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-','') } finally { $taskStream.Dispose(); $taskHasher.Dispose() }
            if ($taskEntryHash -ne (Get-FileHash -LiteralPath $taskSource -Algorithm SHA256).Hash) { throw "ZIP resource hash mismatch: $taskName" }
        }
    }
} finally { $taskArchive.Dispose() }
$taskSummary = @($taskInstaller,$taskZip,$taskPortable) | ForEach-Object {
    $taskFile = Get-Item -LiteralPath $_
    [PSCustomObject]@{ name=$taskFile.Name; bytes=$taskFile.Length; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
}
$taskSummary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot 'artifacts\checksums.json') -Encoding utf8
$taskSummary | Format-Table -AutoSize
Write-Host 'Verified source freshness, portable executable, ZIP resources, engine hashes and artifact checksums.'
