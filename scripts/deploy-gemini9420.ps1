param(
    [ValidateSet("gemini", "adapter", "adapter-install", "all")]
    [string]$Mode = "gemini",
    [string]$Device = "192.168.2.224:5555"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$bash = "wsl"
$script = "$Root/scripts/deploy-gemini9420.sh"
if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
    Write-Error "需要 WSL 执行构建与部署: $script"
}
& $bash bash -lc "export DEVICE='$Device'; bash '$($script -replace '\\','/')' '$Mode'"
