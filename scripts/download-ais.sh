#!/usr/bin/env bash
set -euo pipefail
date_value="${1:-2023_01_01}"
if [[ ! "$date_value" =~ ^2023_[0-9]{2}_[0-9]{2}$ ]]; then
  echo 'Usage: scripts/download-ais.sh [2023_MM_DD] [output-directory]' >&2
  exit 2
fi
output="${2:-data}"
url="https://coast.noaa.gov/htdata/CMSP/AISDataHandler/2023/AIS_${date_value}.zip"
mkdir -p "$output"
curl --fail --location --head "$url" >/dev/null
curl --fail --location --retry 3 --output "$output/AIS_${date_value}.zip.part" "$url"
mv "$output/AIS_${date_value}.zip.part" "$output/AIS_${date_value}.zip"
unzip -o "$output/AIS_${date_value}.zip" -d "$output"
