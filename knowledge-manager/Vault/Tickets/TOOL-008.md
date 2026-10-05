---
id: "TOOL-008"
created: "2026-10-05 13:31:18"
tags:
- "claude-code"
- "subagent"
- "claude-md"
---

# Problem

Welche CLAUDE.md-Dateien sieht ein frisch per Agent-Tool gestarteter Subagent automatisch - die globale, die des Projekts, die der Module? Relevant, sobald Arbeit in einem Modul oder ein Review an einen Subagenten delegiert wird, denn die Modul-CLAUDE.md (modules/name/CLAUDE.md) enthalten eigene Regeln.

# Lösung

Getestet am 2026-10-05 in der Claude-Desktop-App (Code-Tab): Ein general-purpose-Subagent, ohne Tools nur nach seinem Kontext gefragt, hatte die globale ~/.claude/CLAUDE.md (General Coding Instructions) und die Root-CLAUDE.md des Projekts (Stracciatella - Project Instructions) wortgetreu im Kontext, aber KEINE Modul-CLAUDE.md wie modules/bot/CLAUDE.md. NICHT getestet: ob Claude Code eine Modul-CLAUDE.md nachlaedt, sobald der Subagent Dateien in diesem Ordner liest. Konsequenz: Wer Arbeit in einem Modul delegiert, weist den Subagenten ausdruecklich an, die Modul-CLAUDE.md zu lesen. Der globale Review-Skill /review tut das mit der Anweisung read every CLAUDE.md that applies to a changed file and is not already in your context, siehe TOOL-009. Nebenbefunde derselben Session: (1) Der Modell-Alias sonnet im Agent-Tool ergibt claude-sonnet-5-5 (Subagent nach seiner Modell-ID gefragt). (2) Ohne model-Parameter erbt ein Subagent laut Beschreibung des Agent-Tools das Modell der Sitzung, ausser ein Standard-Subagentenmodell ist konfiguriert, dann gilt dieses - wer eine Untergrenze braucht, setzt das Modell explizit. (3) Ein neu angelegtes Verzeichnis unter ~/.claude/skills erschien sofort in der Skill-Liste der laufenden Sitzung, obwohl die Skills-Doku dafuer /reload-skills nennt.
