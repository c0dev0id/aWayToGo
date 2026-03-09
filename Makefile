# a simple make file to start developer tools

COMMIT := $(shell git rev-parse --short HEAD)

.PHONY: diag install

diag:
	adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticActivity

install:
	wget https://github.com/c0dev0id/aWayToGo/releases/download/dev/aWayToGo-dev-$(COMMIT).apk
	adb install -r aWayToGo-dev-$(COMMIT).apk
	rm -f aWayToGo-dev-$(COMMIT).apk
	adb shell am start -n de.codevoid.aWayToGo/.map.MapActivity

