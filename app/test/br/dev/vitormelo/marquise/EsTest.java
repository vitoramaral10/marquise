package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

/** The Spanish table: found by the English text, and English when there is none. */
final class EsTest {
    private EsTest() {}

    /** A text with a line comes back in Spanish. */
    static void aTextIsFoundByItsEnglish() {
        eq("Cancelar", Es.of("Cancel"));
        eq("Buenos días", Es.of("Good morning"));
        eq("Mantén OK en una app para moverla, quitarla o desinstalarla.", Es.of("Hold OK on an app to move, remove or uninstall it."));
    }

    /** Fragments with a space at the edge keep it: they are glued to a name or a number. */
    static void fragmentsKeepTheirSpaces() {
        eq("Descargando ", Es.of("Downloading "));
        eq(" episodios nuevos", Es.of(" new episodes"));
        eq("en ", Es.of("on "));
    }

    /** A text with no line is returned as it came: English, never nothing. */
    static void anUnknownTextStaysEnglish() {
        eq("No such text in the table", Es.of("No such text in the table"));
        eq("", Es.of(""));
    }

    /** The table has the texts the app has (182 when it was made); a big drop means it was damaged. */
    static void theTableIsWhole() {
        yes(Es.size() >= 180, "size " + Es.size());
    }

    /** The line breaks the setup screen counts on are real breaks in Spanish too. */
    static void lineBreaksSurvive() {
        String es = Es.of("One TV setting lets Marquise switch the Home screen:\n1. In About, press “Android TV OS build” 7 times.\n2. In Developer options, turn on “USB debugging”.\nThen come back here.");
        eq(3, es.length() - es.replace("\n", "").length());
    }
}
