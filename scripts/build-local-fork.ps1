#requires -Version 7.0
<#
.SYNOPSIS
构建本地 Windows 程序与 Android APK，并整理安装包、校验文件和构建日志。
.DESCRIPTION
SDK 与签名配置合并保存到受 Git 忽略的 local.properties，其它配置保持原值。
签名密码只写入私有配置，不输出到控制台或作为命令行参数传递。
此脚本不运行测试，不发布、不推送，也不覆盖正在使用的程序。
#>
[CmdletBinding()]
param(
    [string]$JbrHome,
    [string]$AndroidSdk,
    [string]$SigningConfig,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$installationRoot = Split-Path -Parent $repositoryRoot
$toolsDirectory = Join-Path $installationRoot 'build-tools'
$localPropertiesPath = Join-Path $repositoryRoot 'local.properties'
$utf8 = [Text.UTF8Encoding]::new($false)

function Get-ConfigValue {
    param([object]$Config, [string]$Name, [string]$Fallback = '')
    $property = $Config.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        return $Fallback
    }
    return [string]$property.Value
}

function ConvertTo-PropertiesValue {
    param([string]$Value)
    # java.util.Properties.load(InputStream) 通过转义读取 Unicode 配置值。
    $result = [Text.StringBuilder]::new()
    foreach ($character in $Value.ToCharArray()) {
        switch ([int]$character) {
            9 { [void]$result.Append('\t') }
            10 { [void]$result.Append('\n') }
            13 { [void]$result.Append('\r') }
            32 { [void]$result.Append('\ ') }
            92 { [void]$result.Append('\\') }
            default {
                if ([int]$character -lt 32 -or [int]$character -gt 126) {
                    [void]$result.Append(('\u{0:x4}' -f [int]$character))
                } else {
                    [void]$result.Append($character)
                }
            }
        }
    }
    return $result.ToString()
}

function Get-ConfiguredSdk {
    param([string[]]$Lines)
    foreach ($line in $Lines) {
        if ($line -match '^\s*sdk\.dir\s*[:=]\s*(.*)$') {
            $path = [regex]::Replace($Matches[1], '\\u([0-9a-fA-F]{4})', {
                param($match)
                [string][char][Convert]::ToInt32($match.Groups[1].Value, 16)
            })
            return [regex]::Replace($path, '\\(.)', '$1')
        }
    }
    return ''
}

function Save-LocalProperties {
    param([string[]]$Lines, [System.Collections.IDictionary]$Values)
    $result = [Collections.Generic.List[string]]::new()
    $written = @{}
    foreach ($line in $Lines) {
        if ($line -match '^\s*([^#!\s][^=:]*?)\s*[:=]' -and $Values.Contains($Matches[1])) {
            $key = $Matches[1]
            if (!$written.ContainsKey($key)) {
                $result.Add("$key=$(ConvertTo-PropertiesValue $Values[$key])")
                $written[$key] = $true
            }
        } else {
            $result.Add($line)
        }
    }
    foreach ($key in $Values.Keys) {
        if (!$written.ContainsKey($key)) {
            $result.Add("$key=$(ConvertTo-PropertiesValue $Values[$key])")
        }
    }
    [IO.File]::WriteAllText($localPropertiesPath, ($result -join "`n") + "`n", $utf8)
}

function Write-PackageHash {
    param([string]$Path)
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$Path.sha1", $hash, $utf8)
}

if ([string]::IsNullOrWhiteSpace($JbrHome)) {
    $JbrHome = Get-ChildItem -LiteralPath $toolsDirectory -Directory -Filter 'jbrsdk_jcef-21*' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if ([string]::IsNullOrWhiteSpace($JbrHome)) {
    throw '没有找到 JBR 21，请使用 -JbrHome 指定带 JCEF 的 JBR SDK。'
}
$JbrHome = [IO.Path]::GetFullPath($JbrHome)
foreach ($requiredFile in @('bin/java.exe', 'bin/jpackage.exe', 'jmods/jcef.jmod')) {
    if (!(Test-Path -LiteralPath (Join-Path $JbrHome $requiredFile) -PathType Leaf)) {
        throw "JBR SDK 缺少 $requiredFile，请使用带 JCEF 的 JBR 21。"
    }
}
if (!(Select-String -LiteralPath (Join-Path $JbrHome 'release') -Pattern '^JAVA_VERSION="21\.' -Quiet)) {
    throw '此项目需要 JBR 21。'
}

$localLines = if (Test-Path -LiteralPath $localPropertiesPath) {
    [IO.File]::ReadAllLines($localPropertiesPath, [Text.Encoding]::UTF8)
} else { @() }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = Get-ConfiguredSdk $localLines }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($AndroidSdk) -or !(Test-Path -LiteralPath $AndroidSdk -PathType Container)) {
    throw '找不到 Android SDK，请配置 local.properties 的 sdk.dir 或传入 -AndroidSdk。'
}
$AndroidSdk = [IO.Path]::GetFullPath($AndroidSdk)

if ([string]::IsNullOrWhiteSpace($SigningConfig)) {
    $SigningConfig = Join-Path $toolsDirectory 'signing/signing.json'
}
$SigningConfig = [IO.Path]::GetFullPath($SigningConfig)
if (!(Test-Path -LiteralPath $SigningConfig -PathType Leaf)) {
    throw "找不到签名配置：$SigningConfig"
}
try {
    $signing = [IO.File]::ReadAllText($SigningConfig, [Text.Encoding]::UTF8) | ConvertFrom-Json
} catch {
    throw '签名配置不是有效的 JSON 文件。'
}
$keyAlias = Get-ConfigValue $signing 'alias'
$storePassword = Get-ConfigValue $signing 'password'
$keyPassword = Get-ConfigValue $signing 'keyPassword' $storePassword
$storeFile = Get-ConfigValue $signing 'storeFile' 'brokenmyth-animeko.p12'
if ([string]::IsNullOrWhiteSpace($keyAlias) -or [string]::IsNullOrWhiteSpace($storePassword)) {
    throw '签名 JSON 必须提供非空的 alias 和 password。'
}
if (![IO.Path]::IsPathRooted($storeFile)) {
    $storeFile = Join-Path (Split-Path -Parent $SigningConfig) $storeFile
}
$storeFile = [IO.Path]::GetFullPath($storeFile)
if (!(Test-Path -LiteralPath $storeFile -PathType Leaf)) { throw "找不到签名文件：$storeFile" }

# 两个 storeFile 属性会重复创建 release 签名配置，只保留绝对路径属性。
$localLines = @($localLines | Where-Object { $_ -notmatch '^\s*signing_release_storeFileFromRoot\s*[:=]' })
Save-LocalProperties $localLines ([ordered]@{
    'sdk.dir' = $AndroidSdk.Replace('\', '/')
    'signing_release_storeFile' = $storeFile.Replace('\', '/')
    'signing_release_storePassword' = $storePassword
    'signing_release_keyAlias' = $keyAlias
    'signing_release_keyPassword' = $keyPassword
})

$versionLine = Select-String -LiteralPath (Join-Path $repositoryRoot 'gradle.properties') -Pattern '^version\.name=(.+)$'
if ($null -eq $versionLine) { throw 'gradle.properties 中缺少 version.name。' }
$version = $versionLine.Matches[0].Groups[1].Value.Trim()
if ($version -notmatch '^[0-9A-Za-z.+-]+$') { throw 'version.name 包含不适合安装包文件名的字符。' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $installationRoot '本地构建'
}
$outputPath = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "$version-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
[void][IO.Directory]::CreateDirectory($outputPath)
$logPath = Join-Path $outputPath 'build.log'
$buildLog = [IO.StreamWriter]::new($logPath, $false, $utf8)
$buildLog.AutoFlush = $true
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANI_COMPOSE_JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$buildExitCode = 1
$locationPushed = $false
try {
    $env:JAVA_HOME = $JbrHome
    $env:ANI_COMPOSE_JAVA_HOME = $JbrHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    Push-Location -LiteralPath $repositoryRoot
    $locationPushed = $true
    $arguments = @(
        ':app:desktop:createReleaseDistributable',
        ':app:android:assembleDefaultRelease',
        '--console=plain',
        '--stacktrace',
        '--no-daemon'
    )
    Write-Host "开始构建，日志：$logPath"
    & (Join-Path $repositoryRoot 'gradlew.bat') @arguments 2>&1 | ForEach-Object {
        $line = $_.ToString()
        $buildLog.WriteLine($line)
        Write-Host $line
    }
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        $buildExitCode = $gradleExitCode
        throw "Gradle 构建失败，退出码 $gradleExitCode。日志：$logPath"
    }

    $desktopSource = Join-Path $repositoryRoot 'app/desktop/build/compose/binaries/main-release/app/Ani'
    if (!(Test-Path -LiteralPath (Join-Path $desktopSource 'Ani.exe') -PathType Leaf)) {
        throw '构建完成但未找到 Windows Ani.exe。'
    }
    Copy-Item -LiteralPath $desktopSource -Destination (Join-Path $outputPath 'Ani') -Recurse
    $desktopArchive = Join-Path $outputPath "ani-$version-windows-x86_64.zip"
    # .NET ZIP 支持超过 2 GB 的运行库，保留自动更新要求的顶层 Ani 目录。
    [IO.Compression.ZipFile]::CreateFromDirectory(
        (Join-Path $outputPath 'Ani'), $desktopArchive, [IO.Compression.CompressionLevel]::Optimal, $true
    )
    Write-PackageHash $desktopArchive

    $apkDirectory = Join-Path $repositoryRoot 'app/android/build/outputs/apk/default/release'
    $metadataPath = Join-Path $apkDirectory 'output-metadata.json'
    if (!(Test-Path -LiteralPath $metadataPath -PathType Leaf)) { throw '构建完成但未找到 APK 输出元数据。' }
    $metadata = [IO.File]::ReadAllText($metadataPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if (@($metadata.elements).Count -eq 0) { throw 'APK 输出元数据没有安装包。' }
    foreach ($element in $metadata.elements) {
        $abiFilter = @($element.filters | Where-Object { $_.filterType -eq 'ABI' }) | Select-Object -First 1
        $abi = if ($null -ne $abiFilter) { $abiFilter.identifier } else { 'universal' }
        $apkSource = Join-Path $apkDirectory $element.outputFile
        if ($element.outputFile -match '-unsigned\.apk$') { throw 'APK 未签名，请检查本地签名配置。' }
        $apkTarget = Join-Path $outputPath "ani-$version-$abi.apk"
        Copy-Item -LiteralPath $apkSource -Destination $apkTarget
        Write-PackageHash $apkTarget
    }
    $buildExitCode = 0
    $message = "构建完成：$outputPath"
    $buildLog.WriteLine($message)
    Write-Host $message
} catch {
    $message = $_.Exception.Message
    $buildLog.WriteLine($message)
    Write-Host $message -ForegroundColor Red
} finally {
    if ($locationPushed) { Pop-Location }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    $buildLog.Dispose()
}
exit $buildExitCode
