$ErrorActionPreference = 'Stop'
$root = 'C:\Users\user\Desktop\mpsync-main'

function Invoke-IdleBuild {
  param([string]$Project, [string]$JavaHome, [string]$Task = 'build', [int]$IdleSec = 120)
  $dir = Join-Path $root $Project
  $log = Join-Path $root ("build-{0}.log" -f $Project)
  Remove-Item $log -Force -EA SilentlyContinue
  $env:JAVA_HOME = $JavaHome
  $env:GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8'
  $psi = New-Object Diagnostics.ProcessStartInfo
  $psi.FileName = Join-Path $dir 'gradlew.bat'
  $psi.Arguments = "$Task --console=plain --no-daemon"
  $psi.WorkingDirectory = $dir
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.CreateNoWindow = $true
  $psi.Environment['JAVA_HOME'] = $JavaHome
  $psi.Environment['GRADLE_OPTS'] = $env:GRADLE_OPTS
  $p = [Diagnostics.Process]::Start($psi)
  $last = [datetime]::UtcNow
  $outJob = Start-Job -ScriptBlock {
    param($sr,$lp)
    while (($l = $sr.ReadLine()) -ne $null) {
      Add-Content -Path $lp -Value $l -Encoding UTF8
      $l
    }
  } -ArgumentList $p.StandardOutput, $log
  $errJob = Start-Job -ScriptBlock {
    param($sr,$lp)
    while (($l = $sr.ReadLine()) -ne $null) {
      Add-Content -Path $lp -Value $l -Encoding UTF8
      $l
    }
  } -ArgumentList $p.StandardError, $log
  $lastSize = 0
  while (-not $p.HasExited) {
    Start-Sleep -Seconds 2
    Receive-Job $outJob -EA SilentlyContinue | ForEach-Object { Write-Host $_; $last = [datetime]::UtcNow }
    Receive-Job $errJob -EA SilentlyContinue | ForEach-Object { Write-Host $_; $last = [datetime]::UtcNow }
    $size = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
    if ($size -gt $lastSize) { $lastSize = $size; $last = [datetime]::UtcNow }
    if (([datetime]::UtcNow - $last).TotalSeconds -ge $IdleSec) {
      Write-Host "TIMEOUT idle ${IdleSec}s for $Project"
      try { $p.Kill($true) } catch { $p.Kill() }
      break
    }
  }
  Receive-Job $outJob -Wait -EA SilentlyContinue | ForEach-Object { Write-Host $_ }
  Receive-Job $errJob -Wait -EA SilentlyContinue | ForEach-Object { Write-Host $_ }
  Remove-Job $outJob, $errJob -Force -EA SilentlyContinue
  Write-Host "EXIT $Project = $($p.ExitCode)"
  return $p.ExitCode
}

# Rewrite 26.2 version json library URLs to local mirror
$src = Join-Path $root 'mirror\versions\26.2.json'
$dst = Join-Path $root 'mirror\versions\26.2.local.json'
$raw = Get-Content $src -Raw
$raw2 = $raw -replace 'https://libraries\.minecraft\.net/', 'http://127.0.0.1:18765/maven/'
$raw2 = $raw2 -replace 'https://piston-meta\.mojang\.com/', 'http://127.0.0.1:18765/'
$raw2 = $raw2 -replace 'https://launchermeta\.mojang\.com/', 'http://127.0.0.1:18765/'
# strip BOM-sensitive path: write via .NET
[IO.File]::WriteAllText($dst, $raw2)
Write-Host "rewrote $dst len=$($raw2.Length)"

$vjCache = 'C:\Users\user\.gradle\caches\minecraftforge\forgegradle\mavenizer\caches'
New-Item $vjCache -ItemType Directory -Force | Out-Null

# Seed launcher manifest with local version url
$mf = @{
  latest = @{ release = '26.2'; snapshot = '26.2' }
  versions = @(
    @{ id='26.2'; type='release'; url='http://127.0.0.1:18765/versions/26.2.local.json'; time='2026-06-16T10:38:18+00:00'; releaseTime='2026-06-16T10:38:18+00:00' }
    @{ id='1.21.11'; type='release'; url='http://127.0.0.1:18765/versions/1.21.11.json'; time='2025-12-23T12:42:41+00:00'; releaseTime='2025-12-23T12:42:41+00:00' }
    @{ id='1.21.1'; type='release'; url='http://127.0.0.1:18765/versions/1.21.1.json'; time='2024-08-08T12:21:46+00:00'; releaseTime='2024-08-08T12:21:46+00:00' }
    @{ id='1.20.1'; type='release'; url='http://127.0.0.1:18765/versions/1.20.1.json'; time='2023-06-12T08:44:12+00:00'; releaseTime='2023-06-12T08:44:12+00:00' }
  )
} | ConvertTo-Json -Depth 6
Set-Content (Join-Path $vjCache 'launcher_manifest.json') -Value $mf -Encoding UTF8
Set-Content (Join-Path $root 'mirror\version_manifest_local.json') -Value $mf -Encoding UTF8

Write-Host 'Building 26.2forge...'
$code26 = Invoke-IdleBuild -Project '26.2forge' -JavaHome 'C:\Program Files\Zulu\zulu-25'
Write-Host "26.2forge result=$code26"

if ($code26 -eq 0) {
  Write-Host 'Building 1.21.11forge...'
  $code11 = Invoke-IdleBuild -Project '1.21.11forge' -JavaHome 'D:\jdk-21\zulu21.44.17-ca-jdk21.0.8-win_x64'
  Write-Host "1.21.11forge result=$code11"
} else {
  Write-Host 'Skip 1.21.11forge until 26.2forge succeeds; still attempting 1.21.11forge...'
  $code11 = Invoke-IdleBuild -Project '1.21.11forge' -JavaHome 'D:\jdk-21\zulu21.44.17-ca-jdk21.0.8-win_x64'
  Write-Host "1.21.11forge result=$code11"
}

