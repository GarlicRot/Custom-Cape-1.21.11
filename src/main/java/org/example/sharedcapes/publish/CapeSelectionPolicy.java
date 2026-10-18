package org.example.sharedcapes.publish;

/**
 * Automatic cape-sharing policy. A selected cape is public to users of this plugin
 * while RusherHack's Capes module is active. No separate sharing toggle exists.
 */
public final class CapeSelectionPolicy {
    private CapeSelectionPolicy() { }

    public static boolean shouldPublish(boolean capesModuleEnabled, String selectedCape) {
        return capesModuleEnabled
                && selectedCape != null
                && !selectedCape.isBlank()
                && !"None".equals(selectedCape);
    }
}
