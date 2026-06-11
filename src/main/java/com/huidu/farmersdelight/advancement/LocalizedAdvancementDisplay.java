package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Advancement display whose title/description are resource-pack translation keys rather than baked
 * server-side strings, so each client renders them in its own language. Only the (non-NMS) component
 * getters are overridden; the patched UltimateAdvancementAPI reads them for the GUI, toast and chat.
 */
public class LocalizedAdvancementDisplay extends AdvancementDisplay {

    private final String titleKey;
    private final String descriptionKey;

    public LocalizedAdvancementDisplay(@NotNull ItemStack icon, @NotNull String titleKey, @NotNull String descriptionKey,
                                       @NotNull AdvancementFrameType frame, boolean showToast, boolean announceChat,
                                       float x, float y) {
        super(icon, titleKey, frame, showToast, announceChat, x, y, descriptionKey);
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
    }

    // No @Override: this overrides the method added by the patched UltimateAdvancementAPI, but the
    // plugin compiles against the stock API jar where it doesn't exist yet. At runtime the patched UAA
    // calls it to opt this display into component (per-client) rendering.
    public boolean usesComponentDisplay() {
        return true;
    }

    @Override
    @NotNull
    public BaseComponent[] getChatTitle() {
        return new BaseComponent[]{new TranslatableComponent(titleKey)};
    }

    @Override
    @NotNull
    public BaseComponent[] getChatDescription() {
        return new BaseComponent[]{new TranslatableComponent(descriptionKey)};
    }
}
