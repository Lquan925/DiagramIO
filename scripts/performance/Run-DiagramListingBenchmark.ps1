param(
    [string]$JdbcUrl,
    [string]$DbUser = 'benchmark',
    [string]$DbPassword = '',
    [int]$Port = 55433,
    [int]$Rows = 100000,
    [int]$Repeats = 30,
    [string]$OutputDirectory = 'docs/performance',
    [string]$Java = 'java',
    [string]$PostgresJdbcJar = "$env:USERPROFILE/.m2/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar"
)

# Without -JdbcUrl, run PostgreSQL 16.4 in a disposable local cluster; no Docker
# or system-service installation. An existing server needs CREATE DATABASE rights.
$ErrorActionPreference = 'Stop'
$taskRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskPreviousLocation = Get-Location
$taskStarted = $false
$taskOldDbUser = $env:BENCHMARK_DB_USER
$taskOldDbPassword = $env:BENCHMARK_DB_PASSWORD
try {
    Set-Location -LiteralPath $taskRepoRoot
    if (-not (Test-Path -LiteralPath $PostgresJdbcJar)) {
        throw "PostgreSQL JDBC jar missing: $PostgresJdbcJar. Run Maven dependency:go-offline or supply -PostgresJdbcJar."
    }
    if (-not $JdbcUrl) {
        if ($env:OS -ne 'Windows_NT') { throw 'The portable mode requires Windows; supply -JdbcUrl for another platform.' }
        $taskTools = Join-Path $taskRepoRoot 'target/query-benchmark-tools'
        New-Item -ItemType Directory -Force -Path $taskTools | Out-Null
        $taskPgCtl = Join-Path $taskTools 'bin/pg_ctl.exe'
        if (-not (Test-Path -LiteralPath $taskPgCtl)) {
            $taskArchive = Join-Path $taskTools 'postgres-windows.jar'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/16.4.0/embedded-postgres-binaries-windows-amd64-16.4.0.jar' -OutFile $taskArchive
            if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA1).Hash -ne '6D0A371F5CDA9045261B0BC07E0002B0274BB3F4') {
                throw 'Portable PostgreSQL archive checksum mismatch.'
            }
            & tar.exe -xf $taskArchive -C $taskTools
            if ($LASTEXITCODE -ne 0) { throw 'Failed to extract PostgreSQL jar.' }
            & tar.exe -xf (Join-Path $taskTools 'postgres-windows-x86_64.txz') -C $taskTools
            if ($LASTEXITCODE -ne 0) { throw 'Failed to extract PostgreSQL binaries.' }
        }
        $taskRunDirectory = Join-Path $taskRepoRoot ('target/query-benchmark-runs/' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $taskRunDirectory | Out-Null
        $taskData = Join-Path $taskRunDirectory 'pgdata'
        & (Join-Path $taskTools 'bin/initdb.exe') -D $taskData -U benchmark --auth=trust --encoding=UTF8 --locale=C
        if ($LASTEXITCODE -ne 0) { throw 'initdb failed.' }
        & $taskPgCtl -D $taskData -l (Join-Path $taskRunDirectory 'postgres.log') -o "-h 127.0.0.1 -p $Port" -w start
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed.' }
        $taskStarted = $true
        $JdbcUrl = "jdbc:postgresql://127.0.0.1:$Port/postgres"
        $DbUser = 'benchmark'
        $DbPassword = ''
    }
    $env:BENCHMARK_DB_USER = $DbUser
    $env:BENCHMARK_DB_PASSWORD = $DbPassword
    & $Java --class-path $PostgresJdbcJar scripts/performance/DiagramListingBenchmark.java $JdbcUrl $OutputDirectory $Rows $Repeats
    if ($LASTEXITCODE -ne 0) { throw 'Query benchmark failed; inspect output files for partial results.' }
} finally {
    if ($taskStarted) {
        & $taskPgCtl -D $taskData -m fast -w stop
    }
    $env:BENCHMARK_DB_USER = $taskOldDbUser
    $env:BENCHMARK_DB_PASSWORD = $taskOldDbPassword
    Set-Location -LiteralPath $taskPreviousLocation
}
