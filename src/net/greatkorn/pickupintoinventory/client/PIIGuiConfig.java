package net.greatkorn.pickupintoinventory.client;

import net.greatkorn.pickupintoinventory.PIIConfig;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import cpw.mods.fml.client.config.GuiConfig;

public class PIIGuiConfig extends GuiConfig {

    public PIIGuiConfig(GuiScreen parent) {
        super(
            parent,
            new ConfigElement(PIIConfig.config.getCategory(Configuration.CATEGORY_GENERAL))
                .getChildElements(),
            PIIConfig.MODID,
            false,
            false,
            GuiConfig.getAbridgedConfigPath(PIIConfig.config.toString()));
    }
}
