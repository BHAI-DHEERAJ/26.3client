package com.cypherv6.gui;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import com.cypherv6.setting.ModeSetting;
import com.cypherv6.setting.NumberSetting;
import com.cypherv6.setting.Setting;
import com.cypherv6.util.KeyUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/**
 * CypherV6 click GUI, opened with RIGHT SHIFT.
 * <ul>
 *     <li>Left click a module to toggle it.</li>
 *     <li>Right click a module to open its settings.</li>
 *     <li>Click sliders / toggles / modes in the settings column to change them.</li>
 * </ul>
 * 26.3 draws through {@link Screen#extractRenderState}.
 */
public class ClickGui extends Screen {

    private static final int PANEL_WIDTH = 470;
    private static final int PANEL_HEIGHT = 290;
    private static final int HEADER_HEIGHT = 22;
    private static final int CATEGORY_WIDTH = 96;
    private static final int MODULE_WIDTH = 158;
    private static final int ROW_HEIGHT = 14;
    private static final int SLIDER_WIDTH = 150;
    private static final int SLIDER_HEIGHT = 6;

    private static final int BG = 0xE6101018;
    private static final int HEADER_BG = 0xFF1B1B2A;
    private static final int ACCENT = 0xFF9B59B6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int ENABLED = 0xFF55FF55;
    private static final int HOVER = 0x33FFFFFF;

    private Category selectedCategory = Category.CLIENT;
    private Module selectedModule;
    private NumberSetting dragging;
    /** Module that is currently waiting for a key press to be bound. */
    private Module binding;

    private int panelX;
    private int panelY;

    public ClickGui() {
        super(Component.literal("CypherV6"));
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int contentY() {
        return panelY + HEADER_HEIGHT + 6;
    }

    private int moduleX() {
        return panelX + CATEGORY_WIDTH + 6;
    }

    private int settingsX() {
        return moduleX() + MODULE_WIDTH + 8;
    }

    private int keybindY() {
        return contentY() + 26;
    }

    private int settingsBaseY() {
        return keybindY() + 13;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        int x = panelX;
        int y = panelY;

        // Frame + panel.
        graphics.fill(x - 1, y - 1, x + PANEL_WIDTH + 1, y + PANEL_HEIGHT + 1, ACCENT);
        graphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, BG);

        // Header.
        graphics.fill(x, y, x + PANEL_WIDTH, y + HEADER_HEIGHT, HEADER_BG);
        graphics.text(this.font, "CypherV6", x + 6, y + 7, ACCENT, true);
        String hint = "right shift to close";
        graphics.text(this.font, hint, x + PANEL_WIDTH - this.font.width(hint) - 6, y + 7, TEXT_DIM, true);

        // Categories.
        int cy = contentY();
        for (Category category : Category.values()) {
            boolean selected = category == selectedCategory;
            boolean hovered = isHover(mouseX, mouseY, x + 4, cy, CATEGORY_WIDTH - 8, ROW_HEIGHT);
            if (selected) {
                graphics.fill(x + 4, cy, x + 4 + CATEGORY_WIDTH - 8, cy + ROW_HEIGHT, ACCENT);
            } else if (hovered) {
                graphics.fill(x + 4, cy, x + 4 + CATEGORY_WIDTH - 8, cy + ROW_HEIGHT, HOVER);
            }
            graphics.text(this.font, category.getDisplayName(), x + 8, cy + 3, selected ? 0xFF000000 : TEXT, true);
            cy += ROW_HEIGHT + 1;
        }

        // Modules.
        int mx = moduleX();
        int my = contentY();
        List<Module> modules = CypherV6.MODULES.getByCategory(selectedCategory);
        for (Module module : modules) {
            boolean hovered = isHover(mouseX, mouseY, mx - 2, my, MODULE_WIDTH, ROW_HEIGHT);
            if (module == selectedModule) {
                graphics.fill(mx - 2, my, mx - 2 + MODULE_WIDTH, my + ROW_HEIGHT, 0x551B1B2A);
            } else if (hovered) {
                graphics.fill(mx - 2, my, mx - 2 + MODULE_WIDTH, my + ROW_HEIGHT, HOVER);
            }
            int colour = module.isEnabled() ? ENABLED : TEXT_DIM;
            String label = (module.isEnabled() ? "+ " : "- ") + module.getName();
            graphics.text(this.font, label, mx, my + 3, colour, true);
            if (module.hasSettings()) {
                String dot = "\u2022";
                graphics.text(this.font, dot, mx + MODULE_WIDTH - 8, my + 3, ACCENT, true);
            }
            my += ROW_HEIGHT;
        }

        // Settings.
        if (selectedModule != null) {
            int sx = settingsX();
            int sy = contentY();
            graphics.text(this.font, selectedModule.getName(), sx, sy, ACCENT, true);
            graphics.text(this.font, selectedModule.getDescription(), sx, sy + 12, TEXT_DIM, true);
            boolean listening = binding == selectedModule;
            String keyLabel = listening ? "Press a key..." : "Keybind: " + KeyUtil.name(selectedModule.getKeybind());
            graphics.text(this.font, keyLabel, sx, keybindY(), listening ? ACCENT : TEXT, true);
            drawSettings(graphics, sx, settingsBaseY(), mouseX, mouseY, false);
        }
    }

    private void drawSettings(GuiGraphicsExtractor graphics, int sx, int sy, int mouseX, int mouseY, boolean forClick) {
        for (Setting<?> setting : selectedModule.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            if (setting instanceof NumberSetting number) {
                graphics.text(this.font, number.getName(), sx, sy, TEXT, true);
                int barX = sx;
                int barY = sy + 11;
                graphics.fill(barX, barY, barX + SLIDER_WIDTH, barY + SLIDER_HEIGHT, 0xFF000000);
                double ratio = (number.get() - number.getMin()) / (number.getMax() - number.getMin());
                graphics.fill(barX, barY, barX + (int) (SLIDER_WIDTH * ratio), barY + SLIDER_HEIGHT, ACCENT);
                graphics.text(this.font, String.format("%.1f", number.get()), barX + SLIDER_WIDTH + 6, sy, TEXT_DIM, true);
                sy += 24;
            } else if (setting instanceof ModeSetting mode) {
                graphics.text(this.font, mode.getName() + ": " + mode.getMode(), sx, sy, TEXT, true);
                sy += 13;
            } else if (setting instanceof BooleanSetting bool) {
                graphics.text(this.font, bool.getName() + ": " + (bool.isEnabled() ? "on" : "off"), sx, sy, TEXT, true);
                sy += 13;
            } else {
                graphics.text(this.font, setting.getName(), sx, sy, TEXT, true);
                sy += 13;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int button = event.button();
        int x = panelX;

        // Categories.
        int cy = contentY();
        for (Category category : Category.values()) {
            if (isHover(mouseX, mouseY, x + 4, cy, CATEGORY_WIDTH - 8, ROW_HEIGHT)) {
                selectedCategory = category;
                return true;
            }
            cy += ROW_HEIGHT + 1;
        }

        // Modules.
        int mx = moduleX();
        int my = contentY();
        for (Module module : CypherV6.MODULES.getByCategory(selectedCategory)) {
            if (isHover(mouseX, mouseY, mx - 2, my, MODULE_WIDTH, ROW_HEIGHT)) {
                if (button == 0) {
                    module.toggle();
                } else if (button == 1) {
                    selectedModule = module == selectedModule ? null : module;
                }
                return true;
            }
            my += ROW_HEIGHT;
        }

        // Module keybind: left click to rebind, right click to clear.
        if (selectedModule != null) {
            int sx = settingsX();
            if (isHover(mouseX, mouseY, sx, keybindY(), SLIDER_WIDTH, 11)) {
                if (button == 1) {
                    selectedModule.setKeybind(KeyUtil.NONE);
                } else {
                    binding = selectedModule;
                }
                return true;
            }
        }

        // Settings.
        if (selectedModule != null && button == 0) {
            int sx = settingsX();
            int sy = settingsBaseY();
            for (Setting<?> setting : selectedModule.getSettings()) {
                if (!setting.isVisible()) {
                    continue;
                }
                if (setting instanceof NumberSetting number) {
                    int barY = sy + 11;
                    if (isHover(mouseX, mouseY, sx, barY, SLIDER_WIDTH, SLIDER_HEIGHT)) {
                        setSlider(number, (mouseX - sx) / (double) SLIDER_WIDTH);
                        dragging = number;
                        return true;
                    }
                    sy += 24;
                } else if (setting instanceof ModeSetting mode) {
                    if (isHover(mouseX, mouseY, sx, sy, SLIDER_WIDTH, 11)) {
                        mode.cycle();
                        return true;
                    }
                    sy += 13;
                } else if (setting instanceof BooleanSetting bool) {
                    if (isHover(mouseX, mouseY, sx, sy, SLIDER_WIDTH, 11)) {
                        bool.toggle();
                        return true;
                    }
                    sy += 13;
                } else {
                    sy += 13;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            setSlider(dragging, (event.x() - settingsX()) / (double) SLIDER_WIDTH);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // While listening for a keybind, swallow the next key press.
        if (binding != null) {
            if (event.isEscape()) {
                binding = null;
            } else {
                binding.setKeybind(event.key());
                binding = null;
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_RSHIFT) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void setSlider(NumberSetting number, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        number.setValue(number.getMin() + clamped * (number.getMax() - number.getMin()));
    }

    private static boolean isHover(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
