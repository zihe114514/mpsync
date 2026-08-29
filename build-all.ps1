<#
    mpsync 服务端 mod —— 八个目标一次全构建。

    为什么需要这个脚本：八个模块都没有在 gradle.properties 里钉 org.gradle.java.home
    （刻意的：那会把某台机器的绝对路径写进工程，之前 modernMP 就是这么被一条失效路径卡住的）。
    本机 JAVA_HOME 指向 JDK 8，直接跑 gradlew 会在 "Gradle requires JVM 17 or later" 上停下，
    所以守护进程用的 JDK 由这里按模块指定。

    每个模块的下限是被两件事同时卡住的：
      * Gradle 发行版能接受的最高守护进程 JVM —— 1.20.1fabric 用 Gradle 8.8，接不了 JDK 25；
      * javac --release 的目标 —— 26.2 两个模块要 release 25，必须真的有一个 JDK 25。

    用法： powershell -ExecutionPolicy Bypass -File .\build-all.ps1 [-Task build]
#>
param(
    [string] $Task = 'build'
)

$ErrorActionPreference = 'Continue'

# 本机 JDK。换机器时只改这三行。
$JDK17 = 'C:\Program Files\Java\jdk-17'
$JDK21 = 'C:\Users\24914\.jdks\ms-21.0.12.1'
$JDK25 = 'C:\Users\24914\.jdks\temurin-25.0.4.1'

# 模块 → 守护进程 JDK
$targets = [ordered]@{
    '1.20.1fabric'  = $JDK17   # Gradle 8.8 + loom 1.7.4：接不了 25
    '1.20.1paper'   = $JDK17   # release 17
    '1.21.1fabric'  = $JDK25   # loom 1.17-SNAPSHOT，release 21
    '1.21.1paper'   = $JDK21   # release 21
    '1.21.11fabric' = $JDK25
    '1.21.11paper'  = $JDK21
    '26.2fabric'    = $JDK25   # release 25：必须是 JDK 25
    '26.2paper'     = $JDK25   # release 25
}

foreach ($jdk in ($targets.Values | Select-Object -Unique)) {
    if (-not (Test-Path $jdk)) { throw "JDK 不存在：$jdk（改一下脚本顶部的 JDK17/21/25）" }
}

$root = $PSScriptRoot
$log = Join-Path $root 'build-all.log'
Remove-Item $log -ErrorAction SilentlyContinue

$results = @()
foreach ($name in $targets.Keys) {
    $jdk = $targets[$name]
    Write-Host "==> $name (JDK: $(Split-Path $jdk -Leaf))"
    "===== $name  jdk=$jdk =====" | Out-File -Append -Encoding utf8 $log

    Push-Location (Join-Path $root $name)
    & .\gradlew.bat "-Dorg.gradle.java.home=$jdk" $Task '--console=plain' 2>&1 |
        Out-File -Append -Encoding utf8 $log
    $code = $LASTEXITCODE
    Pop-Location

    $jar = Get-ChildItem (Join-Path $root "$name\build\libs") -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\-sources\.jar$|\-dev\.jar$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1

    $results += [pscustomobject]@{
        Module = $name
        Exit   = $code
        Jar    = if ($jar) { $jar.Name } else { '-' }
        Size   = if ($jar) { [math]::Round($jar.Length / 1KB, 1) } else { 0 }
        Built  = if ($jar) { $jar.LastWriteTime } else { $null }
    }
}

$results | Format-Table -AutoSize
$failed = $results | Where-Object { $_.Exit -ne 0 }
if ($failed) {
    Write-Host "失败：$(($failed.Module) -join ', ')  —— 详见 $log" -ForegroundColor Red
    exit 1
}
Write-Host "八个目标全部构建完成。日志：$log"
