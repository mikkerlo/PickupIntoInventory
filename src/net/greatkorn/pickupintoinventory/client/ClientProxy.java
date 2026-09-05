package net.greatkorn.pickupintoinventory.client;

import net.greatkorn.pickupintoinventory.CommonProxy;
import net.greatkorn.pickupintoinventory.PIIConfig;
import net.greatkorn.pickupintoinventory.net.MsgSetPreference;
import net.greatkorn.pickupintoinventory.net.PIINetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    private boolean connected;
    private KeyBinding toggleKey;

    @Override
    public void preInit() {
        FMLCommonHandler.instance().bus().register(this);

        // Unbound by default - GTNH has hundreds of binds and any default would collide with one.
        // Find it under Options > Controls (the Controlling mod's search box takes "pickup").
        toggleKey = new KeyBinding(
            "key.pickupintoinventory.toggle", 0, "key.categories.pickupintoinventory");
        ClientRegistry.registerKeyBinding(toggleKey);
    }

    /** Applies config GUI edits without a restart, and pushes them to the server we are on. */
    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!PIIConfig.MODID.equals(event.modID)) return;
        PIIConfig.load();
        sendPreference();
    }

    /** The in-game toggle. The Mods config screen only exists on the title screen. */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (toggleKey == null || !toggleKey.func_151468_f()) return; // isPressed

        PIIConfig.setEnabled(!PIIConfig.enabled);
        sendPreference();
        tell("Pickup into inventory: " + (PIIConfig.enabled ? "ON" : "OFF"));
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        connected = true;
        sendPreference();
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        connected = false;
    }

    /**
     * The server keeps a per-player setting; this tells it ours. If the server does not have the
     * mod the payload is simply dropped there, which is why nothing here checks first.
     */
    private void sendPreference() {
        if (!connected) return;
        PIINetwork.channel.sendToServer(new MsgSetPreference(false, PIIConfig.enabled));
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null || mc.field_71439_g == null) return;
        mc.field_71439_g.func_145747_a(new ChatComponentText(message)); // addChatMessage
    }
}
