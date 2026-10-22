$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskParent = Split-Path -Parent $taskRoot
$taskWorkspace = $taskRoot
if (Test-Path -LiteralPath (Join-Path $taskParent '.aop-workspace')) { $taskWorkspace = $taskParent }
$taskSearchRoots = @()
$taskAncestor = $taskRoot
for ($taskDepth = 0; $taskDepth -lt 6 -and $taskAncestor; $taskDepth++) {
    $taskSearchRoots += $taskAncestor
    $taskAncestor = Split-Path -Parent $taskAncestor
}
$taskJava = $null
foreach ($taskSearchRoot in $taskSearchRoots) {
    $taskJdkRoot = Join-Path $taskSearchRoot '.tools/java21'
    if (-not (Test-Path -LiteralPath $taskJdkRoot)) { continue }
    $taskJdk = Get-ChildItem -LiteralPath $taskJdkRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } |
        Select-Object -First 1
    if ($null -ne $taskJdk) { $taskJava = Join-Path $taskJdk.FullName 'bin/java.exe'; break }
}
if ($null -eq $taskJava -and $env:JAVA_HOME) {
    $taskCandidate = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (Test-Path -LiteralPath $taskCandidate) { $taskJava = $taskCandidate }
}
if ($null -eq $taskJava) {
    $taskJavaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $taskJavaCommand) { $taskJava = $taskJavaCommand.Source }
}
if ($null -eq $taskJava) { throw 'Java 21 is missing. Set JAVA_HOME or install it in .tools/java21/<jdk>.' }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
$taskCacheRoot = Join-Path $taskWorkspace '.cache'
foreach ($taskSearchRoot in $taskSearchRoots) {
    if ($taskWorkspace -ne $taskRoot -and $taskSearchRoot -eq $taskRoot) { continue }
    if (Test-Path -LiteralPath (Join-Path $taskSearchRoot '.cache/gradle')) {
        $taskCacheRoot = Join-Path $taskSearchRoot '.cache'
        break
    }
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $taskCacheRoot 'gradle' }
$env:TEMP = Join-Path $taskWorkspace '.cache/tmp'
$env:TMP = $env:TEMP
$taskJavaHome = Join-Path $taskCacheRoot 'java-home'
$taskProjectCache = Join-Path $taskWorkspace '.gradle'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$env:TEMP,$taskJavaHome,$taskProjectCache | Out-Null
$taskWrapper = Join-Path $taskRoot 'gradle/wrapper/gradle-wrapper.jar'
Push-Location -LiteralPath $taskRoot
try {
    & $taskJava '-Xmx2G' "-Duser.home=$taskJavaHome" "-Dorg.gradle.jvmargs=-Xmx2G -Duser.home=$taskJavaHome" '-classpath' $taskWrapper 'org.gradle.wrapper.GradleWrapperMain' '--project-cache-dir' $taskProjectCache "-PaopWorkspace=$taskWorkspace" @args
    $taskExit = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $taskExit
