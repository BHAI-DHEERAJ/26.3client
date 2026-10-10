package com.cypherv6.module;

import com.cypherv6.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for a CypherV6 module. Modules can be toggled from the click GUI
 * (opened with RIGHT SHIFT) or with their optional keybind.
 */
public abstract class Module {

    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();

    private boolean enabled;
    private int keybind = -1; // -1 == unbound (InputConstants.UNKNOWN)

    /** Notified whenever a module is toggled; the client wires this to its chat output. */
    public interface ToggleListener {
        void onToggle(Module module, boolean enabled);
    }

    private static ToggleListener toggleListener = (module, enabled) -> {
    };

    public static void setToggleListener(ToggleListener listener) {
        toggleListener = listener != null ? listener : (module, enabled) -> {
        };
    }

    protected Module(String name, Category category, String description) {
        this.name = name;
        this.category = category;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
        toggleListener.onToggle(this, enabled);
    }

    public int getKeybind() {
        return keybind;
    }

    public Module setKeybind(int keybind) {
        this.keybind = keybind;
        return this;
    }

    protected <T extends Setting<?>> T register(T setting) {
        settings.add(setting);
        return setting;
    }

    public List<Setting<?>> getSettings() {
        return settings;
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    /** Called once when the module is enabled. */
    protected void onEnable() {
    }

    /** Called once when the module is disabled. */
    protected void onDisable() {
    }

    /** Called every client tick while the module is enabled. */
    public void onTick() {
    }
}
