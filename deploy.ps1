<#
.SYNOPSIS
    一键编译 CyanTestAddon、部署到 Folia 服务器 plugins 目录并重启服务器。

.DESCRIPTION
    流程：
      1. Maven clean package（自动把 JAVA_HOME 指向 JDK 21）
      2. 停止正在运行的 Folia
      3. 清理 plugins 里的旧 jar 并复制新 jar
      4. 运行 start.bat 启动服务器
      5. 等待 server-port 开始监听

    注意：服务器未开启 RCON，无法远程发送 stop 命令。脚本先尝试 taskkill
    正常关闭，失败后强制终止（测试服适用；世界数据可能回退到上次自动保存）。

    Windows 上插件 jar 会被运行中的 JVM 占用，因此必须先停服再复制。

.EXAMPLE
    .\deploy.bat                 # 完整流程（推荐双击）
    .\deploy.ps1 -NoRestart      # 只编译 + 复制，不重启
    .\deploy.ps1 -SkipBuild      # 跳过编译，直接部署 target 里已有的 jar
    .\deploy.ps1 -DeploySlimefun # 同时部署本地 Slimefun fork 构建出的 jar
    .\deploy.ps1 -Detached       # 后台启动（无控制台窗口，适合 CI / 自动化调用）
    .\deploy.ps1 -StartTimeout 240 -StopTimeout 90
#>
[CmdletBinding()]
param(
    [string] $ProjectDir   = '',
    [string] $ServerDir    = 'C:\Users\Elvin\Desktop\MCServer\folia',
    [string] $JavaHome     = 'C:\Program Files\Java\jdk-21',
    [string] $MavenCmd     = '',
    [switch] $NoRestart,
    [switch] $SkipBuild,
    [switch] $DeploySlimefun,
    [string] $SlimefunJar  = '',
    [switch] $Detached,
    [int]    $StopTimeout  = 45,
    [int]    $StartTimeout = 150
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step([string] $Text) { Write-Host "`n>>> $Text" -ForegroundColor Cyan }
function Write-Ok([string] $Text)   { Write-Host "    $Text" -ForegroundColor Green }
function Write-Note([string] $Text) { Write-Host "    $Text" -ForegroundColor Yellow }

# 原生命令写 stderr 时，PS 5.1 在 ErrorActionPreference=Stop 下会抛 NativeCommandError。
# 只在调用原生程序的这一小段里临时降级，脚本其余部分仍保持严格错误检查。
function Invoke-Native([scriptblock] $Block) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $old }
}

# 只匹配真正启动 Folia 服务端的 java 进程，避免误杀 IDEA / MC 客户端 / Maven 守护进程
function Get-FoliaProcess {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match '-jar\s+folia[\w.\-]*\.jar' })
}

function Resolve-Maven {
    if ($MavenCmd) {
        if (Test-Path -LiteralPath $MavenCmd) { return $MavenCmd }
        throw "指定的 Maven 不存在：$MavenCmd"
    }
    $found = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }

    $idea = 'C:\Program Files\JetBrains\IntelliJ IDEA 2024.1.4\plugins\maven\lib\maven3\bin\mvn.cmd'
    if (Test-Path -LiteralPath $idea) { return $idea }

    $wrapper = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2\wrapper\dists') `
        -Recurse -Filter 'mvn.cmd' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($wrapper) { return $wrapper.FullName }

    throw '找不到 Maven，请用 -MavenCmd 指定 mvn.cmd 的完整路径'
}

# 按 PID 判活：进程退出阶段 WMI 的 CommandLine 可能短暂为空，不能靠命令行匹配判断
function Test-PidAlive([int] $Id) {
    return [bool](Get-Process -Id $Id -ErrorAction SilentlyContinue)
}

# Windows 上插件 jar 会被 JVM 占用，进程退出后句柄释放也可能有延迟
function Wait-FileFree([string] $Path, [int] $Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $fs = [System.IO.File]::Open($Path, 'Open', 'ReadWrite', 'None')
            $fs.Close()
            return $true
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

function Test-PortListening([int] $Port) {
    try {
        return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)
    } catch { return $false }
}

function Get-ServerPort([string] $Dir) {
    $file = Join-Path $Dir 'server.properties'
    if (Test-Path -LiteralPath $file) {
        $line = Get-Content -LiteralPath $file |
            Where-Object { $_ -match '^\s*server-port\s*=' } | Select-Object -First 1
        if ($line) { return [int](($line -split '=', 2)[1].Trim()) }
    }
    return 25565
}

# $PSScriptRoot 在 param 默认值里不一定可用，放到这里兜底解析
if (-not $ProjectDir) { $ProjectDir = $PSScriptRoot }
if (-not $ProjectDir) { $ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ProjectDir) { throw '无法确定项目目录，请用 -ProjectDir 指定' }

Write-Host '=== CyanTestAddon -> Folia 部署 ===' -ForegroundColor White
Write-Host "项目目录: $ProjectDir"
Write-Host "服务器目录: $ServerDir"

if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir 'pom.xml'))) { throw "找不到 pom.xml：$ProjectDir" }
if (-not (Test-Path -LiteralPath $ServerDir)) { throw "服务器目录不存在：$ServerDir" }
$pluginsDir = Join-Path $ServerDir 'plugins'
if (-not (Test-Path -LiteralPath $pluginsDir)) { throw "plugins 目录不存在：$pluginsDir" }
$serverPort = Get-ServerPort $ServerDir

# ---------- 1. 编译 ----------
if ($SkipBuild) {
    Write-Step '1/5 跳过编译（-SkipBuild）'
} else {
    $mvn = Resolve-Maven
    Write-Step "1/5 编译  Maven: $mvn"
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
    Invoke-Native { & $mvn '-B' '-f' (Join-Path $ProjectDir 'pom.xml') 'clean' '--offline' 'package' '-DskipTests' }
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 构建失败（退出码 $LASTEXITCODE），服务器未做任何改动"
    }
    Write-Ok '构建成功'
}

# ---------- 2. 定位产物 ----------
Write-Step '2/5 定位构建产物'
$targetDir = Join-Path $ProjectDir 'target'
$jar = Get-Item -LiteralPath (Join-Path $targetDir 'CyanTestAddon v1.0.0.jar') -ErrorAction SilentlyContinue
if (-not $jar) {
    $jar = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if (-not $jar) { throw "target 下没有可用的插件 jar：$targetDir" }
Write-Ok "$($jar.Name)  ($([math]::Round($jar.Length / 1KB, 1)) KB, $($jar.LastWriteTime.ToString('HH:mm:ss')))"

# ---------- 3. 停服 ----------
if ($NoRestart) {
    Write-Step '3/5 跳过停止服务器（-NoRestart）'
    if ((Get-FoliaProcess).Count -gt 0) {
        Write-Note '服务器仍在运行，覆盖 jar 可能因文件占用失败'
    }
} else {
    Write-Step '3/5 停止 Folia 服务器'
    $procs = Get-FoliaProcess
    if ($procs.Count -eq 0) {
        Write-Note '服务器当前未运行，无需停止'
    } else {
        $ids = @($procs | ForEach-Object { [int] $_.ProcessId })
        foreach ($id in $ids) {
            Write-Note "尝试正常关闭 PID $id"
            # 控制台进程通常不响应不带 /F 的 taskkill，这里只是给 JVM 一次优雅退出的机会
            Invoke-Native { & cmd.exe /c "taskkill /PID $id /T >nul 2>&1" }
        }

        Write-Note "等待 JVM 保存世界并退出（最多 $StopTimeout 秒）..."
        # 按 PID 判活，避免 WMI 命令行在进程退出阶段短暂为空导致误判
        $deadline = (Get-Date).AddSeconds($StopTimeout)
        while ((Get-Date) -lt $deadline -and @($ids | Where-Object { Test-PidAlive $_ }).Count -gt 0) {
            Start-Sleep -Milliseconds 500
        }

        $alive = @($ids | Where-Object { Test-PidAlive $_ })
        if ($alive.Count -gt 0) {
            Write-Note '优雅关闭超时（且未启用 RCON），改为强制终止（世界数据可能回退到上次自动保存）'
            foreach ($id in $alive) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
        }

        # 等 PID 消失 + 端口释放，最多 25 秒
        $deadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $deadline) {
            if (@($ids | Where-Object { Test-PidAlive $_ }).Count -eq 0 -and -not (Test-PortListening $serverPort)) {
                break
            }
            Start-Sleep -Milliseconds 500
        }
        if (@($ids | Where-Object { Test-PidAlive $_ }).Count -gt 0 -or (Get-FoliaProcess).Count -gt 0) {
            throw '服务器进程未能停止（或已被守护进程重新拉起），已中止部署'
        }
        Write-Ok "服务器已停止，端口 $serverPort 已释放"
    }
}

# ---------- 4. 部署 jar ----------
Write-Step '4/5 部署 jar 到 plugins'
$oldJars = @(Get-ChildItem -LiteralPath $pluginsDir -Filter 'CyanTestAddon*.jar' -File -ErrorAction SilentlyContinue)
$fileWait = if ($NoRestart) { 3 } else { 20 }
foreach ($old in $oldJars) {
    if (-not (Wait-FileFree $old.FullName $fileWait)) {
        throw "旧 jar 仍被占用，无法替换：$($old.FullName)`n请确认 Folia 已完全退出（必要时手动结束 java 进程）"
    }
}
# 同名 jar 直接覆盖，不要先删除：桌面/回收站策略或文件占用都可能让删除失败，
# 而 Copy-Item -Force 覆盖并不需要先删文件。
$dest = Join-Path $pluginsDir $jar.Name
foreach ($old in $oldJars) {
    if ($old.FullName -ieq $dest) {
        continue
    }
    Write-Note "移除旧版本 jar: $($old.Name)"
    try {
        Remove-Item -LiteralPath $old.FullName -Force -ErrorAction Stop
    } catch {
        Write-Note "移除失败（不影响本次覆盖部署，必要时手动清理）：$($_.Exception.Message)"
    }
}
try {
    Copy-Item -LiteralPath $jar.FullName -Destination $dest -Force -ErrorAction Stop
} catch {
    throw "部署失败（服务器是否仍在运行、jar 被占用？）：$($_.Exception.Message)"
}
Write-Ok "已部署: $dest"

# 同时部署本地 Slimefun fork 的 jar（修了 Folia 线程问题时需要一起换掉）
if ($DeploySlimefun) {
    if (-not $SlimefunJar) {
        $SlimefunJar = Join-Path (Split-Path -Parent $ProjectDir) 'Slimefun4\build\libs\Slimefun-DEV-SNAPSHOT.jar'
    }
    $sfSource = Get-Item -LiteralPath $SlimefunJar -ErrorAction SilentlyContinue
    if (-not $sfSource) { throw "找不到 Slimefun jar：$SlimefunJar（请先运行 gradlew shadowJar）" }

    $sfDest = Join-Path $pluginsDir $sfSource.Name
    if ((Test-Path -LiteralPath $sfDest) -and -not (Wait-FileFree $sfDest $fileWait)) {
        throw "Slimefun jar 仍被占用，无法替换：$sfDest"
    }
    try {
        Copy-Item -LiteralPath $sfSource.FullName -Destination $sfDest -Force -ErrorAction Stop
    } catch {
        throw "部署 Slimefun jar 失败：$($_.Exception.Message)"
    }
    Write-Ok "已部署: $sfDest  ($([math]::Round($sfSource.Length / 1KB, 1)) KB)"
}

# ---------- 5. 启动并等待就绪 ----------
if ($NoRestart) {
    Write-Step '5/5 跳过启动服务器（-NoRestart）'
    Write-Host "`n部署完成，服务器未重启。" -ForegroundColor Green
    return
}

Write-Step '5/5 启动 Folia 服务器'
$bat = Join-Path $ServerDir 'start.bat'
if (-not (Test-Path -LiteralPath $bat)) { throw "找不到启动脚本：$bat" }
if ($Detached) {
    # 由 WMI 服务创建进程，脱离调用方会话：从 CI / 自动化工具里执行也不会被连带终止。
    # 代价是没有可见的控制台窗口，日志看 logs\latest.log。
    $cmdLine = 'cmd.exe /c ""' + $bat + '""'
    $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = $cmdLine
        CurrentDirectory = $ServerDir
    }
    if ($created.ReturnValue -ne 0) {
        throw "启动服务器失败，Win32_Process.Create 返回码 $($created.ReturnValue)"
    }
    Write-Ok "已后台启动服务器（cmd PID $($created.ProcessId)，日志见 logs\latest.log）"
} else {
    # 手动双击 deploy.bat 时用这个：会打开可见的服务器控制台窗口
    $launcher = Start-Process -FilePath $bat -WorkingDirectory $ServerDir -WindowStyle Normal -PassThru
    Write-Ok "已打开服务器控制台（cmd PID $($launcher.Id)）"
}

Write-Note "等待端口 $serverPort 开始监听（最多 $StartTimeout 秒）..."
$deadline = (Get-Date).AddSeconds($StartTimeout)
$ready = $false
$seen = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-PortListening $serverPort) {
        $ready = $true
        break
    }
    if ((Get-FoliaProcess).Count -gt 0) {
        $seen = $true
    } elseif ($seen) {
        Write-Note '服务器进程意外退出，启动失败'
        break
    }
}

if ($ready) {
    Write-Ok "服务器已就绪，端口 $serverPort 正在监听"
} else {
    Write-Note "服务器尚未就绪，请查看服务器控制台窗口或 $ServerDir\logs\latest.log"
}

Write-Host "`n完成：jar 已部署，服务器已重启。" -ForegroundColor Green
