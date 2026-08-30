$ErrorActionPreference = 'Continue'
$log = 'C:\Users\user\Desktop\mpsync-main\build-26.2forge.log'
$lastSize = -1
$lastChange = Get-Date
while ($true) {
  Start-Sleep -Seconds 5
  if (-not (Test-Path $log)) { Write-Host 'no log'; break }
  $item = Get-Item $log
  if ($item.Length -ne $lastSize) {
    $lastSize = $item.Length
    $lastChange = Get-Date
    Get-Content $log -Tail 3 | ForEach-Object { Write-Host $_ }
  }
  $alive = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '26\.2forge|MinecraftMaven|mcmaven|mpsync-26\.2' }
  $gradle = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'GradleDaemon|gradlew' -and $_.CommandLine -match '26\.2' }
  $any = @($alive) + @($gradle) | Where-Object { $_ }
  # also any java with working hint from log recent
  $javaFg = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'zulu-25' }
  if (-not $any -and -not $javaFg) {
    Write-Host 'no related java; done'
    break
  }
  # If only wrapper-less java and log idle 180s, consider stuck
  if (((Get-Date) - $lastChange).TotalSeconds -ge 180) {
    Write-Host "IDLE 180s size=$lastSize — checking"
    Get-Content $log -Tail 15
    # if BUILD SUCCESSFUL/FAILED already in log, exit
    $tail = Get-Content $log -Raw
    if ($tail -match 'BUILD SUCCESSFUL|BUILD FAILED') { break }
    Write-Host 'still idle without final status; stop waiting'
    break
  }
}
Write-Host '==== FINAL TAIL ===='
Get-Content $log -Tail 50
$jar = Get-ChildItem 'C:\Users\user\Desktop\mpsync-main\26.2forge\build\libs\*.jar' -EA SilentlyContinue
if ($jar) { Write-Host "JAR OK: $($jar.FullName)" } else { Write-Host 'NO JAR' }
