<#
.SYNOPSIS
  Protocol smoke test for GomsApprovalMcpServer WITHOUT Claude (no login, no tokens used).
  Checks: auth, initialize, notification(202), tools/list, allow (CRLF match), path guard denials.
.EXAMPLE
  .\smoke-test.ps1            # or: .\smoke-test.ps1 -Java "C:\Program Files\Java\jdk-17\bin\java.exe"
#>
param([string]$Java = "java")

$ErrorActionPreference = "Stop"
$PocDir = $PSScriptRoot
$Work = Join-Path (Join-Path $PocDir "out") "smoke"
if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
$Ws = Join-Path $Work "ws"
New-Item -ItemType Directory -Force -Path (Join-Path $Ws "src"), (Join-Path $Ws ".git") | Out-Null
[IO.File]::WriteAllText((Join-Path (Join-Path $Ws "src") "Hello.java"), "public class Hello {`r`n    void a() {`r`n        int x = 1;`r`n    }`r`n}`r`n")

$cfg = Join-Path $Work "mcp.json"
$src = Join-Path (Join-Path $PocDir "src") "GomsApprovalMcpServer.java"
$argLine = (@($src, "--root", $Ws, "--config", $cfg, "--log", (Join-Path $Work "log.jsonl"), "--auto", "allow") | ForEach-Object { '"' + $_ + '"' }) -join " "
$p = Start-Process -FilePath $Java -ArgumentList $argLine -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $Work "server.out.txt") -RedirectStandardError (Join-Path $Work "server.err.txt")

$fail = 0
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host "PASS  $name" -ForegroundColor Green }
    else { Write-Host "FAIL  $name  $detail" -ForegroundColor Red; $script:fail++ }
}

try {
    $deadline = (Get-Date).AddSeconds(40)
    while (-not (Test-Path $cfg)) { if ((Get-Date) -gt $deadline -or $p.HasExited) { throw "server did not start" }; Start-Sleep -Milliseconds 300 }
    $conf = Get-Content $cfg -Raw | ConvertFrom-Json
    $url = $conf.mcpServers.goms_ide.url
    $hdr = @{ Authorization = $conf.mcpServers.goms_ide.headers.Authorization; Accept = "application/json, text/event-stream" }

    function Rpc($body) {
        Invoke-RestMethod -Uri $url -Method Post -Headers $hdr -ContentType "application/json; charset=utf-8" `
            -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress)))
    }
    function Decide($toolName, $inputObj) {
        $r = Rpc @{ jsonrpc = "2.0"; id = 9; method = "tools/call"; params = @{ name = "approve"; arguments = @{ tool_name = $toolName; input = $inputObj } } }
        $r.result.content[0].text | ConvertFrom-Json
    }

    try { Invoke-WebRequest -Uri $url -Method Post -Body "{}" -Headers @{ Authorization = "Bearer wrong" } -UseBasicParsing | Out-Null; Check "rejects bad token" $false }
    catch { Check "rejects bad token" ($_.Exception.Response.StatusCode.value__ -eq 401) }

    $init = Rpc @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{ protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "smoke"; version = "1" } } }
    Check "initialize" ($init.result.serverInfo.name -eq "goms-ide-approval")

    $n = Invoke-WebRequest -Uri $url -Method Post -Headers $hdr -ContentType "application/json" -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -UseBasicParsing
    Check "notification -> 202" ($n.StatusCode -eq 202)

    $list = Rpc @{ jsonrpc = "2.0"; id = 2; method = "tools/list" }
    Check "tools/list has approve" ($list.result.tools[0].name -eq "approve")

    $d = Decide "Edit" @{ file_path = "src/Hello.java"; old_string = "        int x = 1;`n    }"; new_string = "        int x = 2;`n    }" }
    Check "Edit allowed (LF old_string matched against CRLF file)" ($d.behavior -eq "allow" -and $d.updatedInput.new_string -like "*int x = 2*")

    $d = Decide "Write" @{ file_path = "src/New.java"; content = "class New {}`n" }
    Check "Write new file allowed" ($d.behavior -eq "allow")

    $d = Decide "Write" @{ file_path = "../outside.txt"; content = "x" }
    Check "outside root denied" ($d.behavior -eq "deny" -and $d.message -like "*outside*")

    $d = Decide "Write" @{ file_path = ".git/config"; content = "x" }
    Check ".git denied" ($d.behavior -eq "deny")

    $d = Decide "Edit" @{ file_path = "src/Hello.java"; old_string = "does-not-exist"; new_string = "x" }
    Check "missing old_string denied" ($d.behavior -eq "deny" -and $d.message -like "*not found*")

    $d = Decide "Edit" @{ file_path = "src/Hello.java"; old_string = "    "; new_string = "  " }
    Check "ambiguous old_string denied" ($d.behavior -eq "deny" -and $d.message -like "*occurs*")
} finally {
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
}

if ($fail -eq 0) { Write-Host "`nALL PASSED" -ForegroundColor Green } else { Write-Host "`n$fail FAILED (see $Work)" -ForegroundColor Red; exit 1 }
