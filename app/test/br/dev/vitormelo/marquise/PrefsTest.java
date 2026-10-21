package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;

/** What the preferences decide where no Context is at hand: the temperature unit, and the assistant in the switch. */
final class PrefsTest {
    private PrefsTest() {}

    /** C and F force the unit; the value is read at the time of the call, so a change shows at once. */
    static void theTemperatureFollowsThePreference() {
        String before = Prefs.temperature;
        try {
            Prefs.temperature = "F";
            eq("68\u00b0", HomeData.degrees(20));
            Prefs.temperature = "C";
            eq("20\u00b0", HomeData.degrees(20));
            Prefs.temperature = " f ";
            eq("212\u00b0", HomeData.degrees(100));
        } finally {
            Prefs.temperature = before;
        }
    }

    /** No reading at all is a dash, in either unit. */
    static void noTemperatureIsADash() {
        eq("\u2014", HomeData.degrees(Double.NaN));
    }
}
