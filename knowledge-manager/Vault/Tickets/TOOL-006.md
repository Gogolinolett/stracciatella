---
id: "TOOL-006"
created: "2026-09-10 19:49:48"
tags:
- "knowledge-manager"
- "embedding"
- "suche"
- "vault"
---

# Problem

Ein langes Vault-Ticket ist per search NICHT FINDBAR, obwohl die Datei existiert und genau ein Eintrag dafuer in store.json steht. Konkret: STR-087 (4,6 KB) tauchte in keiner von vier verschiedenen Suchformulierungen auf, auch nicht bei woertlichen Begriffen aus seinem Text, waehrend fuenf kuerzere Tickets derselben Sitzung sauber rankten. Nach Ticket-ID ist es da, semantisch ist es unsichtbar.

# Lösung

URSACHE: ObsidianKnowledgeManager.reindexTicket bettet den GESAMTEN Ticket-Body als EIN TextSegment ein (TextSegment.from(text) ohne Chunking), und das Modell AllMiniLmL6V2QuantizedEmbeddingModel (all-MiniLM-L6-v2) hat eine maximale Sequenzlaenge von 256 Tokens, also rund 200 Woerter. Alles danach fliesst NICHT in den Vektor ein. Ein Ticket, dessen Unterscheidungsmerkmale hinten stehen, ist damit nur noch ueber seine ID erreichbar. Fuer lange Tickets verschiebt sich der Vektor ausserdem in den Mittelwert der ersten Absaetze und verliert gegen fokussierte Tickets bei jeder Einzelfrage. FALSIFIZIERT STATT GEGLAUBT: STR-087 wurde mit identischem Inhalt, aber mit den Suchbegriffen in den ERSTEN rund 200 Woertern (Problemtext nennt Restock, Nachschub, Kiste, Werkzeug, Inventar voll, unterbrechen, fortsetzen; der Loesungstext beginnt mit einer Entscheidungs-Zusammenfassung, die Details folgen erst danach) neu gespeichert. Dieselbe Suche, die es vorher nicht fand (kein korrektes Werkzeug Manifest Inventar voll unterbrechen), liefert es danach auf Platz 1 mit Score 0,6754. Geaendert wurde NUR die Reihenfolge innerhalb des Tickets, nicht der Inhalt und nicht der Index-Code. SOFORT ANWENDBARE REGEL FUERS SCHREIBEN: die ersten rund 200 Woerter eines Tickets sind der Suchhaken, der Rest ist zum Lesen. Also: Problemtext nennt die Begriffe, unter denen man das Ticket spaeter suchen wuerde; der Loesungstext beginnt mit der Entscheidung oder der Ursache in drei bis fuenf Saetzen, und die Begruendung samt verworfener Alternativen kommt DANACH. Das widerspricht nicht dem Dokumentations-Standard aus STR-001 - die Begruendung bleibt vollstaendig drin, sie steht nur nicht vorne. WICHTIG ZU update: ein Update haengt hinten an und kann die Findbarkeit eines langen Tickets deshalb NICHT reparieren; der angehaengte Text liegt jenseits der Abschneidegrenze. Wer ein langes Ticket findbar machen will, muss es per save auf dieselbe ID neu schreiben - saveNewTicket benutzt Files.writeString (ueberschreibt) und reindexTicket entfernt den alten Vektor per store.removeAll vorher, ein erneutes save auf eine bestehende ID ist also der saubere Weg und kein Duplikat. BESTEHENDER BESTAND IST BETROFFEN: STR-076 hat vier Update-Runden und ist weit ueber der Grenze, ebenso mehrere andere lange Tickets - ihre spaeteren Runden sind fuer die Suche nicht vorhanden. NICHT BEHOBEN, bewusst: eine echte Loesung waere Chunking im Manager (Ticket in mehrere Segmente mit derselben ticketId als Metadaten aufteilen, Treffer danach deduplizieren). Das ist eine Aenderung am Knowledge-Manager selbst, nicht an der Wissensbasis, und wurde hier nur festgestellt, nicht umgesetzt.
