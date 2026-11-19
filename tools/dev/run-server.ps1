# Local dev server: loads .dev/server.env (git-ignored), builds and runs the backend on :8080.
# Phone reaches it through:  adb reverse tcp:8080 tcp:8080
# Against production Atlas + Cloudinary:  .\tools\dev\run-server.ps1 -EnvFile .dev\prod.env
param([switch]$NoBuild, [string]$EnvFile = ".dev\server.env")
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root
if (-not (Test-Path $EnvFile)) { Write-Error "Missing $EnvFile (see deploy/.env.example)"; exit 1 }
Get-Content $EnvFile | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $k, $v = $_.Split('=', 2); [Environment]::SetEnvironmentVariable($k, $v, 'Process')
}
if (-not $NoBuild) { .\gradlew.bat -p server-build :server:installDist -q --console=plain }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
& $java -cp "server\build\install\server\lib\*" com.rajdeep.vesper.server.MainKt
