#!/bin/bash
set -euo pipefail
analysis_dir="$(cd "$(dirname "$0")" && pwd)"
exec /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java \
  -Xmx3G -Xshare:off -Djava.awt.headless=true \
  -Djava.system.class.loader=ghidra.GhidraClassLoader \
  -Dfile.encoding=UTF8 -Duser.language=en -Duser.country=US \
  -Dapplication.settingsdir="$analysis_dir/settings" \
  -Dapplication.cachedir="$analysis_dir/cache" \
  -Dapplication.tempdir=/tmp -Djava.io.tmpdir=/tmp \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp /Users/yiweigao/Downloads/ghidra_12.2_PUBLIC/Ghidra/Framework/Utility/lib/Utility.jar \
  ghidra.Ghidra ghidra.app.util.headless.AnalyzeHeadless \
  "$analysis_dir/project" FujiAnalysis \
  -scriptPath "$analysis_dir/scripts" \
  -log "$analysis_dir/output/ghidra.log" \
  -scriptlog "$analysis_dir/output/scripts.log" \
  "$@"
