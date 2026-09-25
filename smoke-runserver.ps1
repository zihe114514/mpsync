<#
    起一次 runServer 冒烟测试：确认 mod 真的被加载了，而不只是「编译过了」。

    这是本仓唯一的端到端检查。core 的单元测试（core-test）覆盖房间逻辑，但它跑在纯 JVM 上，
    证明不了「这个 jar 能被这个版本的加载器装上」—— 通道注册、事件挂点、mods.toml/fabric.mod.json
    这些都只有真的起一次服务器才会暴露。

    判定方式：盯着 run 日志，出现 "MusicParty ready" 或 "Done (" 就算通过，然后立刻停掉。
    不做联机验证（那需要真客户端）。

    只对有 runServer 任务的模块有意义：fabric（loom）、forge / neoforge（FG / ModDevGradle）。
    paper 那四个是普通 java 工程，没有 runServer，用服务器手动装 jar 验证。

    用法：
      powershell -ExecutionPolicy Bypass -File .\smoke-runserver.ps1
      powershell -ExecutionPolicy Bypass -File .\smoke-runserver.ps1 -Modules 1.21.1fabric
#>
param(
    [string[]] $Modules = @(),
    [int]      $IdleSec = 120,
    [int]      $MaxSec  = 300
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot

# 与 build-all.ps1 同一套 JDK 映射。换机器时两个脚本一起改。
$JDK17 = 'C:\Program Files\Java\jdk-17'
$JDK21 = 'C:\Users\24914\.jdks\ms-21.0.12.1'
$JDK25 = 'C:\Users\24914\.jdks\temurin-25.0.4.1'

$jdkOf = [ordered]@{
    '1.20.1fabric'    = $JDK17
    '1.21.1fabric'    = $JDK25
    '1.21.11fabric'   = $JDK25
    '26.2fabric'      = $JDK25
    '1.20.1neoforge'  = $JDK17
    '1.21.1neoforge'  = $JDK21
    '1.21.11neoforge' = $JDK21
    '26.2neoforge'    = $JDK25
    '1.20.1forge'     = $JDK17
    '1.21.1forge'     = $JDK21
    '1.21.11forge'    = $JDK21
    '26.2forge'       = $JDK25
    # 26.3 家族同样跑在 JDK 25 上（version json 的 javaVersion.majorVersion=25）。
    '26.3fabric'      = $JDK25
    '26.3neoforge'    = $JDK25
    '26.3forge'       = $JDK25
}

if ($Modules.Count -eq 0) { $Modules = @($jdkOf.Keys) }
$unknown = $Modules | Where-Object { -not $jdkOf.Contains($_) }
if ($unknown) { throw "这些模块没有 runServer 任务（或不存在）：$($unknown -join ', ')" }

function Invoke-Smoke {
    param([string] $Project, [string] $JavaHome)

    $dir = Join-Path $root $Project
    if (-not (Test-Path $dir)) { Write-Host "跳过 $Project（目录不存在）"; return $null }

    # runServer 第一件事就是检查 eula，没有它连日志都出不来。
    $run = Join-Path $dir 'run'
    New-Item $run -ItemType Directory -Force | Out-Null
    Set-Content (Join-Path $run 'eula.txt') 'eula=true' -Encoding ASCII

    $log = Join-Path $root ("run-{0}.log" -f $Project)
    Remove-Item $log -Force -ErrorAction SilentlyContinue

    # 刻意用 --no-daemon：起服务器的守护进程留着没用，还会占住 run 目录的文件锁。
    $gradleArgs = "runServer --console=plain --no-daemon `"-Dorg.gradle.java.home=$JavaHome`""
    $cmd = "cd /d `"$dir`" && gradlew.bat $gradleArgs > `"$log`" 2>&1"
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $cmd -PassThru -WindowStyle Hidden
    Write-Host "==> 冒烟 $Project (pid=$($p.Id), JDK: $(Split-Path $JavaHome -Leaf))"

    $start = Get-Date
    $lastWrite = Get-Date
    $lastSize = 0
    $ok = $false
    $reason = ''
    while (-not $p.HasExited) {
        Start-Sleep -Seconds 3
        if (Test-Path $log) {
            $item = Get-Item $log
            if ($item.Length -ne $lastSize) { $lastSize = $item.Length; $lastWrite = Get-Date }
            $tail = (@(Get-Content $log -Tail 40 -ErrorAction SilentlyContinue) -join "`n")
            if ($tail -match 'MusicParty ready') { $ok = $true; $reason = 'mod 已加载'; break }
            if ($tail -match 'Done \(|For help, type') { $ok = $true; $reason = '服务器起来了'; break }
        }
        if (((Get-Date) - $lastWrite).TotalSeconds -ge $IdleSec) { $reason = "日志静默 $IdleSec 秒"; break }
        if (((Get-Date) - $start).TotalSeconds -ge $MaxSec) { $reason = "超过 $MaxSec 秒未起来"; break }
    }

    # 连带清掉这个模块起的 java 进程：runServer 是 gradlew 的子进程，杀父不一定带走子。
    Get-CimInstance Win32_Process |
        Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($dir) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    if ($ok) {
        Write-Host "    通过（$reason）" -ForegroundColor Green
    } else {
        Write-Host "    失败（$reason），日志尾部：" -ForegroundColor Red
        Get-Content $log -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" }
    }
    return [pscustomobject]@{ Module = $Project; Passed = $ok; Note = $reason; Log = $log }
}

$results = @()
foreach ($m in $Modules) {
    $r = Invoke-Smoke -Project $m -JavaHome $jdkOf[$m]
    if ($r) { $results += $r }
}

Write-Host ''
$results | Format-Table Module, Passed, Note -AutoSize
$bad = $results | Where-Object { -not $_.Passed }
if ($bad) { exit 1 }
