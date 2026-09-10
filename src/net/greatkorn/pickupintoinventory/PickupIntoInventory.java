package net.greatkorn.pickupintoinventory;

import java.io.File;

import net.greatkorn.pickupintoinventory.net.PIINetwork;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

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

    /**
     * The world's policy is fixed here, before anyone can join, and nothing the host's own client
     * does to their config afterwards moves it again. See PIIPolicy.
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        PIIPolicy.startServer();
        event.registerServerCommand(new PIICommand());
    }

    /**
     * The preferences file is written off the tick loop now, so a /pickupinv run in the seconds
     * before a stop can still be nothing but a counter in memory when the world ends. On a
     * dedicated server the JVM goes with it, and on a client leaving a world nothing would write it
     * either, since the writer is a daemon and the next world load reads the file back. So the last
     * thing the world does is finish the writing, on this thread, before anything else is torn down.
     */
    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        PIIState.flush();
        PIIPolicy.stopServer();
    }
}
