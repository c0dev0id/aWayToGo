---
name: debug-crash
description: Read log/crash.log and log/app.log, correlate stack frames with Kotlin source, and identify the root cause of the most recent crash or ANR.
---

Read `log/crash.log` and `log/app.log` in the project root (created by `make log`).

Follow these steps:

1. Find the most recent crash, exception, or ANR in the logs.
2. Extract the full stack trace including the exception type and message.
3. For every frame that belongs to `de.codevoid.aWayToGo`, map the class name to its source file in `app/src/main/java/` and read the lines around the reported line number.
4. State the root cause in one sentence.
5. Show the specific line(s) responsible with their file path and line number.
6. Suggest the minimal fix (code, not prose).

If both log files are empty or absent, say so and remind the user to run `make log-clear` before reproducing the issue and `make log` after.
