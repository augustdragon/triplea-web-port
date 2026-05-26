<#
.SYNOPSIS
  Restart the TripleA web-port playable game for manual testing.

.DESCRIPTION
  Stops any game server on the chosen port, makes sure the Vite client is running, then launches a
  fresh game in the FOREGROUND (so you see the engine log and can stop it with Ctrl+C). After it
  starts, refresh the browser at http://localhost:<port-of-vite> — the WebSocket reconnects and the
  client re-syncs to the new game.

  Restart loop while testing:  Ctrl+C this script  ->  run it again  ->  refresh the browser.

.PARAMETER Player
  Which seat you play (the human). e.g. Japanese, Americans, British, ANZAC, Chinese. Default: Japanese.

.PARAMETER Port
  WebSocket port the game server listens on. Default: 8080.

.PARAMETER MaxRounds
  Round cap before the game auto-ends. Default: 20.

.PARAMETER StepDelayMs
  Delay between AI steps (ms) — lower = snappier AI turns. Default: 150.

.PARAMETER GameXml
  Path to the game XML. Defaults to the local 2nd-edition Pacific map. NOTE: gradle's --args splits
  on spaces, so the path must not contain spaces.

.PARAMETER SkipClient
  Don't auto-start the Vite client even if it isn't running.

.EXAMPLE
  .\scripts\restart-web.ps1
  .\scripts\restart-web.ps1 -Player Americans -MaxRounds 10
#>
param(
  [string]$Player = "Japanese",
  [int]$Port = 8080,
  [int]$MaxRounds = 20,
  [int]$StepDelayMs = 150,
  [string]$GameXml,
  [switch]$SkipClient
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# --- Resolve JAVA_HOME (prefer an already-set valid one; else the known Temurin 21 install). ---
function Resolve-JavaHome {
  foreach ($candidate in @(
      $env:JAVA_HOME,
      [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'),
      'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot')) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) { return $candidate }
  }
  return $null
}
$javaHome = Resolve-JavaHome
if (-not $javaHome) {
  Write-Error "No JDK 21 found. Set JAVA_HOME to a JDK 21 install and retry."
  exit 1
}
$env:JAVA_HOME = $javaHome

# --- Resolve the game XML (parameter, else the first default that exists). ---
if (-not $GameXml) {
  $defaults = @(
    'C:\Users\ndhay\triplea-webport-work\world_war_ii_pacific\map\games\ww2pac40_2nd_edition.xml'
  )
  $GameXml = $defaults | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $GameXml -or -not (Test-Path $GameXml)) {
  Write-Error "Game XML not found. Pass -GameXml <path-without-spaces>."
  exit 1
}
if ($GameXml -match '\s') {
  Write-Warning "Game XML path contains spaces; gradle --args may split it. Use a space-free path."
}

# --- Stop any game server already on the port. ---
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
  Select-Object -First 1 -ExpandProperty OwningProcess
if ($existing) {
  Stop-Process -Id $existing -Force
  Write-Host "Stopped running game server (PID $existing) on port $Port." -ForegroundColor Yellow
}

# --- Make sure the Vite client is up (the browser needs it), unless told to skip. ---
if (-not $SkipClient) {
  $viteUp = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue
  if (-not $viteUp) {
    Write-Host "Starting the Vite client in a new window (port 5173)..." -ForegroundColor Cyan
    Start-Process powershell -ArgumentList @(
      '-NoExit', '-Command', "Set-Location '$repoRoot'; npm --prefix web-client run dev")
  }
}

Write-Host ""
Write-Host "Launching fresh game:" -ForegroundColor Green
Write-Host "  seat=$Player  port=$Port  maxRounds=$MaxRounds  stepDelayMs=$StepDelayMs"
Write-Host "  map=$GameXml"
Write-Host "  JAVA_HOME=$env:JAVA_HOME"
Write-Host ""
Write-Host "When it boots, open/refresh  http://localhost:5173/  . Ctrl+C here to stop." -ForegroundColor Green
Write-Host ""

# --- Run in the foreground so logs are visible and Ctrl+C stops the game. ---
$gameArgs = "$GameXml $Player $Port $MaxRounds $StepDelayMs"
& "$repoRoot\gradlew.bat" ':game-web-server:runPlayable' "--args=$gameArgs" '--console=plain'
