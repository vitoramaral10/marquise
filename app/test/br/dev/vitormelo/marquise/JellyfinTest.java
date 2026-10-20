package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;

/** Addresses typed with the remote, and Jellyfin's client header. */
final class JellyfinTest {
    private JellyfinTest() {}

    static void aBareAddressGetsHttpAndJellyfinsPort() {
        eq("http://192.168.1.10:8096", Jellyfin.normalize(" 192.168.1.10 "));
        eq("http://jellyfin.local:8096", Jellyfin.normalize("jellyfin.local/"));
    }

    static void aTypedSchemePortOrPathIsKept() {
        eq("https://jf.example.com", Jellyfin.normalize("https://jf.example.com/"));
        eq("http://jf.local:8920", Jellyfin.normalize("jf.local:8920"));
        eq("http://host/jellyfin", Jellyfin.normalize("host/jellyfin"));
        eq("", Jellyfin.normalize("   "));
    }

    static void theHeaderCarriesTheTokenOnlyWhenSignedIn() {
        eq("MediaBrowser Client=\"Marquise\", Device=\"TV\", DeviceId=\"marquise\", Version=\"" + Config.VERSION + "\"",
                Jellyfin.authorization(null, null));
        eq("MediaBrowser Client=\"Marquise\", Device=\"TV\", DeviceId=\"d1\", Version=\"" + Config.VERSION
                + "\", Token=\"t1\"", Jellyfin.authorization("d1", "t1"));
    }
}
