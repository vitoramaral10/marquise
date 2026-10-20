package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;

/** How Jellyfin's titles are shown. */
final class RecsTest {
    private RecsTest() {}

    static void brazilianRatingsLoseTheirPrefix() {
        eq("16", Recs.ageRating("BR-16"));
        eq("L", Recs.ageRating("BR-L"));
        eq("PG-13", Recs.ageRating("PG-13"));
        eq("", Recs.ageRating("null"));
        eq("", Recs.ageRating(null));
    }

    static void aFranchiseIsWhatComesBeforeTheSubtitle() {
        eq("law & order", Recs.franchise("Law & Order: Special Victims Unit"));
        eq("star wars", Recs.franchise("Star Wars - Episode IV"));
        eq("dune", Recs.franchise("Dune"));
        eq("", Recs.franchise(null));
    }
}
