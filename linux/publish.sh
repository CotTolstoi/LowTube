#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_PROJECT="$ROOT/linux/LowTube.Linux/LowTube.Linux.csproj"
PUBLISH_DIR="$ROOT/linux/LowTube.Linux/publish"

dotnet restore "$APP_PROJECT" --locked-mode
dotnet publish "$APP_PROJECT" --configuration Release --no-restore --output "$PUBLISH_DIR"
printf 'Published LowTube for Linux to %s\n' "$PUBLISH_DIR"
