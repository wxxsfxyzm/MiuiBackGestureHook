package dev.codex.miuibackgesturehook.hooks.googleapp;

/** Process-local DexKit native library loader shared by the two independent Google hookers. */
final class DexKitLoader {
    private static boolean loaded;

    private DexKitLoader() {
    }

    static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("dexkit");
            loaded = true;
        }
    }
}
