package br.dev.vitormelo.marquise;

/**
 * What is said under the poster of something half-watched, or of the next episode of a show being followed. Plain
 * arithmetic and words, so it can be tested without a TV: the language is an argument, not read from the settings.
 */
final class Resume {
    private Resume() {}

    /** Jellyfin counts time in ticks of 100 nanoseconds. */
    private static final long TICKS_PER_MINUTE = 600_000_000L;

    /** Minutes left, rounded up and never under 1; 0 when the length isn't known. */
    static int minutesLeft(long runtimeTicks, long positionTicks) {
        if (runtimeTicks <= 0) return 0;
        long left = Math.max(0, runtimeTicks - Math.max(0, positionTicks));
        return (int) Math.max(1, (left + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE);
    }

    /**
     * "Resume · 42 min" for a movie, "Resume · S2 E4" for an episode being watched, "Next · S2 E5" for the episode after
     * the last one watched. Short on purpose: it is all that fits under a poster. A missing number leaves just the word.
     */
    static String badge(boolean en, boolean next, boolean episode, int season, int episodeNumber, int minutesLeft) {
        String word = next ? (en ? "Next" : "Próximo") : (en ? "Resume" : "Continuar");
        if (episode) {
            return season > 0 && episodeNumber > 0 ? word + " · " + (en ? "S" : "T") + season + " E" + episodeNumber : word;
        }
        return minutesLeft > 0 ? word + " · " + minutesLeft + " min" : word;
    }
}
