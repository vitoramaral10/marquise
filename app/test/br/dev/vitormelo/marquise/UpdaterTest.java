package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.yes;

/** Which release counts as newer. */
final class UpdaterTest {
    private UpdaterTest() {}

    /** Versions compare major, minor and patch numerically, not as plain strings. */
    static void versionsCompareNumberByNumber() {
        yes(Updater.newer("1.10.0", "1.9.2"), "1.10.0 > 1.9.2");
        yes(Updater.newer("2.0.0", "1.99.99"), "2.0.0 > 1.99.99");
        yes(!Updater.newer("1.9.0", "1.10.0"), "1.9.0 < 1.10.0");
    }

    /** Equal versions, or one with a missing part treated as 0, are never "newer". */
    static void theSameVersionIsNotNewer() {
        yes(!Updater.newer("1.9.1", "1.9.1"), "equal");
        yes(!Updater.newer("1.9", "1.9.0"), "a missing part is 0");
    }

    /** A "-beta"-style suffix doesn't change the version comparison. */
    static void aSuffixIsIgnored() {
        yes(Updater.newer("2.0.0-beta.1", "1.9.9"), "2.0.0-beta.1 > 1.9.9");
        yes(!Updater.newer("1.9.1-beta", "1.9.1"), "the suffix doesn't count");
    }
}
