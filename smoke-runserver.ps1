$ErrorActionPreference = 'Continue'
$root = 'C:\Users\user\Desktop\mpsync-main'

function Smoke-RunServer {
  param([string]$Project, [string]$JavaHome, [int]$IdleSec = 120, [int]$MaxSec = 300)
  $dir = Join-Path $root $Project
  $run = Join-Path $dir 'run'
  New-Item $run -ItemType Directory -Force | Out-Null
  Set-Content (Join-Path $run 'eula.txt') 'eula=true' -Encoding ASCII
  $log = Join-Path $root ("run-{0}.log" -f $Project)
  Remove-Item $log -Force -EA SilentlyContinue
  $env:JAVA_HOME = $JavaHome
  $env:GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8'
  $cmd = "cd /d `"$dir`" && set JAVA_HOME=$JavaHome&& set GRADLE_OPTS=$env:GRADLE_OPTS&& gradlew.bat runServer --console=plain --no-daemon > `"$log`" 2>&1"
  $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmd -PassThru -WindowStyle Hidden
  Write-Host "SMOKE $Project pid=$($p.Id)"
  $start = Get-Date
  $lastWrite = Get-Date
  $lastSize = 0
  $ok = $false
  while (-not $p.HasExited) {
    Start-Sleep -Seconds 3
    if (Test-Path $log) {
      $item = Get-Item $log
      if ($item.Length -ne $lastSize) { $lastSize = $item.Length; $lastWrite = Get-Date }
      $lines = @(Get-Content $log -Tail 40 -EA SilentlyContinue)
      $tail = ($lines -join "`n")
      if ($tail -match 'Done \(|For help, type|MusicParty ready|\[mpsync\]') {
        Write-Host "SMOKE OK $Project (server up)"
        $ok = $true
        break
      }
    }
    if (((Get-Date) - $lastWrite).TotalSeconds -ge $IdleSec) {
      Write-Host "SMOKE TIMEOUT idle $Project"
      break
    }
    if (((Get-Date) - $start).TotalSeconds -ge $MaxSec) {
      Write-Host "SMOKE TIMEOUT max $Project"
      break
    }
  }
  Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match [regex]::Escape($Project) } | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue
  }
  try { Stop-Process -Id $p.Id -Force -EA SilentlyContinue } catch {}
  Start-Sleep 2
  if (-not $ok -and (Test-Path $log)) {
    $tail = Get-Content $log -Tail 25
    Write-Host "---- $Project tail ----"
    $tail | ForEach-Object { Write-Host $_ }
  }
  return $ok
}

$results = @{}
$results['26.2forge'] = Smoke-RunServer -Project '26.2forge' -JavaHome 'C:\Program Files\Zulu\zulu-25' -MaxSec 240
$results['1.21.11forge'] = Smoke-RunServer -Project '1.21.11forge' -JavaHome 'D:\jdk-21\zulu21.44.17-ca-jdk21.0.8-win_x64' -MaxSec 240

Write-Host '==== RESULTS ===='
$results.GetEnumerator() | ForEach-Object { Write-Host "$($_.Key)=$($_.Value)" }
