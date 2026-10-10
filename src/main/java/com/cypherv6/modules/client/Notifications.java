package com.cypherv6.modules.client;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;

/**
 * Controls CypherV6's chat feedback (the {@code [CypherV6] ...} messages).
 */
public class Notifications extends Module {

    private final BooleanSetting chat = register(new BooleanSetting("Chat Messages", true));
    private final BooleanSetting toggleMessages = register(new BooleanSetting("Module Toggle", true));

    public Notifications() {
        super("Notifications", Category.CLIENT, "Prints CypherV6 messages to chat");
    }

    public boolean chatEnabled() {
        return chat.isEnabled();
    }

    public boolean toggleMessages() {
        return toggleMessages.isEnabled();
    }
}
