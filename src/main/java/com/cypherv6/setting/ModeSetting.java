package com.cypherv6.setting;

import java.util.Arrays;
import java.util.List;

public class ModeSetting extends Setting<String> {

    private final List<String> modes;

    public ModeSetting(String name, String defaultMode, String... modes) {
        super(name, defaultMode);
        this.modes = Arrays.asList(modes);
        if (!this.modes.contains(defaultMode)) {
            setValue(this.modes.get(0));
        }
    }

    public List<String> getModes() {
        return modes;
    }

    public String getMode() {
        return value;
    }

    public boolean is(String mode) {
        return value.equalsIgnoreCase(mode);
    }

    public void cycle() {
        int index = modes.indexOf(value);
        setValue(modes.get((index + 1) % modes.size()));
    }

    @Override
    public void setValue(String value) {
        if (modes.contains(value)) {
            super.setValue(value);
        }
    }
}
