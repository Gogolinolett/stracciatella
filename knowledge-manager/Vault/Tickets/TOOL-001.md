---
id: "TOOL-001"
created: "2026-09-06 15:11:58"
tags:
- "tooling"
- "gradle"
- "windows"
- "cli"
---

# Problem

Writing tickets via knowledge-manager gradlew.bat fails on Windows: a pipe character in the text aborts with 'Der Befehl xyz ist entweder falsch geschrieben oder konnte nicht gefunden werden', and apostrophes inside the single-quoted --args values break the argument parsing.

# Lösung

Root cause: gradlew.bat runs through cmd.exe, which parses the --args payload before Gradle ever sees it. Metacharacters like the pipe escape the quoting that PowerShell built and are interpreted as shell operators. Working write path: call the POSIX wrapper ./knowledge-manager/gradlew from bash instead of gradlew.bat, and wrap the three values in escaped double quotes rather than single quotes. Verified to preserve apostrophes, parentheses, commas, colons and umlauts in the written Markdown. For bulk writes do not inline the text in the command at all: put each ticket as four consecutive lines (id, problem, solution, tags) in a data file and loop with 'while IFS= read -r id && IFS= read -r problem && ...', passing the shell variables into --args. Values expanded from a variable are not re-tokenised by bash, so backticks, dollar signs and apostrophes in the text become harmless. Two further gotchas from the same session: the Bash tool truncates very long commands, so a 20 KB heredoc silently loses its terminator and fails with 'unexpected EOF' - write large payloads with a file-writing tool instead; and the CLI has no batch mode (one JVM start per ticket, roughly 10 s), so 51 tickets take about 9 minutes and must run sequentially because every save re-serialises store.json.
