package br.dev.vitormelo.marquise;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * The unit tests, run on the computer's JVM by build.sh (no device, no test library). Only plain logic: Android's
 * classes are stubs here, so a test never reaches code that touches them (Ui, Build, JSON, the views). Each suite is
 * a class of static, no-argument methods; every one runs, and any failure fails the build.
 */
public final class Tests {
    private Tests() {}

    private static final Class<?>[] SUITES = {
        HomeModeTest.class, TvReportTest.class, TmdbTest.class, RecsTest.class, JellyfinTest.class, UpdaterTest.class,
    };

    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void yes(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws IllegalAccessException {
        int passed = 0, failed = 0;
        for (Class<?> suite : SUITES) {
            Method[] tests = suite.getDeclaredMethods();
            Arrays.sort(tests, Comparator.comparing(Method::getName));
            for (Method m : tests) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0 || m.isSynthetic()) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(null);
                    passed++;
                } catch (InvocationTargetException e) {
                    failed++;
                    Throwable why = e.getCause();
                    System.out.println("FAIL " + suite.getSimpleName() + "." + m.getName() + ": " + why);
                    if (!(why instanceof AssertionError)) why.printStackTrace(System.out);
                }
            }
        }
        System.out.println("Tests: " + passed + " passed" + (failed > 0 ? ", " + failed + " failed" : ""));
        System.exit(failed > 0 ? 1 : 0);
    }
}
