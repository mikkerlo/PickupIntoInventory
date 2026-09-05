package net.greatkorn.pickupintoinventory;

import java.io.File;

import net.greatkorn.pickupintoinventory.net.PIINetwork;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = PIIConfig.MODID,
    name = "Pickup Into Inventory",
    version = "1.3.0",
    guiFactory = "net.greatkorn.pickupintoinventory.client.PIIGuiFactory",
    acceptableRemoteVersions = "*")
public class PickupIntoInventory {

    @SidedProxy(
        clientSide = "net.greatkorn.pickupintoinventory.client.ClientProxy",
        serverSide = "net.greatkorn.pickupintoinventory.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PIIConfig.init(event.getSuggestedConfigurationFile());
        PIIState.init(
            new File(event.getModConfigurationDirectory(), PIIConfig.MODID + "_players.properties"));
        PIINetwork.init();
        PIISync.register();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new PIICommand());
    }
}
