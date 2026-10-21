package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;

/** The "continue watching" lines under a poster. */
final class ResumeTest {
    private ResumeTest() {}

    private static final long MINUTE = 600_000_000L;

    /** Minutes left are rounded up, and never under one while there is anything left to watch. */
    static void minutesLeftRoundUp() {
        eq(42, Resume.minutesLeft(100 * MINUTE, 58 * MINUTE));
        eq(43, Resume.minutesLeft(100 * MINUTE, 57 * MINUTE + 1));
        eq(1, Resume.minutesLeft(100 * MINUTE, 100 * MINUTE - 5));
        eq(1, Resume.minutesLeft(100 * MINUTE, 100 * MINUTE));
    }

    /** No length or a position past the end or before the start doesn't break it. */
    static void oddValuesAreSafe() {
        eq(0, Resume.minutesLeft(0, 5 * MINUTE));
        eq(0, Resume.minutesLeft(-1, 0));
        eq(100, Resume.minutesLeft(100 * MINUTE, -3 * MINUTE));
        eq(1, Resume.minutesLeft(100 * MINUTE, 500 * MINUTE));
    }

    /** A movie says the minutes left. */
    static void aMovieSaysHowLongIsLeft() {
        eq("Resume · 42 min", Resume.badge(true, false, false, 0, 0, 42));
        eq("Continuar · 42 min", Resume.badge(false, false, false, 0, 0, 42));
        eq("Resume", Resume.badge(true, false, false, 0, 0, 0));
    }

    /** An episode says which one: S2 E4 in English, T2 E4 in Portuguese. */
    static void anEpisodeSaysWhichOne() {
        eq("Resume · S2 E4", Resume.badge(true, false, true, 2, 4, 30));
        eq("Continuar · T2 E4", Resume.badge(false, false, true, 2, 4, 30));
    }

    /** The next episode of a show being followed says so. */
    static void theNextEpisode() {
        eq("Next · S2 E5", Resume.badge(true, true, true, 2, 5, 0));
        eq("Próximo · T2 E5", Resume.badge(false, true, true, 2, 5, 0));
    }

    /** Without the numbers (specials have none), only the word. */
    static void withoutNumbersOnlyTheWord() {
        eq("Next", Resume.badge(true, true, true, 0, 0, 0));
        eq("Próximo", Resume.badge(false, true, true, 2, 0, 0));
        eq("Continuar", Resume.badge(false, false, true, 0, 3, 0));
    }
}
