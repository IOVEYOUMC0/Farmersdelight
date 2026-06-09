package com.huidu.farmersdelight.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.nms.wrappers.advancement.AdvancementDisplayWrapper;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Advancement display whose title/description are resource-pack translation keys rather than baked
 * server-side strings, so each client renders them in its own language. Keys resolve client-side from
 * the resource pack lang files.
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

    @Override
    @NotNull
    public AdvancementDisplayWrapper getNMSWrapper(@NotNull Advancement advancement) {
        try {
            BaseComponent title = new TranslatableComponent(titleKey);
            BaseComponent description = new TranslatableComponent(descriptionKey);
            if (advancement instanceof RootAdvancement root) {
                return AdvancementDisplayWrapper.craft(getIcon(), title, description,
                        getFrame().getNMSWrapper(), getX(), getY(), root.getBackgroundTexture());
            }
            return AdvancementDisplayWrapper.craft(getIcon(), title, description,
                    getFrame().getNMSWrapper(), getX(), getY());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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
