# a simple make file to start developer tools

.PHONY: diag diag-texture diag-maxfps diag-3d install launcher restore browser remote

install:
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
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticMaxFpsActivity

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
