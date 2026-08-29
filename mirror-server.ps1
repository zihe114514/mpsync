param([int]$Port=18765, [string]$Root)
$ErrorActionPreference='Continue'
if (-not $Root) { $Root = Join-Path $PSScriptRoot 'mirror' }
$cache = Join-Path $Root 'maven'
New-Item -ItemType Directory -Force -Path $cache | Out-Null

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
try {
  $listener.Start()
} catch {
  Write-Host "START_FAIL $_"
  exit 1
}
Write-Host "MIRROR_SERVER_READY http://127.0.0.1:$Port/ root=$Root"

while ($listener.IsListening) {
  $ctx = $null
  try { $ctx = $listener.GetContext() } catch { Start-Sleep -Milliseconds 200; continue }
  try {
    $path = [System.Uri]::UnescapeDataString($ctx.Request.Url.AbsolutePath.TrimStart('/'))
    while ($path.Contains('//')) { $path = $path.Replace('//','/') }
    if ($path.StartsWith('maven/')) { } elseif ($path -match 'maven/(.+)$') { $path = 'maven/' + $Matches[1] }
    $isHead = ($ctx.Request.HttpMethod -eq 'HEAD')
    $file = $null
    # piston-meta package style: v1/packages/{sha}/{id}.json
    if ($path -match '^v1/packages/[a-f0-9]+/(.+\.json)$') {
      $id = [IO.Path]::GetFileNameWithoutExtension($Matches[1])
      $cand = Join-Path $Root "versions\$id.json"
      if (Test-Path -LiteralPath $cand) { $file = $cand }
    }
    if (-not $file -and ([string]::IsNullOrWhiteSpace($path) -or $path -eq 'version_manifest_v2.json' -or $path.EndsWith('version_manifest_v2.json'))) {
      $file = Join-Path $Root 'version_manifest_local.json'
    } elseif (-not $file -and ($path -eq 'version_manifest.json' -or $path -eq 'mc/game/version_manifest.json' -or $path.EndsWith('version_manifest.json'))) {
      $file = Join-Path $Root 'version_manifest.json'
    } elseif (-not $file) {
      $local = Join-Path $Root ($path -replace '/','\')
      if (Test-Path -LiteralPath $local) {
        $file = $local
      } elseif ($path.EndsWith('/') -or ($path -match 'maven/.+/$') -or ($path -notmatch '\.[A-Za-z0-9]+$')) {
        # Directory / metadata listing: do not hang — 404 fast so Gradle falls through to Aliyun
        $file = $null
      } elseif ($path.StartsWith('maven/') -or $path -match '^(com|net|org|ca|io|it|at)/') {
        $rel = $path
        if ($rel.StartsWith('maven/')) { $rel = $rel.Substring(6) }
        $dest = Join-Path $cache ($rel -replace '/','\')
        if ((Test-Path -LiteralPath $dest) -and ((Get-Item -LiteralPath $dest).Length -gt 0)) {
          $file = $dest
        } else {
          New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
          foreach ($base in @('https://libraries.minecraft.net/','https://maven.aliyun.com/repository/public/')) {
            try {
              Write-Host "FETCH $($base)$rel"
              $wc = New-Object System.Net.WebClient
              $wc.Headers.Add('User-Agent','mpsync-mirror/1.0')
              $tmp = "$dest.tmp"
              $wc.DownloadFile("$base$rel", $tmp)
              if ((Get-Item $tmp).Length -gt 0) {
                Move-Item -Force $tmp $dest
                $file = $dest
                break
              }
            } catch {
              Remove-Item "$dest.tmp" -Force -ErrorAction SilentlyContinue
            }
          }
        }
      }
    }

    if ($file -and (Test-Path -LiteralPath $file)) {
      $len = (Get-Item -LiteralPath $file).Length
      $ctx.Response.StatusCode = 200
      $ctx.Response.ContentLength64 = $len
      $ctx.Response.KeepAlive = $false
      if (-not $isHead) {
        $in = [System.IO.File]::OpenRead($file)
        try {
          $buf = New-Object byte[] 65536
          while (($n = $in.Read($buf,0,$buf.Length)) -gt 0) {
            $ctx.Response.OutputStream.Write($buf,0,$n)
          }
        } finally { $in.Close() }
      }
    } else {
      $msg = [Text.Encoding]::UTF8.GetBytes("not found: $path")
      $ctx.Response.StatusCode = 404
      $ctx.Response.ContentLength64 = $msg.Length
      if (-not $isHead) { $ctx.Response.OutputStream.Write($msg,0,$msg.Length) }
    }
  } catch {
    Write-Host "REQ_ERR $_"
    try { $ctx.Response.StatusCode = 500 } catch {}
  } finally {
    try { $ctx.Response.Close() } catch {}
  }
}
