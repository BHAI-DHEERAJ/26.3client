package com.cypherv6.setting;

import java.util.function.BooleanSupplier;

/**
 * Base class for every module setting. A setting holds a value of type {@code T}
 * and can optionally be shown/hidden inside the GUI depending on other settings.
 */
public abstract class Setting<T> {

    private final String name;
    protected T value;
    private BooleanSupplier visibility = () -> true;

    protected Setting(String name, T defaultValue) {
        this.name = name;
        this.value = defaultValue;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public boolean isVisible() {
        return visibility.getAsBoolean();
    }

    public Setting<T> visibleWhen(BooleanSupplier visibility) {
        this.visibility = visibility;
        return this;
    }
}
