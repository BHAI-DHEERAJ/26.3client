package com.cypherv6.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Holds every CypherV6 module and drives their tick updates/keybinds.
 */
public final class ModuleManager {

    private final List<Module> modules = new ArrayList<>();

    public void register(Module module) {
        modules.add(module);
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getByCategory(Category category) {
        return modules.stream()
                .filter(m -> m.getCategory() == category)
                .sorted(Comparator.comparing(Module::getName))
                .collect(Collectors.toList());
    }

    public List<Module> getEnabledSorted() {
        List<Module> enabled = modules.stream().filter(Module::isEnabled).collect(Collectors.toList());
        enabled.sort(Comparator.comparing(module -> stripFormatting(module.getName())));
        return enabled;
    }

    private static String stripFormatting(String name) {
        return name == null ? "" : name;
    }

    public Module getByName(String name) {
        return modules.stream()
                .filter(m -> m.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public <T extends Module> T get(Class<T> type) {
        return modules.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    public boolean isEnabled(Class<? extends Module> type) {
        Module module = get(type);
        return module != null && module.isEnabled();
    }

    public void onTick() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    module.onTick();
                } catch (Throwable ignored) {
                    // A single broken module must never take the client down.
                }
            }
        }
    }

    /** Toggles any module bound to {@code key}. Returns true if something was toggled. */
    public boolean onKey(int key) {
        if (key < 0) {
            return false;
        }
        boolean toggled = false;
        for (Module module : modules) {
            if (module.getKeybind() == key) {
                module.toggle();
                toggled = true;
            }
        }
        return toggled;
    }
}
