package com.cypherv6.module;

/**
 * Categories shown as tabs / groups in the CypherV6 click GUI.
 */
public enum Category {
    COMBAT("Combat"),
    CLIENT("Client"),
    RENDER("Render"),
    PLAYER("Player"),
    MOVEMENT("Movement"),
    MISC("Misc");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
