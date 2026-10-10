package com.cypherv6.setting;

public class BooleanSetting extends Setting<Boolean> {

    public BooleanSetting(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    public boolean isEnabled() {
        return value;
    }

    public void toggle() {
        value = !value;
    }
}
