package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.yes;

import java.util.HashSet;
import java.util.Set;

/** The streaming services Marquise opens from a TMDB poster. */
final class TmdbTest {
    private TmdbTest() {}

    /** Every service has a name, at least one TMDB provider id and at least one app package. */
    static void everyServiceHasAnIdAndAnApp() {
        for (Tmdb.Service s : Tmdb.SERVICES) {
            yes(!s.name.isEmpty(), "a name");
            yes(s.ids.length > 0, s.name + ": a TMDB provider id");
            yes(s.pkgs.length > 0, s.name + ": an app");
        }
    }

    /** No TMDB provider id is shared between two services. */
    static void aProviderIdBelongsToOneService() {
        Set<Integer> seen = new HashSet<>();
        for (Tmdb.Service s : Tmdb.SERVICES) for (int id : s.ids) yes(seen.add(id), s.name + ": id " + id + " twice");
    }

    /** A search link's "%s" placeholder for the title appears exactly once, with nothing else percent-encoded. */
    static void searchLinksTakeTheTitleOnce() {
        for (Tmdb.Service s : Tmdb.SERVICES) {
            if (s.search == null) continue;
            yes(s.search.matches("^[a-z]+://[^%]*%s[^%]*$"), s.name + ": " + s.search);
        }
    }
}
