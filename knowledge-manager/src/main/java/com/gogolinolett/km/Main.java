package com.gogolinolett.km;

import java.util.List;

/**
 * Entry point. Without arguments it runs the validation demo; with arguments
 * it acts as a small CLI so agents/scripts can write to the knowledge base
 * through the one code path that keeps Markdown and vector index in sync.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        ObsidianKnowledgeManager manager = new ObsidianKnowledgeManager();
        manager.initializeSystem();

        if (args.length == 0) {
            runDemo(manager);
            return;
        }

        switch (args[0]) {
            case "save" -> {
                requireArgs(args, 5, 5, "save <ticketId> <problem> <lösung> <tag1,tag2,...>");
                manager.saveNewTicket(args[1], args[2], args[3], List.of(args[4].split(",")));
            }
            case "search" -> {
                requireArgs(args, 2, 3, "search <query> [maxResults]");
                int maxResults = args.length > 2 ? Integer.parseInt(args[2]) : 3;
                System.out.println(manager.searchKnowledge(args[1], maxResults));
            }
            case "update" -> {
                requireArgs(args, 3, 3, "update <ticketId> <neues Wissen>");
                manager.updateExistingTicket(args[1], args[2]);
            }
            default -> {
                System.err.println("Unbekannter Befehl: " + args[0]
                        + " (erwartet: save | search | update)");
                System.exit(1);
            }
        }
    }

    private static void runDemo(ObsidianKnowledgeManager manager) throws Exception {
        manager.saveNewTicket(
                "TEST-001",
                "Datenbank Timeout",
                "Connection Pool erhöht",
                List.of("db", "java"));

        String results = manager.searchKnowledge("Verzögerung bei Datenbank", 3);
        System.out.println();
        System.out.println("Suchergebnisse für \"Verzögerung bei Datenbank\":");
        System.out.println(results);
    }

    /**
     * Extra arguments are rejected, not ignored: a quote character inside a value ends
     * its quoting early in Gradle's --args parsing, and the rest of the text would
     * otherwise land in the next argument (save: split into tags) or vanish silently.
     */
    private static void requireArgs(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            System.err.println("Verwendung: " + usage + " - " + (args.length - 1)
                    + " Argumente erhalten; ein Anführungszeichen im Text beendet das Quoting vorzeitig");
            System.exit(1);
        }
    }
}
