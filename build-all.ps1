<#
    mpsync 服务端 mod —— core 单元测试 + 二十个目标一次全构建，产物统一汇总。

    为什么需要这个脚本：所有模块都不在 gradle.properties 里钉 org.gradle.java.home
    （刻意的：那会把某台机器的绝对路径写进工程，之前 modernMP 就是这么被一条失效路径卡住的；
    forge / neoforge 那八个模块从云端构建机带回来的 org.gradle.java.installations.paths
    是同样的毛病，已经删掉）。本机 JAVA_HOME 指向 JDK 8，直接跑 gradlew 会在
    "Gradle requires JVM 17 or later" 上停下，所以守护进程用的 JDK 由这里按模块指定。

    每个模块的 JDK 下限是被两件事同时卡住的：
      * Gradle 发行版 / 构建插件能接受的最高守护进程 JVM —— 1.20.1fabric 用 Gradle 8.8，
        接不了 JDK 25；ForgeGradle 6 也只跟到 21；
      * javac --release 的目标 —— 26.2 那一批要 release 25，必须真的有一个 JDK 25。

    顺序是刻意的：
      1. core-test 打头，纯 JVM 秒级跑完。房间逻辑坏了就别浪费十几分钟去构建二十个模块；
      2. fabric / paper 八个，最快也最稳，先出货；
      3. neoforge 四个；
      4. forge 四个放最后（对网络和 forge_gradle 缓存最挑），挂了也不影响前面的产物。

    用法：
      powershell -ExecutionPolicy Bypass -File .\build-all.ps1
      powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -Only 1.21.1fabric,1.21.1paper
      powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -Task assemble -NoCollect
      powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -TestOnly     # 只跑 core 单元测试
      powershell -ExecutionPolicy Bypass -File .\check-repo.ps1              # 只跑仓库自检
#>
param(
    [string]   $Task = 'build',
    [string[]] $Only = @(),
    [string]   $DistDir = 'F:\donked\构建产物\服务端mod-mpsync',
    [switch]   $NoCollect,
    [switch]   $TestOnly,
    [switch]   $SkipTests,
    [switch]   $SkipChecks
)

$ErrorActionPreference = 'Continue'

# 本机 JDK。换机器时只改这三行。
$JDK17 = 'C:\Program Files\Java\jdk-17'
$JDK21 = 'C:\Users\24914\.jdks\ms-21.0.12.1'
$JDK25 = 'C:\Users\24914\.jdks\temurin-25.0.4.1'

# 模块 → 守护进程 JDK
$targets = [ordered]@{
    # ── core 的单元测试：纯 JVM，只依赖 gson，同时是「core 不许碰 Minecraft」的编译期守卫 ──
    'core-test'       = $JDK17
    # ── fabric / paper：没有外部前提，纯 Maven 依赖 ──────────────────────────
    '1.20.1fabric'    = $JDK17   # Gradle 8.8 + loom 1.7.4：接不了 25
    '1.20.1paper'     = $JDK17   # release 17
    '1.21.1fabric'    = $JDK25   # loom 1.17-SNAPSHOT，release 21
    '1.21.1paper'     = $JDK21   # release 21
    '1.21.11fabric'   = $JDK25
    '1.21.11paper'    = $JDK21
    '26.2fabric'      = $JDK25   # release 25：必须真有一个 JDK 25
    '26.2paper'       = $JDK25
    # ── neoforge：ModDevGradle；1.20.1 那个用的其实是 Forge 系 API（NeoForge 20.1 是 Forge 的分支）
    '1.20.1neoforge'  = $JDK17
    '1.21.1neoforge'  = $JDK21
    '1.21.11neoforge' = $JDK21
    '26.2neoforge'    = $JDK25
    # ── forge：ForgeGradle，依赖 Minecraft 依赖已在 forge_gradle 缓存里或公网可达 ──
    '1.20.1forge'     = $JDK17
    '1.21.1forge'     = $JDK21
    '1.21.11forge'    = $JDK21
    '26.2forge'       = $JDK25   # ForgeGradle 7.x
    # 26.3 家族：目标运行时同样是 Java 25（version json 的 javaVersion.majorVersion=25）。
    '26.3fabric'      = $JDK25
    '26.3paper'       = $JDK25
    '26.3neoforge'    = $JDK25
    '26.3forge'       = $JDK25
}

# core-test 只跑测试，也不参与产物汇总（它不产出可安装的 jar）。
$TEST_PROJECT = 'core-test'

# ForgeGradle 7 的两个模块。它们在配置阶段会 fork 一个 Minecraft Mavenizer 工具进程去取
# launchermeta / piston-data / libraries.minecraft.net / maven.minecraftforge.net，而那个工具的
# 连接超时是**硬编码的 5 秒**；本机 Clash TUN + fake-ip 下这些域名的 TLS 握手要 5~6.3 秒，
# 于是每次都死在 downloadLauncherManifest。直连真实 IP 只要 0.6~1.2 秒，所以给它们注入一份
# JDK 专用 hosts 文件（-Djdk.net.hosts.file，只影响这个进程）。详见 resolve-jdk-hosts.ps1 的头注释。
#
# 用 JAVA_TOOL_OPTIONS 而不是命令行 -D：那个属性得让 **fork 出来的** JVM 也拿到，而 FG7 只转发
# http.* / https.* / java.net.* / javax.net.ssl.* / jdk.tls.* 这几类前缀，jdk.net.hosts.file 不在其中；
# 环境变量则是子进程天然继承的。配 --no-daemon 是因为 fork 继承的是**守护进程**的环境，
# 复用一个早先没带这个变量启动的守护进程就白搭了。
$FG7_MODULES = @('1.21.11forge', '26.2forge', '26.3forge')

if ($TestOnly) { $Only = @($TEST_PROJECT) }

if ($Only.Count -gt 0) {
    $unknown = $Only | Where-Object { -not $targets.Contains($_) }
    if ($unknown) { throw "未知模块：$($unknown -join ', ')" }
    $filtered = [ordered]@{}
    foreach ($name in $targets.Keys) { if ($Only -contains $name) { $filtered[$name] = $targets[$name] } }
    $targets = $filtered
} elseif ($SkipTests) {
    $filtered = [ordered]@{}
    foreach ($name in $targets.Keys) { if ($name -ne $TEST_PROJECT) { $filtered[$name] = $targets[$name] } }
    $targets = $filtered
}

foreach ($jdk in ($targets.Values | Select-Object -Unique)) {
    if (-not (Test-Path $jdk)) { throw "JDK 不存在：$jdk（改一下脚本顶部的 JDK17/21/25）" }
}

$root = $PSScriptRoot

# 构建前先跑一遍仓库自检（check-repo.ps1）：不联网、不起 Gradle，秒级出结果。
# 配置口径漂了（二十个模块版本号不一致、产物名不合约定、minecraft_version 与目录名对不上、
# core 里被写进一行 net.minecraft.*、又被塞回某台机器的 JDK 绝对路径）都会在这里被挡住。
# 放在最前面的理由和 core-test 一样：这类问题花十几分钟构建完再发现，代价太大 ——
# 更糟的是配置漂了还能构建成功，只是发出去的二十个 jar 彼此不是一套。
if (-not $SkipChecks) {
    & (Join-Path $root 'check-repo.ps1')
    if ($LASTEXITCODE -ne 0) {
        Write-Host '仓库自检未通过，已中止构建（确实要跳过就加 -SkipChecks）' -ForegroundColor Red
        exit 1
    }
}
$log = Join-Path $root 'build-all.log'
Remove-Item $log -ErrorAction SilentlyContinue

$collecting = (-not $NoCollect) -and ($targets.Keys | Where-Object { $_ -ne $TEST_PROJECT })
if ($collecting) { New-Item -ItemType Directory -Force -Path $DistDir | Out-Null }

$jdkHostsFile = $null
if ($targets.Keys | Where-Object { $FG7_MODULES -contains $_ }) {
    Write-Host '==> 解析 JDK hosts（ForgeGradle 7 模块需要，见 resolve-jdk-hosts.ps1）'
    $jdkHostsFile = & (Join-Path $root 'resolve-jdk-hosts.ps1') -Quiet | Select-Object -Last 1
    if (-not $jdkHostsFile -or -not (Test-Path $jdkHostsFile)) {
        Write-Host 'JDK hosts 生成失败，ForgeGradle 7 的模块大概率会死在 downloadLauncherManifest' -ForegroundColor Yellow
    }
}

$results = @()
foreach ($name in $targets.Keys) {
    $jdk = $targets[$name]
    $isTest = ($name -eq $TEST_PROJECT)
    $moduleTask = if ($isTest) { 'test' } else { $Task }
    Write-Host "==> $name  (JDK: $(Split-Path $jdk -Leaf), task: $moduleTask)"
    "===== $name  jdk=$jdk  task=$moduleTask =====" | Out-File -Append -Encoding utf8 $log

    # 为什么不直接 `& .\gradlew.bat ... | Out-File`：Gradle 每次新起守护进程时，守护进程会
    # 继承这条管道的写端，于是 gradlew.bat 早就退出了、BUILD SUCCESSFUL 也打完了，
    # PowerShell 的管道还在等那个永远不会关的写端 —— 整个脚本就那么挂住（实测挂过五分钟以上，
    # 这个仓换三种 JDK 构建二十个模块，新起守护进程是常态）。
    # 改成让 cmd 把输出重定向到文件：没有管道，PowerShell 只需要等 cmd 退出。
    $modOut = Join-Path ([System.IO.Path]::GetTempPath()) "mpsync-build-$name.log"
    Remove-Item $modOut -ErrorAction SilentlyContinue
    $gradlew = Join-Path $root "$name\gradlew.bat"
    # FG7 模块：注入 JDK hosts + 不复用守护进程，理由见上面 $FG7_MODULES 那段注释。
    $envPrefix = ''
    $extraArgs = ''
    if (($FG7_MODULES -contains $name) -and $jdkHostsFile) {
        $envPrefix = "set `"JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=$jdkHostsFile`" && "
        $extraArgs = ' --no-daemon'
    }
    & cmd /c "cd /d `"$(Join-Path $root $name)`" && $envPrefix`"$gradlew`" `"-Dorg.gradle.java.home=$jdk`" $moduleTask --console=plain$extraArgs > `"$modOut`" 2>&1"
    $code = $LASTEXITCODE
    if (Test-Path $modOut) {
        # Gradle 的输出走的是控制台代码页（本机是 GBK），按本机 ANSI 读回来再统一写成 UTF-8，
        # 免得日志里 UTF-8 的分隔行和 GBK 的构建输出混在一起两头都不可读。
        $ansi = [System.Text.Encoding]::GetEncoding([System.Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage)
        [System.IO.File]::ReadAllText($modOut, $ansi) |
            Out-File -Append -Encoding utf8 $log
        Remove-Item $modOut -ErrorAction SilentlyContinue
    }

    # core 的单元测试是闸门：房间逻辑坏了就没有必要再花十几分钟构建二十个模块。
    if ($isTest -and $code -ne 0) {
        Write-Host "core 单元测试失败，已中止后续构建 —— 详见 $log" -ForegroundColor Red
        exit 1
    }
    if ($isTest) {
        $results += [pscustomobject]@{ Module = $name; Exit = $code; Size = 0; Built = $null; Jar = '(单元测试)' }
        continue
    }

    # 只挑「能直接丢进 mods/ 或 plugins/ 的那一个」：build/libs 里还躺着 -sources、loom 的 -dev，
    # 以及改过 archivesName 之后的旧名产物，用通配全收会把过时的 jar 也一起发出去。
    $jar = Get-ChildItem (Join-Path $root "$name\build\libs") -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\-sources\.jar$|\-dev\.jar$|\-dev-shadow\.jar$|\-slim\.jar$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $NoCollect -and $jar) {
        # 汇总名带模块前缀：二十个 jar 平铺在一个目录里，1.21.1 与 1.21.11、forge 与 neoforge
        # 的产物名很容易看混，前缀是唯一能一眼分清来源的东西。
        $distName = "$name`__$($jar.Name)"
        # 只清这个模块自己的旧产物，别碰别的模块。
        Get-ChildItem $DistDir -Filter "$name`__*.jar" -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Copy-Item $jar.FullName (Join-Path $DistDir $distName) -Force
    }

    $results += [pscustomobject]@{
        Module = $name
        Exit   = $code
        Size   = if ($jar) { [math]::Round($jar.Length / 1KB, 1) } else { 0 }
        Built  = if ($jar) { $jar.LastWriteTime } else { $null }
        Jar    = if ($jar) { $jar.Name } else { '-' }
    }
}

$results | Format-Table Module, Exit, Size, Built, Jar -AutoSize

# ── 发版前的新旧校验 ─────────────────────────────────────────────────────────
# core/ 与四个 *-common 是共用源码，但它们是被 srcDirs **按值拷进**每个 jar 的，
# 模块之间没有任何 Gradle 依赖关系。只重建一部分模块，剩下那些 jar 里就还是旧 core，
# 而它们会在同一个房间里通信 —— 这种「新旧混跑」实测发生过（产物目录里 8 个 jar 是新 core，
# 两个 neoforge jar 停在一小时前的旧 core），而且不会有任何报错。所以在这里挡一道。
$stale = @()
if ($collecting -and (Test-Path $DistDir)) {
    $sharedDirs = @('core', 'fabric-common', 'paper-common', 'forge-common', 'neoforge-common') |
        ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path $_ }
    $newestShared = Get-ChildItem $sharedDirs -Recurse -Filter *.java -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newestShared) {
        $stale = Get-ChildItem $DistDir -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -lt $newestShared.LastWriteTime }
        if ($stale) {
            Write-Host ""
            Write-Host "共用源码比产物新（$($newestShared.Name) 改于 $($newestShared.LastWriteTime)）," -ForegroundColor Yellow
            Write-Host "下列 jar 里还是旧的 core，别和新 jar 混着发：" -ForegroundColor Yellow
            $stale | ForEach-Object { Write-Host "    $($_.Name)  ($($_.LastWriteTime))" -ForegroundColor Yellow }
            Write-Host "    重建办法： .\build-all.ps1 -Only <模块名>" -ForegroundColor Yellow
        }
    }
}

$failed = $results | Where-Object { $_.Exit -ne 0 }
if ($failed) {
    Write-Host "失败：$(($failed.Module) -join ', ')  —— 详见 $log" -ForegroundColor Red
    exit 1
}
if ($stale) { exit 2 }
Write-Host "$($results.Count) 个目标全部通过。日志：$log"
if ($collecting) { Write-Host "产物已汇总到：$DistDir" }
