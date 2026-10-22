#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEST_PROJECT="$ROOT/linux/LowTube.Linux.Tests/LowTube.Linux.Tests.csproj"
APP_PROJECT="$ROOT/linux/LowTube.Linux/LowTube.Linux.csproj"

dotnet restore "$TEST_PROJECT" --locked-mode
dotnet build "$APP_PROJECT" --configuration Release --no-restore
dotnet run --project "$TEST_PROJECT" --configuration Release --no-restore
