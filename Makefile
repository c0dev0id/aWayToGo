# a simple make file to start developer tools

.PHONY: diag diag-remote install launcher restore browser remote log log-clear

# Dump the device logcat into log/ so Claude can read the files directly.
#
#   log/full.log   — complete logcat buffer (everything)
#   log/crash.log  — lines from FATAL/crash-related tags
#   log/app.log    — lines mentioning our package or PID
#   log/bench.log  — DiagBench per-second fps samples + final RESULT line
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
	grep -E "DiagBench" \
	    log/full.log > log/bench.log || true
	grep -E "RemoteControl" \
	    log/full.log > log/remote.log || true
	@echo "full:   $$(wc -l < log/full.log) lines → log/full.log"
	@echo "crash:  $$(wc -l < log/crash.log) lines → log/crash.log"
	@echo "app:    $$(wc -l < log/app.log) lines → log/app.log"
	@echo "bench:  $$(wc -l < log/bench.log) lines → log/bench.log"
	@echo "remote: $$(wc -l < log/remote.log) lines → log/remote.log"

# Clear the on-device log ring buffer so the next `make log` only shows
# entries from the current run.
log-clear:
	adb logcat -c
	@echo "logcat buffer cleared"

install:
	RUN_ID=$$(gh run list --repo c0dev0id/aWayToGo --workflow build.yml \
	          --status success --limit 1 --json databaseId --jq '.[0].databaseId') && \
	gh run download $$RUN_ID --repo c0dev0id/aWayToGo --name app-signed --dir /tmp/aWayToGo-install && \
	adb install -r /tmp/aWayToGo-install/app-signed.apk && \
	rm -rf /tmp/aWayToGo-install && \
	adb shell am start -n de.codevoid.aWayToGo/.map.MapActivity

diag:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticActivity

diag-remote:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticRemoteActivity

launcher:
	adb shell cmd package set-home-activity de.codevoid.aWayToGo/.map.MapActivity

restore:
	adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher

browser:
	adb shell am start -n org.mozilla.firefox/.App

remote:
	adb shell am broadcast -a de.codevoid.andremote2.OVERLAY_TOGGLE -p de.codevoid.andremote2

mc:
	adb shell am start -n com.ghisler.android.TotalCommander/.TotalCommander

snd:
	adb shell am start -n de.codevoid.androsnd/.MainActivity

photos:
	adb shell am start -n com.google.android.apps.photos/.home.HomeActivity

dmd:
	adb shell am start -n com.thorkracing.dmd2launcher/.Main
