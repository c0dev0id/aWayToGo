# a simple make file to start developer tools

# diag-maxfps / diag-bench tunables — override on the command line:
#   make diag-maxfps MAXFPS=45
#   make diag-maxfps MAXFPS=0 PREFETCH=2   (0 = unlimited fps)
#   make diag-maxfps ZOOM=15.0
#   make diag-bench                         (10s benchmark, default params)
#   make diag-bench MAXFPS=60 ZOOM=14.0
MAXFPS   ?= 30
PREFETCH ?= 4
ZOOM     ?= 12.0
BENCH    ?= false

.PHONY: diag diag-texture diag-maxfps diag-bench diag-3d install launcher restore browser remote log log-clear

# Dump the device logcat into log/ so Claude can read the files directly.
#
#   log/full.log   — complete logcat buffer (everything)
#   log/crash.log  — lines from FATAL/crash-related tags
#   log/app.log    — lines mentioning our package or PID
#
# Run `make log-clear` before reproducing a bug to flush stale entries,
# then `make log` after the crash to capture only the relevant session.
log:
	mkdir -p log
	adb logcat -d -v threadtime \
	    > log/full.log 2>&1 || true
	grep -E "beginning of crash|FATAL|AndroidRuntime|DEBUG|crash_dump|libc|abort" \
	    log/full.log > log/crash.log || true
	grep -E "de\.codevoid\.aWayToGo|aWayToGo" \
	    log/full.log > log/app.log || true
	@echo "full:  $$(wc -l < log/full.log) lines → log/full.log"
	@echo "crash: $$(wc -l < log/crash.log) lines → log/crash.log"
	@echo "app:   $$(wc -l < log/app.log) lines → log/app.log"

# Clear the on-device log ring buffer so the next `make log` only shows
# entries from the current run.
log-clear:
	adb logcat -c
	@echo "logcat buffer cleared"

install: log-clear
	RUN_ID=$$(gh run list --repo c0dev0id/aWayToGo --workflow build.yml \
	          --status success --limit 1 --json databaseId --jq '.[0].databaseId') && \
	gh run download $$RUN_ID --repo c0dev0id/aWayToGo --name app-signed --dir /tmp/aWayToGo-install && \
	adb install -r /tmp/aWayToGo-install/app-signed.apk && \
	rm -rf /tmp/aWayToGo-install && \
	adb shell am start -n de.codevoid.aWayToGo/.map.MapActivity

diag:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticActivity

diag-texture:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticTextureActivity

diag-maxfps:
	adb shell am start -S \
	    -n de.codevoid.aWayToGo/.diagnostic.DiagnosticMaxFpsActivity \
	    --ei maxFps $(MAXFPS) --ei prefetchDelta $(PREFETCH) \
	    --ed zoom $(ZOOM) \
	    --ez bench $(BENCH)

# 10-second benchmark: pan right, record frames + load time, show summary.
# Override any tunable on the command line, e.g.:
#   make diag-bench MAXFPS=60 ZOOM=14.0 PREFETCH=2
diag-bench:
	$(MAKE) diag-maxfps BENCH=true MAXFPS=$(MAXFPS) PREFETCH=$(PREFETCH) ZOOM=$(ZOOM)

diag-3d:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.Diagnostic3dStyleActivity

launcher:
	adb shell cmd package set-home-activity de.codevoid.aWayToGo/.map.MapActivity

restore:
	adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher

browser:
	adb shell am start -n org.mozilla.firefox/.App

remote:
	adb shell am broadcast -a de.codevoid.andremote2.OVERLAY_TOGGLE

mc:
	adb shell am start -n com.ghisler.android.TotalCommander/.TotalCommander

snd:
	adb shell am start -n de.codevoid.androsnd/.MainActivity

photos:
	adb shell am start -n com.google.android.apps.photos/.home.HomeActivity

dmd:
	adb shell am start -n com.thorkracing.dmd2launcher/.Main
