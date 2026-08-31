<#
    仓库一致性自检 —— 不构建、不联网、不碰 Gradle，秒级跑完。

    为什么需要它：这个仓有 16 个**互相独立**的 Gradle 构建（每个模块自带 settings.gradle
    和 wrapper），没有根工程能替它们统一口径。于是「统一配置」这件事在这里不是靠
    继承实现的，而是靠约定 + 这个脚本兜着：任何一处漂了（版本号、产物名、minecraft_version
    与目录名对不上、又被云端构建机塞进绝对路径、core 里被写进一行 net.minecraft.*），
    都在这里当场报出来，而不是等某个模块构建到一半才炸、或者发版之后才发现两个 jar 协议不一样。

    退出码：0 全通过；1 有问题。

    用法：
      powershell -ExecutionPolicy Bypass -File .\check-repo.ps1
      powershell -ExecutionPolicy Bypass -File .\check-repo.ps1 -Quiet   # 只打问题
#>
param([switch] $Quiet)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$problems = New-Object System.Collections.Generic.List[string]

function Add-Problem([string] $msg) { [void] $problems.Add($msg) }
function Say([string] $msg) { if (-not $Quiet) { Write-Host $msg } }

# 期望的模块矩阵：4 个游戏版本 x 4 个平台。多一个少一个都要人过一眼。
$mcVersions = @('1.20.1', '1.21.1', '1.21.11', '26.2')
$loaders    = @('fabric', 'forge', 'neoforge', 'paper')
$expected   = foreach ($mc in $mcVersions) { foreach ($ld in $loaders) { "$mc$ld" } }

# 从云端构建机 / 别人机器上带回来的东西，一律不许留在仓库里。
$forbidden = @{
    'org.gradle.java.installations.paths' = '钉死的 JDK 绝对路径（守护进程 JDK 由 build-all.ps1 按模块传 -Dorg.gradle.java.home）'
    'C:\Users\user'                       = '云端构建机的用户目录'
    '127.0.0.1:18765'                     = '早已下线的本地 maven 镜像（mirror-server.ps1）'
    'org.gradle.java.home='               = '把某台机器的 JDK 路径写进工程'
}

# core 只许依赖 gson + JDK。这是整个跨版本共用的前提，见 README 的架构一节。
$platformImport = '^\s*import\s+(net\.minecraft|net\.fabricmc|net\.minecraftforge|net\.neoforged|org\.bukkit|io\.papermc|cpw\.mods|org\.spongepowered)'

function Read-Props([string] $path) {
    $map = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)) {
        $t = $line.Trim()
        if ($t.Length -eq 0 -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
    }
    return $map
}

# ── 1. 模块矩阵与必需文件 ───────────────────────────────────────────────────
$actual = Get-ChildItem $root -Directory | Where-Object { $_.Name -match '^(1\.|26\.)' } | ForEach-Object { $_.Name }
foreach ($m in $expected) { if ($actual -notcontains $m) { Add-Problem "缺模块目录：$m" } }
foreach ($m in $actual)   { if ($expected -notcontains $m) { Add-Problem "多出一个没登记的模块目录：$m（build-all.ps1 不会构建它）" } }

$required = @('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar', 'gradle\wrapper\gradle-wrapper.properties')
foreach ($m in ($actual | Sort-Object)) {
    foreach ($f in $required) {
        if (-not (Test-Path (Join-Path $root "$m\$f"))) { Add-Problem "$m 缺文件：$f" }
    }
}

# ── 2. 每个模块的配置口径 ───────────────────────────────────────────────────
$versions = @{}
foreach ($m in ($actual | Sort-Object)) {
    $gp = Join-Path $root "$m\gradle.properties"
    $bg = Join-Path $root "$m\build.gradle"
    if (-not (Test-Path $gp) -or -not (Test-Path $bg)) { continue }

    $props = Read-Props $gp
    $mc = ($mcVersions | Where-Object { $m.StartsWith($_) } | Sort-Object Length -Descending | Select-Object -First 1)
    $ld = $m.Substring($mc.Length)

    if ($props['minecraft_version'] -ne $mc) {
        Add-Problem "${m}: minecraft_version=$($props['minecraft_version'])，与目录名要求的 $mc 不一致"
    }
    $wantAbn = "mpsync-$mc-$ld"
    if ($props['archives_base_name'] -ne $wantAbn) {
        Add-Problem "${m}: archives_base_name=$($props['archives_base_name'])，约定是 $wantAbn（产物名是发版时唯一的辨识依据）"
    }
    if ($props.ContainsKey('mod_id') -and $props['mod_id'] -ne 'mpsync') {
        Add-Problem "${m}: mod_id=$($props['mod_id'])，全仓约定是 mpsync"
    }

    # 版本号：fabric/forge/neoforge 叫 mod_version，paper 叫 plugin_version（平台各自的模板变量名）。
    # 名字可以不同，值必须一致 —— 不同版本的客户端会进同一个房间，版本号不一致排查起来是灾难。
    $ver = if ($props.ContainsKey('mod_version')) { $props['mod_version'] } else { $props['plugin_version'] }
    if ([string]::IsNullOrWhiteSpace($ver)) {
        Add-Problem "${m}: 既没有 mod_version 也没有 plugin_version"
    } else {
        if (-not $versions.ContainsKey($ver)) { $versions[$ver] = @() }
        $versions[$ver] += $m
    }

    # gradle.properties 的**值**必须是纯 ASCII：Gradle 用 ISO-8859-1 读它，
    # 非 ASCII 会以 mojibake 的形式展开进 mods.toml / fabric.mod.json（实测发生过）。
    foreach ($k in $props.Keys) {
        if ($props[$k] -match '[^\x00-\x7F]') {
            Add-Problem "${m}: gradle.properties 的 $k 的值里有非 ASCII 字符（Gradle 按 ISO-8859-1 读，会变乱码）"
        }
    }

    # 共用同一份 core 源码，而不是各自 fork 一份。
    $bgText = [System.IO.File]::ReadAllText($bg, [System.Text.Encoding]::UTF8)
    if ($bgText -notmatch '\.\./core/src/main/java') {
        Add-Problem "${m}: build.gradle 没有把 ../core/src/main/java 加进 srcDirs（core 必须只有一份实现）"
    }

    foreach ($needle in $forbidden.Keys) {
        foreach ($f in @($gp, $bg)) {
            $text = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
            # 注释里提到它没关系（有几处是在解释为什么删掉的），只查非注释行。
            $bad = @([System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8) | Where-Object {
                $t = $_.Trim()
                (-not $t.StartsWith('#')) -and (-not $t.StartsWith('//')) -and $t.Contains($needle)
            })
            if ($bad.Count -gt 0) {
                Add-Problem "$m\$(Split-Path $f -Leaf): 残留 $needle —— $($forbidden[$needle])"
            }
        }
    }
}

if ($versions.Count -gt 1) {
    $desc = ($versions.Keys | Sort-Object | ForEach-Object { "$_ ($($versions[$_].Count) 个: $($versions[$_] -join ', '))" }) -join ' / '
    Add-Problem "各模块版本号不一致：$desc"
}

# ── 3. core 的平台无关性（与 CI 里那道闸同一条规则）─────────────────────────
$coreDir = Join-Path $root 'core\src\main\java'
if (-not (Test-Path $coreDir)) {
    Add-Problem "找不到 core 源码目录：$coreDir"
} else {
    foreach ($f in (Get-ChildItem $coreDir -Recurse -Filter *.java)) {
        $n = 0
        foreach ($line in [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)) {
            $n++
            if ($line -match $platformImport) {
                Add-Problem "core 里出现平台 import：$($f.Name):$n  $($line.Trim())"
            }
        }
    }
}

# ── 4. core-test 必须真的编的是 core 那份源码 ───────────────────────────────
$ctBuild = Join-Path $root 'core-test\build.gradle'
if (-not (Test-Path $ctBuild)) {
    Add-Problem 'core-test/build.gradle 不存在（core 就没有任何测试与编译期守卫了）'
} elseif ([System.IO.File]::ReadAllText($ctBuild, [System.Text.Encoding]::UTF8) -notmatch '\.\./core/src/main/java') {
    Add-Problem 'core-test/build.gradle 没有指向 ../core/src/main/java'
}

# ── 5. ForgeGradle 的大版本必须与 Forge 的版本对得上 ────────────────────────
# 这条是拿血换来的：1.21.11（Forge 61.x）和 26.2（Forge 65.x）的官方 MDK 用的是 FG7，
# 而这个仓最早把 1.21.11forge 钉在 FG6 上，构建会死在
#   java.util.zip.ZipException: duplicate entry: mcp/client/Start.class
# —— FG6 会把 mcp_config 的 inject 源码编好再塞进 srg jar，但 61.x 这条链路上 binpatched jar
# 本来就带着那个类了（实测 1.21.11 的 binpatched.jar 里 mcp/* 有 1 项，1.21.1 是 0 项）。
# 反过来 1.20.1（47.x）和 1.21.1（52.x）必须留在 FG6，FG7 不认那个年代的 userdev。
# FG7 又要求 Gradle 9，所以 wrapper 的大版本一起卡住。
$forgeGradleMajor = [ordered]@{
    '1.20.1forge'  = @{ fg = 6; gradle = 8 }
    '1.21.1forge'  = @{ fg = 6; gradle = 8 }
    '1.21.11forge' = @{ fg = 7; gradle = 9 }
    '26.2forge'    = @{ fg = 7; gradle = 9 }
}
foreach ($m in $forgeGradleMajor.Keys) {
    $bg = Join-Path $root "$m\build.gradle"
    $wp = Join-Path $root "$m\gradle\wrapper\gradle-wrapper.properties"
    $want = $forgeGradleMajor[$m]
    if (Test-Path $bg) {
        $text = [System.IO.File]::ReadAllText($bg, [System.Text.Encoding]::UTF8)
        $mm = [regex]::Match($text, "id\s+'net\.minecraftforge\.gradle'\s+version\s+'\[?(\d+)")
        if (-not $mm.Success) {
            Add-Problem "${m}: build.gradle 里找不到 net.minecraftforge.gradle 的版本声明"
        } elseif ([int] $mm.Groups[1].Value -ne $want.fg) {
            Add-Problem "${m}: ForgeGradle 大版本是 $($mm.Groups[1].Value)，这个 Minecraft 版本必须用 FG$($want.fg)（理由见 check-repo.ps1 里这段注释）"
        }
    }
    if (Test-Path $wp) {
        $text = [System.IO.File]::ReadAllText($wp, [System.Text.Encoding]::UTF8)
        $mm = [regex]::Match($text, 'gradle-(\d+)\.')
        if ($mm.Success -and [int] $mm.Groups[1].Value -lt $want.gradle) {
            Add-Problem "${m}: wrapper 是 Gradle $($mm.Groups[1].Value).x，FG$($want.fg) 需要 Gradle $($want.gradle) 及以上"
        }
    }
}

# ── 6. .ps1 必须带 UTF-8 BOM ────────────────────────────────────────────────
# powershell.exe 5.1 会把没有 BOM 的 .ps1 按本机 ANSI(GBK) 读，脚本里的中文注释
# 和全角括号会直接把解析器搞崩（实测发生过）。
foreach ($ps in (Get-ChildItem $root -Filter *.ps1)) {
    $bytes = [System.IO.File]::ReadAllBytes($ps.FullName)
    if ($bytes.Length -lt 3 -or $bytes[0] -ne 0xEF -or $bytes[1] -ne 0xBB -or $bytes[2] -ne 0xBF) {
        Add-Problem "$($ps.Name) 没有 UTF-8 BOM（powershell.exe 5.1 会按 GBK 读，中文注释会让它解析失败）"
    }
}

# ── 7. gradlew 在 git 索引里要是可执行 ──────────────────────────────────────
# 仓库在 Windows 上开发，但 clone 到 Linux 服务器 / CI 上要能直接 ./gradlew。
if (Get-Command git -ErrorAction SilentlyContinue) {
    $entries = @(& git -C $root ls-files -s 2>$null | Where-Object { $_ -match '(^|/)gradlew$' })
    foreach ($e in $entries) {
        if ($e -notmatch '^100755') {
            $path = ($e -split "`t")[-1]
            Add-Problem "$path 在 git 索引里不是可执行（修：git update-index --chmod=+x '$path'）"
        }
    }
}

# ── 汇总 ────────────────────────────────────────────────────────────────────
Say ''
if ($problems.Count -eq 0) {
    Say "自检通过：$($actual.Count) 个模块、core 无平台依赖、配置口径一致。"
    exit 0
}
Write-Host "自检发现 $($problems.Count) 个问题：" -ForegroundColor Red
foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
exit 1