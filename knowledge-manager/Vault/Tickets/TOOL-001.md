---
id: "TOOL-001"
created: "2026-10-12 12:23:16"
tags:
- "tooling"
- "gradle"
- "windows"
- "cli"
---

# Problem

Writing tickets via knowledge-manager gradlew.bat fails on Windows: a pipe character in the text aborts with 'Der Befehl xyz ist entweder falsch geschrieben oder konnte nicht gefunden werden', and apostrophes inside the single-quoted --args values break the argument parsing. A double quote inside the text breaks the save as well, even through the escaped-double-quote recipe below: the solution ends mid-sentence, the frontmatter tags fill up with comma-separated fragments of the solution text, and the rest of the text is lost without an error (STR-074: 28 junk tags, 949 characters gone).

# Lösung

Summary (2026-10-12): write from bash through the POSIX wrapper ./knowledge-manager/gradlew, read each value from a file into a shell variable and wrap it in escaped double quotes inside the --args value; never go through gradlew.bat (cmd.exe) and never wrap values in single quotes. The text itself must contain no double quote: Gradle splits the --args string itself and treats every double quote as a quote delimiter, so a quoted single word just loses its quotes, while a quoted phrase containing a space cuts the value apart. Use single quotes for quoted phrases instead - inside escaped double quotes they stay literal - and count the double-quote characters in the payload files before every call. Newlines, apostrophes, parentheses, commas, colons and values over 20 KB arrive byte-identical, so multi-paragraph tickets with Markdown headings work. Main now rejects a wrong argument count (save takes exactly 4 values, update 2, search 1 or 2) with a Verwendung error and exit code 1, which turns the cut-apart case into a loud failure - it cannot see quotes that vanished around a single word.

## Original findings 2026-09-06

Root cause: gradlew.bat runs through cmd.exe, which parses the --args payload before Gradle ever sees it. Metacharacters like the pipe escape the quoting that PowerShell built and are interpreted as shell operators. Working write path: call the POSIX wrapper ./knowledge-manager/gradlew from bash instead of gradlew.bat, and wrap the three values in escaped double quotes rather than single quotes. Verified to preserve apostrophes, parentheses, commas, colons and umlauts in the written Markdown. For bulk writes do not inline the text in the command at all: put each ticket as four consecutive lines (id, problem, solution, tags) in a data file and loop with 'while IFS= read -r id && IFS= read -r problem && ...', passing the shell variables into --args. Values expanded from a variable are not re-tokenised by bash, so backticks, dollar signs and apostrophes in the text become harmless. Two further gotchas from the same session: the Bash tool truncates very long commands, so a 20 KB heredoc silently loses its terminator and fails with 'unexpected EOF' - write large payloads with a file-writing tool instead; and the CLI has no batch mode (one JVM start per ticket, roughly 10 s), so 51 tickets take about 9 minutes and must run sequentially because every save re-serialises store.json.

## Double quotes in the text 2026-10-12

Found while repairing STR-074, whose first save on 2026-09-08 followed the recipe above but had double quotes in the solution text (around Schritt erledigt, Stuetzblock in Reichweite and a test name). The value arrived split exactly at those quotes: args[3] ended with auch Schritt - the quoted section closed after auch and the unquoted word Schritt was glued onto it - and args[4], the tags argument, began with erledigt and ran to the next quote, where Main split it at every comma into 28 tags. Everything after that went into args[5] and later, which Main ignored without a word, so 949 characters never reached the file; the full text was recovered from the session transcript.

Reproduced on 2026-10-12 with the fixed Main: a value with a quoted single word was saved with the quotes stripped and nothing else changed (written as a TEST ticket, file deleted afterwards); a value with a quoted two-word phrase produced one argument too many and was rejected by save and update with exit code 1, nothing written. Single quotes and newlines inside escaped double quotes were verified byte-identical in the 21.6 KB re-save of STR-074.

Main fix: requireArgs takes a minimum and a maximum instead of only a minimum, so extra arguments are rejected instead of ignored; the same fix went into the setup-store-knowledge template. Not tried: escaping the quotes for Gradle - replacing them with single quotes is simpler and checkable with one grep.

Concurrent sessions: each CLI run loads store.json at start and writes it back whole after the save, so two sessions writing at the same moment can lose one vector (read from the code, not observed). After a write, check that the ticketId occurs exactly once in store.json.
