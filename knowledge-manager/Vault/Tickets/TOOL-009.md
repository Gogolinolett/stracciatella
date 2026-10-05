---
id: "TOOL-009"
created: "2026-10-05 13:31:36"
tags:
- "claude-code"
- "skill"
- "review"
- "design-entscheidung"
---

# Problem

Ein globaler Code-Review-Skill fuer Claude Code soll Aenderungen pruefen, meist Code, den Claude in derselben Sitzung selbst geschrieben hat. Wer den Code geschrieben hat, uebersieht beim Review dieselben Fehler wieder, ein Reviewer ohne das Gespraech kennt dagegen die Absicht nicht. Wie ist der Skill /review aufgebaut, und warum so?

# Lösung

Entscheidung vom 2026-10-05, Datei ~/.claude/skills/review/SKILL.md, liegt bewusst nicht im Repo und gilt global. HYBRID: Der Hauptagent reviewt nicht selbst. Er bestimmt nur den Scope (Default alle uncommitteten Aenderungen inklusive untracked, sonst der letzte Commit) und die Absicht: die Anforderungen des Nutzers woertlich zitiert, Korrekturen eingeschlossen, nie aus dem Code abgelesen, sonst unknown - eine aus dem Code gelesene Absicht laesst jeden Bug gewollt aussehen. Dann reicht er einen Reviewer-Brief aus derselben Datei woertlich an einen frischen general-purpose-Subagenten weiter, gibt dessen Bericht unveraendert wieder und fixt erst, wenn der Nutzer Befunde auswaehlt. ALTERNATIVEN: (a) context: fork im Frontmatter - pro eine Datei, am einfachsten; contra der Subagent sieht das Gespraech nicht und prueft uncommittete Aenderungen gegen eine geratene Absicht - verworfen. (b) Review im laufenden Chat - pro kennt die Absicht und kann nachfragen; contra Autor-Blindheit, und der Hauptkontext laeuft voll - verworfen. (c) Zwei Dateien, der Subagent liest reviewer.md selbst - verworfen, weil er dann ausserhalb des Projekts lesen muesste und die woertliche Uebergabe aus einer Datei einfacher ist. MODELL explizit gesetzt: fable oder opus, wenn die Sitzung in dieser Familie laeuft, sonst sonnet. Vorgabe des Nutzers: nie schwaecher als Sonnet 5.5, und ohne Angabe koennte ein konfiguriertes Standard-Subagentenmodell greifen (siehe TOOL-008). Fest sonnet waere billiger, aber in einer Opus-Sitzung waere der Reviewer dann schwaecher als der Autor. NAME review statt code-review: Ein eigener Skill verdeckt laut Doku einen mitgelieferten gleichen Namens, und den eingebauten /code-review mit ultra und PR-Kommentaren will der Nutzer behalten; die Beschreibung sagt deshalb, bei Review-Wunsch diesen Skill vorzuziehen. Ausloeser nur auf Anfrage, nicht proaktiv vor der Fertigmeldung (Nutzerwahl). Reviewer nur lesend, keine Builds oder Tests, weil ein Lauf mit einem laufenden kollidieren kann (TOOL-002). PRUEFKATEGORIEN: Bug, Ripple (Aufrufer, Kommentare, Doku und gespeicherte Daten ausserhalb des Diffs), Same bug, Workaround, Weakened test, Hollow test (aus STR-068: ein Test muss ohne die Aenderung fehlschlagen, Sollwerte nicht aus dem Ist-Zustand), Performance, Unneeded, Rule (CLAUDE.md-Regeln). Jeder Befund braucht ein konkretes Fehlerszenario, sonst wird er Frage oder faellt weg; No findings ist ausdruecklich erlaubt, weil Modelle sonst Befunde erfinden, um gruendlich zu wirken. WEGGELASSEN: disable-model-invocation (dann ginge nur noch der Slash-Befehl), Befunde der Art kein Test deckt das ab (kaemen bei fast jeder Aenderung und haben kein Fehlerszenario), mehrere parallele Reviewer (kann der eingebaute /code-review), das Findings-Panel der App (kein Feld fuer den Fix-Vorschlag, nicht in jeder Umgebung).
