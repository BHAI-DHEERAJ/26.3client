package com.cypherv6.setting;

public class NumberSetting extends Setting<Double> {

    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String name, double defaultValue, double min, double max, double step) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public double get() {
        return value;
    }

    public float getFloat() {
        return value.floatValue();
    }

    public int getInt() {
        return (int) Math.round(value);
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    @Override
    public void setValue(Double value) {
        double clamped = Math.max(min, Math.min(max, value));
        if (step > 0.0) {
            clamped = Math.round(clamped / step) * step;
        }
        super.setValue(clamped);
    }

    public void increase() {
        setValue(get() + step);
    }

    public void decrease() {
        setValue(get() - step);
    }
}
