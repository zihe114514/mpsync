# Fast build runner: fail if no new output for 120s
param(
  [Parameter(Mandatory=$true)][string]$Project,
  [string]$Task = 'build',
  [int]$IdleTimeoutSec = 120,
  [string]$JavaHome = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $root) { $root = 'C:\Users\user\Desktop\mpsync-main' }
# When script lives in repo root:
if (Test-Path (Join-Path $PSScriptRoot $Project)) { $root = $PSScriptRoot }

$dir = Join-Path $root $Project
if (-not (Test-Path $dir)) { throw "Missing project: $dir" }

$log = Join-Path $root ("build-{0}.log" -f $Project)
if (Test-Path $log) { Remove-Item $log -Force }

$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
elseif (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25' }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = Join-Path $dir 'gradlew.bat'
$psi.Arguments = "$Task --console=plain --no-daemon -Dorg.gradle.daemon=false"
$psi.WorkingDirectory = $dir
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
$psi.Environment['JAVA_HOME'] = $env:JAVA_HOME
$psi.Environment['GRADLE_OPTS'] = $env:GRADLE_OPTS

$p = New-Object System.Diagnostics.Process
$p.StartInfo = $psi
$null = $p.Start()

$lastOut = Get-Date
$reader = {
  param($stream, $logPath)
  while (($line = $stream.ReadLine()) -ne $null) {
    $line | Tee-Object -FilePath $logPath -Append | Write-Host
  }
}

$outJob = Start-Job -ScriptBlock {
  param($proc, $logPath)
  while (($line = $proc.StandardOutput.ReadLine()) -ne $null) {
    Add-Content -Path $logPath -Value $line
    Write-Output $line
  }
} -ArgumentList $p, $log

$errJob = Start-Job -ScriptBlock {
  param($proc, $logPath)
  while (($line = $proc.StandardError.ReadLine()) -ne $null) {
    Add-Content -Path $logPath -Value $line
    Write-Output $line
  }
} -ArgumentList $p, $log

$lastSize = 0
while (-not $p.HasExited) {
  Start-Sleep -Seconds 2
  # drain jobs
  Receive-Job $outJob -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_; $lastOut = Get-Date }
  Receive-Job $errJob -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_; $lastOut = Get-Date }
  $size = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
  if ($size -gt $lastSize) { $lastSize = $size; $lastOut = Get-Date }
  $idle = ((Get-Date) - $lastOut).TotalSeconds
  if ($idle -ge $IdleTimeoutSec) {
    Write-Host "TIMEOUT: no output for ${IdleTimeoutSec}s — killing $Project"
    try { $p.Kill($true) } catch { $p.Kill() }
    Start-Sleep -Seconds 1
    throw "Idle timeout for $Project after ${IdleTimeoutSec}s"
  }
}
Receive-Job $outJob -Wait -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
Receive-Job $errJob -Wait -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
Remove-Job $outJob, $errJob -Force -ErrorAction SilentlyContinue

$code = $p.ExitCode
Write-Host "EXIT $Project = $code"
if ($code -ne 0) { throw "Build failed: $Project exit=$code (log: $log)" }
