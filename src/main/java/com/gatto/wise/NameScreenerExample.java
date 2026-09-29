package com.gatto.wise;

import com.gatto.wise.matcher.ExactNameMatcher;
import com.gatto.wise.matcher.NameScreener;
import com.gatto.wise.matcher.SanctionedEntity;
import com.gatto.wise.matcher.ScreeningHit;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NameScreenerExample {

    public static void main(String[] args) {
        // Fictional entries for demonstration.
        List<SanctionedEntity> sanctionList = List.of(
                new SanctionedEntity("demo-1", "Alexander Example", Set.of("Alex Example")),
                new SanctionedEntity("demo-2", "Maria Sample", Set.of("Maria Demo"))
        );
        NameScreener screener = new NameScreener(
                sanctionList, 0.9, List.of(new ExactNameMatcher())
        );

        List<String> customerNames = args.length > 0
                ? List.of(args)
                : List.of("Alexander Example", "Alex Example", "  MARIA-SAMPLE  ", "Unknown Customer");

        for (String customerName : customerNames) {
            System.out.println("Customer: " + customerName);
            List<ScreeningHit> hits = screener.screen(customerName);
            if (hits.isEmpty()) {
                System.out.println("  No matches");
            } else {
                for (ScreeningHit hit : hits) {
                    System.out.printf(Locale.ROOT, "  Match: id=%s, name=%s, score=%.2f%n",
                            hit.entityId(), hit.matchedName(), hit.score());
                }
            }
            System.out.println();
        }
    }
}
