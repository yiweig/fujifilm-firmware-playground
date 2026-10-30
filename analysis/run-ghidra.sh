#!/bin/bash
set -euo pipefail
analysis_dir="$(cd "$(dirname "$0")" && pwd)"
ghidra_install_dir="${GHIDRA_INSTALL_DIR:-/Users/yiweigao/Downloads/ghidra_12.1.4_PUBLIC}"
project_dir="${GHIDRA_PROJECT_DIR:-$analysis_dir/project}"
project_name="${GHIDRA_PROJECT_NAME:-FujiAnalysis121}"
exec /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java \
  -Xmx3G -Xshare:off -Djava.awt.headless=true \
  -Djava.system.class.loader=ghidra.GhidraClassLoader \
  -Dfile.encoding=UTF8 -Duser.language=en -Duser.country=US \
  -Dapplication.settingsdir="$analysis_dir/settings" \
  -Dapplication.cachedir="$analysis_dir/cache" \
  -Dapplication.tempdir=/tmp -Djava.io.tmpdir=/tmp \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "$ghidra_install_dir/Ghidra/Framework/Utility/lib/Utility.jar" \
  ghidra.Ghidra ghidra.app.util.headless.AnalyzeHeadless \
  "$project_dir" "$project_name" \
  -scriptPath "$analysis_dir/scripts" \
  -log "$analysis_dir/output/ghidra.log" \
  -scriptlog "$analysis_dir/output/scripts.log" \
  "$@"
