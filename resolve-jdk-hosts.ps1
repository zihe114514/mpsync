<#
    给 JDK 单独生成一份 hosts 文件（配合 -Djdk.net.hosts.file 使用），不联网就没法用，但只查 DoH。

    为什么需要这个东西 —— 这是 ForgeGradle 7 那两个模块（1.21.11forge / 26.2forge）在本机
    能不能构建出来的唯一卡点，值得把原因写清楚：

      * FG7 在配置阶段会 fork 一个叫 Minecraft Mavenizer 的工具进程，由它去取
        launchermeta.mojang.com 的版本清单、piston-data 的 client/server jar、
        libraries.minecraft.net 的依赖、maven.minecraftforge.net 的 userdev。
      * 那个工具用的下载器（net.minecraftforge.util.download.DownloadUtilsImpl）把连接超时
        **硬编码成 5 秒**（javap 可查：Duration.ofSeconds(5) / TIMEOUT = 5000），没有任何开关。
      * 本机的代理是 Clash TUN + fake-ip：域名解析回来的是 198.18.x.x，TLS 握手要等它在上游
        重新拨号，实测 launchermeta / piston-data / libraries.minecraft.net / maven.minecraftforge.net
        全都是 5~6.3 秒，正好压在 5 秒线上 —— 于是 mavenizer 每次都死在
        `Failed to execute task downloadLauncherManifest ... HttpConnectTimeoutException`。
        走 127.0.0.1:7890 显式代理一样是 6 秒，没用。
      * 但**直接连这些域名的真实 IP** 只要 0.6~1.2 秒（同一台机器实测）：fake-ip 那条路才是慢的。

    所以办法是：用 DoH（阿里云 223.5.5.5，本机 ~100ms）拿到真实 IP，写成一份 JDK 专用 hosts 文件，
    构建时通过环境变量 JAVA_TOOL_OPTIONS 把 -Djdk.net.hosts.file 塞给那个 fork 出来的 JVM。
    JDK 9+ 支持这个开关，作用范围只有那个进程，不动系统 hosts、不动代理配置、不改任何第三方 jar。

    注意：jdk.net.hosts.file 是**独占**的 —— 一旦启用，JVM 只认这个文件里的主机名，其他一律
    UnknownHostException。所以下面的列表必须覆盖构建会碰到的所有主机；缺了谁，报错会明确告诉你是谁。

    用法：
      powershell -ExecutionPolicy Bypass -File .\resolve-jdk-hosts.ps1
      powershell -ExecutionPolicy Bypass -File .\resolve-jdk-hosts.ps1 -Force
    正常由 build-all.ps1 自动调用，不用手工跑。
#>
param(
    [string] $OutFile,
    [int]    $MaxAgeHours = 12,
    [switch] $Force,
    [switch] $Quiet
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Say([string] $m) { if (-not $Quiet) { Write-Host $m } }

# 输出路径不能带空格：JAVA_TOOL_OPTIONS 是按空白切分的，带空格的路径会被拦腰截断。
if (-not $OutFile) {
    $candidates = @(
        (Join-Path ([System.IO.Path]::GetTempPath()) 'mpsync-jdk-hosts.txt'),
        (Join-Path $env:LOCALAPPDATA 'mpsync\jdk-hosts.txt'),
        'C:\mpsync-cache\jdk-hosts.txt'
    )
    $OutFile = $candidates | Where-Object { $_ -and ($_ -notmatch ' ') } | Select-Object -First 1
    if (-not $OutFile) { throw '找不到不含空格的可写路径，请显式传 -OutFile' }
}

# 构建会碰到的主机。宁可多列，列表里多一个只是多一次 DoH 查询。
$hostNames = @(
    # Minecraft 本体与依赖
    'launchermeta.mojang.com', 'piston-meta.mojang.com', 'piston-data.mojang.com',
    'libraries.minecraft.net', 'resources.download.minecraft.net',
    'api.mojang.com', 'session.minecraft.net',
    # Forge / NeoForge / Fabric / Paper
    'maven.minecraftforge.net', 'files.minecraftforge.net',
    'maven.neoforged.net', 'maven.fabricmc.net', 'repo.papermc.io',
    'maven.parchmentmc.org',
    # 镜像与中央仓库、Gradle 自己
    'maven.aliyun.com', 'repo1.maven.org', 'repo.maven.apache.org',
    'plugins.gradle.org', 'services.gradle.org', 'mirrors.cloud.tencent.com',
    'api.foojay.io'
)

if (-not $Force -and (Test-Path $OutFile)) {
    $age = (Get-Date) - (Get-Item $OutFile).LastWriteTime
    if ($age.TotalHours -lt $MaxAgeHours) {
        Say "JDK hosts 仍新鲜（$([int]$age.TotalMinutes) 分钟前生成）：$OutFile"
        return $OutFile
    }
}

$dir = Split-Path $OutFile -Parent
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('# 由 resolve-jdk-hosts.ps1 生成，供 -Djdk.net.hosts.file 使用。不要手改，会被覆盖。')
$lines.Add("# 生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
# 本机名与 localhost 必须在：启用这个文件之后，JVM 连自己都得从这里查。
$lines.Add('127.0.0.1 localhost')
$lines.Add('::1 ip6-localhost')
$lines.Add("127.0.0.1 $env:COMPUTERNAME")

$failed = @()
foreach ($h in $hostNames) {
    $ips = @()
    foreach ($doh in @("https://223.5.5.5/resolve?name=$h&type=A", "https://doh.pub/dns-query?name=$h&type=A")) {
        try {
            $r = Invoke-RestMethod -Uri $doh -Headers @{ Accept = 'application/dns-json' } -TimeoutSec 15
            $ips = @($r.Answer | Where-Object { $_.type -eq 1 } | ForEach-Object { $_.data })
            if ($ips.Count -gt 0) { break }
        } catch { }
    }
    if ($ips.Count -eq 0) { $failed += $h; continue }
    foreach ($ip in $ips) { $lines.Add("$ip $h") }
    Say ("  {0,-34} {1}" -f $h, ($ips -join ', '))
}

[System.IO.File]::WriteAllLines($OutFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
if ($failed.Count -gt 0) {
    Write-Host "这些主机没解析出 A 记录（构建碰到它们就会 UnknownHost）：$($failed -join ', ')" -ForegroundColor Yellow
}
Say "已写入 $OutFile（$($lines.Count) 行）"
return $OutFile