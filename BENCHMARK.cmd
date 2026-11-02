@echo off
cd /d "%~dp0"
if not exist "LowTube.exe" (
  echo Copy this tester into the LowTube 0.9.1 folder first.
  pause
  exit /b 1
)
if not exist "LowTube.Benchmark.exe" (
  echo Missing LowTube.Benchmark.exe. Extract the complete benchmark patch.
  pause
  exit /b 1
)
"%~dp0LowTube.Benchmark.exe"
