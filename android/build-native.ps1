param(
  [string]$NdkRoot = $env:ANDROID_NDK_HOME,
  [string]$Target = "AZG3"
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$exploitRoot = Join-Path $repoRoot 'exploit'

if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
  $ndkHome = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'
  $NdkRoot = (Get-ChildItem -LiteralPath $ndkHome -Directory |
      Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$compiler = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android35-clang.cmd'
if (-not (Test-Path -LiteralPath $compiler)) {
  throw "Android NDK compiler not found: $compiler"
}

# Target selection. AZG3 builds the original SM-S948N target byte-identically;
# AZF1 builds the SM-S948U S948USQS3AZF1 port.
switch ($Target.ToUpperInvariant()) {
  "AZG3" {
    $payloadDir = 'm3q-BP4A.251205.006'
    $vendorTarget = 'm3q-S948NKSS4AZG3'
    $outDir = 'build\m3q-BP4A.251205.006\bin'
    $helperDefs = @()
  }
  "AZF1" {
    $payloadDir = 'm3q-S948USQS3AZF1'
    $vendorTarget = 'm3q-S948USQS3AZF1'
    $outDir = 'build\m3q-S948USQS3AZF1\bin'
    $helperDefs = @(
      '-DSOCK_PATH=\"/data/local/tmp/temp_su_azf1.sock\"',
      '-DKSU_LOADER_PATH=\"/data/local/tmp/ksud-m3q-S948USQS3AZF1-kdp\"',
      '-DKSU_LOG_PATH=\"/data/local/tmp/m3q-azf1-kernelsu-late-load.log\"',
      '-DM3Q_OVERLAY_ROOT=\"/dev/temp_root_su_azf1\"',
      '-DM3Q_DAEMON_TRACE_PATH=\"/data/local/tmp/m3q-azf1-daemon.trace\"'
    )
  }
  default { throw "Unknown target: $Target (expected AZG3 or AZF1)" }
}

$outputDir = Join-Path $exploitRoot $outDir
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

$helperArgs = @(
  '-O2', '-g0', '-Wall', '-Wextra', '-Werror', '-Isrc', '-fPIE', '-pie'
) + $helperDefs + @(
  'src\su_daemon.c', '-ldl', '-o',
  "$outDir\su_daemon_aarch64_pie.app"
)
$oracleArgs = @(
  '-O2', '-g0', '-Wall', '-Wextra', '-Werror',
  '-Wno-unused-parameter', '-Wno-sign-compare', '-Wno-unused-function',
  '-DAPP_PAYLOAD=1', '-fPIC', '-Ivendor\root-my-galaxy\src',
  "-DTARGET_HEADER=\`"targets/$vendorTarget/target.h\`"",
  'vendor\root-my-galaxy\src\main.c',
  'vendor\root-my-galaxy\src\util.c',
  'vendor\root-my-galaxy\src\slide_app.c',
  'vendor\root-my-galaxy\src\fops.c',
  'vendor\root-my-galaxy\src\pipe.c',
  'vendor\root-my-galaxy\src\root.c',
  'vendor\root-my-galaxy\src\preload.c',
  '-shared', '-pthread', '-o',
  "$outDir\slide_oracle.app.so"
)
$payloadArgs = @(
  '-O2', '-g0', '-Wall', '-Wextra', '-Werror',
  '-Wno-unused-parameter', '-Wno-sign-compare', '-Wno-unused-function',
  '-Isrc', '-fPIC',
  "-DTARGET_CONFIG_H=\`"targets/$payloadDir/target.h\`"",
  "src\targets\$payloadDir\main.c",
  "src\targets\$payloadDir\util.c",
  "src\targets\$payloadDir\slide.c",
  "src\targets\$payloadDir\fops.c",
  "src\targets\$payloadDir\pipe.c",
  'src\faketables.c', 'src\stage3.c',
  "src\targets\$payloadDir\root.c",
  'src\app_preload.c', 'src\stage3_loop.S', 'src\stage3_poll.S',
  '-shared', '-pthread', '-o',
  "$outDir\preload.app.so"
)

Push-Location $exploitRoot
try {
  & $compiler @helperArgs
  if ($LASTEXITCODE -ne 0) { throw "helper build failed: $LASTEXITCODE" }
  & $compiler @oracleArgs
  if ($LASTEXITCODE -ne 0) { throw "oracle build failed: $LASTEXITCODE" }
  & $compiler @payloadArgs
  if ($LASTEXITCODE -ne 0) { throw "payload build failed: $LASTEXITCODE" }

  Get-FileHash -Algorithm SHA256 -LiteralPath @(
    "$outDir\su_daemon_aarch64_pie.app",
    "$outDir\slide_oracle.app.so",
    "$outDir\preload.app.so"
  ) | Format-Table -AutoSize
} finally {
  Pop-Location
}
