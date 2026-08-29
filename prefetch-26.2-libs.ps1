$ErrorActionPreference = 'Continue'
$root = 'C:\Users\user\Desktop\mpsync-main'
$destRoot = Join-Path $root 'mirror\maven'

$vjPath = Join-Path $root 'mirror\versions\26.2.json'
$ver = Get-Content $vjPath -Raw | ConvertFrom-Json
Write-Host "Using $vjPath libraries=$($ver.libraries.Count)"

$mirrors = @(
  'https://maven.aliyun.com/repository/public/',
  'https://mirrors.cloud.tencent.com/nexus/repository/maven-public/',
  'https://bmclapi2.bangbang93.com/maven/'
)

$ok = 0
$fail = New-Object System.Collections.Generic.List[string]

foreach ($lib in $ver.libraries) {
  $name = $lib.name
  $parts = $name.Split(':')
  if ($parts.Count -lt 3) { continue }
  $g = $parts[0].Replace('.', '/')
  $a = $parts[1]
  $v = $parts[2]
  $classifier = $null
  if ($parts.Count -ge 4) { $classifier = $parts[3] }
  $file = if ($classifier) { "$a-$v-$classifier.jar" } else { "$a-$v.jar" }
  $rel = "$g/$a/$v/$file"
  $dest = Join-Path $destRoot ($rel -replace '/', '\')
  if (Test-Path $dest) { $ok++; continue }
  New-Item (Split-Path $dest) -ItemType Directory -Force | Out-Null

  $candidates = New-Object System.Collections.Generic.List[string]
  if ($lib.downloads.artifact.url) {
    $u = [string]$lib.downloads.artifact.url
    if ($u -match 'libraries\.minecraft\.net') {
      $candidates.Add('https://bmclapi2.bangbang93.com/maven/' + $rel)
    } else {
      $candidates.Add($u)
    }
  }
  foreach ($m in $mirrors) { $candidates.Add($m + $rel) }

  $got = $false
  foreach ($u in $candidates) {
    try {
      Invoke-WebRequest -Uri $u -OutFile $dest -UseBasicParsing -TimeoutSec 25
      if ((Get-Item $dest).Length -gt 100) { $got = $true; $ok++; break }
      Remove-Item $dest -Force -EA SilentlyContinue
    } catch {
      Remove-Item $dest -Force -EA SilentlyContinue
    }
  }
  if (-not $got) {
    $fail.Add($name)
    Write-Host "FAIL $name"
  } else {
    Write-Host "OK $name"
  }
}

Write-Host "DONE ok=$ok fail=$($fail.Count)"
if ($fail.Count -gt 0) { $fail | Select-Object -First 30 | ForEach-Object { Write-Host "  $_" } }
