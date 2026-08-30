$ErrorActionPreference = 'Continue'
$root = 'C:\Users\user\Desktop\mpsync-main'
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-25'
$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8'
$log = Join-Path $root 'build-26.2forge.log'
Remove-Item $log -Force -EA SilentlyContinue

$cmd = "cd /d `"$root\26.2forge`" && set JAVA_HOME=$env:JAVA_HOME&& set GRADLE_OPTS=$env:GRADLE_OPTS&& gradlew.bat build --console=plain --no-daemon > `"$log`" 2>&1"
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmd -PassThru -WindowStyle Hidden
Write-Host "started pid=$($p.Id)"

$lastWrite = Get-Date
$lastSize = 0
while (-not $p.HasExited) {
  Start-Sleep -Seconds 3
  if (Test-Path $log) {
    $item = Get-Item $log
    if ($item.Length -ne $lastSize) {
      $lastSize = $item.Length
      $lastWrite = Get-Date
      Get-Content $log -Tail 5 | ForEach-Object { Write-Host $_ }
    }
  }
  $idle = ((Get-Date) - $lastWrite).TotalSeconds
  if ($idle -ge 120) {
    Write-Host "TIMEOUT idle 120s"
    Stop-Process -Id $p.Id -Force -EA SilentlyContinue
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '26\.2forge\\gradlew|mpsync-26\.2' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue }
    break
  }
}
Start-Sleep 1
Write-Host "EXIT=$($p.ExitCode)"
if (Test-Path $log) {
  Write-Host '--- tail ---'
  Get-Content $log -Tail 40
}
