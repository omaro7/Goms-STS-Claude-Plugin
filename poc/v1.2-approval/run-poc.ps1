<#
.SYNOPSIS
  Goms STS Claude Plugin - V1.2 PoC (step 1): claude -p + --permission-prompt-tool end-to-end test.

.DESCRIPTION
  1) Starts GomsApprovalMcpServer in a separate console window (the "approval window").
  2) Runs claude -p in the workspace with Edit/Write enabled, but every permission request is routed to
     mcp__goms_ide__approve. Approve / deny / edit in the approval window.
  3) Prints the approval log and git diff of the workspace, then stops the server.

.EXAMPLE
  .\run-poc.ps1                                   # sample workspace, interactive approval
  .\run-poc.ps1 -Mode deny                        # everything denied -> no file may change
  .\run-poc.ps1 -BypassTest                       # risk check: settings allow rule vs. prompt tool
  .\run-poc.ps1 -Workspace D:\some\copy -Prompt "Add a toString() to src/Foo.java"
#>
[CmdletBinding()]
param(
    [string]$Workspace = "",
    [string]$Claude = "",
    [string]$Java = "java",
    [string]$Prompt = "",
    [ValidateSet("ask", "allow", "deny")][string]$Mode = "ask",
    [ValidateSet("text", "stream-json")][string]$OutputFormat = "text",
    [string]$SettingSources = "",
    [int]$MaxTurns = 15,
    [switch]$BypassTest,
    [switch]$ForceAsk,
    [switch]$ResetSample,
    [switch]$KeepServer
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$PocDir = $PSScriptRoot
$OutDir = Join-Path $PocDir "out"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Step([string]$msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

function New-SampleWorkspace([string]$dir) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Force -Path (Join-Path $dir "src") | Out-Null
    $crlf = "`r`n"
    $hello = @(
        'public class Hello {',
        '    public static void main(String[] args) {',
        '        System.out.println("Hello, STS!");',
        '    }',
        '}'
    ) -join $crlf
    [IO.File]::WriteAllText((Join-Path $dir "src\Hello.java"), $hello + $crlf, (New-Object System.Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $dir "README.txt"), "Goms V1.2 PoC sample workspace (CRLF Java file).$crlf", (New-Object System.Text.UTF8Encoding($false)))
    if (Get-Command git -ErrorAction SilentlyContinue) {
        Push-Location $dir
        try {
            git init -q
            git add -A
            git -c user.name=goms-poc -c user.email=poc@example.invalid commit -q -m "baseline"
        } finally { Pop-Location }
    }
}

# ---------------------------------------------------------------- prerequisites
Write-Step "Checking prerequisites"
if (-not $Claude) {
    $candidate = Join-Path $env:USERPROFILE ".local\bin\claude.exe"
    if (Test-Path $candidate) { $Claude = $candidate }
    else {
        $cmd = Get-Command claude.exe -ErrorAction SilentlyContinue
        if ($cmd) { $Claude = $cmd.Source }
    }
}
if (-not $Claude -or -not (Test-Path $Claude)) { throw "claude.exe not found. Pass -Claude <path to claude.exe>." }
if (-not (Get-Command $Java -ErrorAction SilentlyContinue)) { throw "Java not found. Pass -Java <path to JDK 17+ java.exe>." }
Write-Host "Claude : $Claude"
Write-Host "Java   : $((Get-Command $Java).Source)"

$usingSample = $false
if (-not $Workspace) {
    $usingSample = $true
    $Workspace = Join-Path $PocDir "sample-workspace"
    if ($ResetSample -or -not (Test-Path $Workspace)) { New-SampleWorkspace $Workspace }
}
$Workspace = (Resolve-Path $Workspace).Path
if ($Workspace.Length -gt 3) { $Workspace = $Workspace.TrimEnd('\') }   # a trailing \ would escape the closing quote
Write-Host "Workspace : $Workspace"

# Risk check: a project-level allow rule for Edit/Write. Does it skip the IDE approval tool?
$localSettings = Join-Path $Workspace ".claude\settings.local.json"
if ($BypassTest) {
    New-Item -ItemType Directory -Force -Path (Split-Path $localSettings) | Out-Null
    [IO.File]::WriteAllText($localSettings, '{ "permissions": { "allow": ["Edit", "Write"] } }', (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "BypassTest: wrote $localSettings (allow Edit, Write)" -ForegroundColor Yellow
} elseif ($usingSample -and (Test-Path $localSettings)) {
    Remove-Item $localSettings -Force
}

if (-not $Prompt) {
    $Prompt = "src/Hello.java 의 main 메서드에 현재 시각을 출력하는 코드를 한 줄 추가하고, " +
              "src/Greeter.java 에 이름을 받아 인사말 문자열을 반환하는 Greeter 클래스를 새로 만들어 주세요. " +
              "변경 후 무엇을 바꿨는지 짧게 요약해 주세요."
}

# ---------------------------------------------------------------- start approval server
Write-Step "Starting approval MCP server (separate window)"
$cfg = Join-Path $OutDir "mcp.json"
$log = Join-Path $OutDir "approvals.jsonl"
Remove-Item $cfg -ErrorAction SilentlyContinue
if (Test-Path $log) { Remove-Item $log -Force }
$src = Join-Path $PocDir "src\GomsApprovalMcpServer.java"
$javaPath = (Get-Command $Java).Source

$serverArgs = @($src, "--root", $Workspace, "--config", $cfg, "--log", $log, "--auto", $Mode) | ForEach-Object { '"' + $_ + '"' }
$inner = "chcp 65001 >nul & title Goms IDE Approval (PoC) & `"$javaPath`" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 " + ($serverArgs -join " ") + " & echo. & echo Server stopped. & pause"
$server = Start-Process -FilePath "cmd.exe" -ArgumentList "/s /c `"$inner`"" -PassThru

$deadline = (Get-Date).AddSeconds(40)   # first single-file launch compiles the source
while (-not (Test-Path $cfg)) {
    if ((Get-Date) -gt $deadline) { throw "Approval server did not start. Check the approval window." }
    if ($server.HasExited) { throw "Approval server window exited early." }
    Start-Sleep -Milliseconds 300
}
Write-Host "MCP config : $cfg"

# ---------------------------------------------------------------- run claude
$claudeArgs = @(
    "-p",
    "--output-format", $OutputFormat,
    "--permission-mode", "default",
    "--tools", "Read,Glob,Grep,Edit,Write",
    "--mcp-config", $cfg,
    "--strict-mcp-config",
    "--permission-prompt-tool", "mcp__goms_ide__approve",
    "--max-turns", "$MaxTurns"
)
if ($OutputFormat -eq "stream-json") { $claudeArgs += "--verbose" }
if ($SettingSources) { $claudeArgs += @("--setting-sources", $SettingSources) }
if ($ForceAsk) {
    # ask rules win over allow rules from any settings source -> Edit/Write must reach the prompt tool.
    # Passed as a file: Windows PowerShell 5.1 mangles inline JSON quotes for native executables.
    $forceAskFile = Join-Path $OutDir "force-ask.settings.json"
    [IO.File]::WriteAllText($forceAskFile, '{ "permissions": { "ask": ["Edit", "Write"] } }', (New-Object System.Text.UTF8Encoding($false)))
    $claudeArgs += @("--settings", $forceAskFile)
    Write-Host "ForceAsk: --settings $forceAskFile (ask: Edit, Write)" -ForegroundColor Yellow
}
$claudeArgs += $Prompt

$env:MCP_TOOL_TIMEOUT = "1800000"      # 30 min: the user may take a while to review a diff
$outFile = Join-Path $OutDir ($(if ($OutputFormat -eq "stream-json") { "stream.jsonl" } else { "claude-output.txt" }))

Write-Step "Running Claude (approve requests in the 'Goms IDE Approval' window)"
Write-Host ($Claude + " " + (($claudeArgs | Select-Object -SkipLast 1) -join " ") + ' "<prompt>"') -ForegroundColor DarkGray
$exitCode = -1
Push-Location $Workspace
try {
    & $Claude @claudeArgs | Tee-Object -FilePath $outFile
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

# ---------------------------------------------------------------- report
Write-Step "Result (claude exit code: $exitCode)"
$requests = @()
if (Test-Path $log) {
    $records = Get-Content $log -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json }
    $requests = @($records | Where-Object { $_.event -eq "permission_request" })
    Write-Host "Permission requests received by IDE: $($requests.Count)"
    $requests | Select-Object tool, path, decision, reason, feedback | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
}

if (Test-Path (Join-Path $Workspace ".git")) {
    Write-Host "git status (workspace):" -ForegroundColor Cyan
    git -C $Workspace status --short
    Write-Host "git diff (tracked files):" -ForegroundColor Cyan
    git -C $Workspace --no-pager diff
}

if ($BypassTest) {
    Write-Host ""
    if ($requests.Count -eq 0) {
        Write-Host "BYPASS DETECTED: files may have changed without any IDE approval request." -ForegroundColor Red
        Write-Host "-> V1.2 must neutralize project/user allow rules (e.g. -SettingSources user) or detect unapproved writes." -ForegroundColor Red
    } else {
        Write-Host "No bypass: allow rule did not skip the IDE approval tool ($($requests.Count) requests)." -ForegroundColor Green
    }
}

if (-not $KeepServer) {
    Write-Step "Stopping approval server"
    & taskkill.exe /PID $server.Id /T /F | Out-Null
}
Write-Host "Logs: $OutDir"
