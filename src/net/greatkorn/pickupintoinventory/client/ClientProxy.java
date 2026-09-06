package net.greatkorn.pickupintoinventory.client;

import net.greatkorn.pickupintoinventory.CommonProxy;
import net.greatkorn.pickupintoinventory.PIIConfig;
import net.greatkorn.pickupintoinventory.PIIPolicy;
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
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    private boolean connected;

    /**
     * Set when the player changes their setting with nowhere to send it - the config screen is
     * reachable from the title screen, where there is no server. Without it the change would go out
     * on the next connect as an ordinary login preference, and a server that already held a choice
     * for this player would quite correctly ignore it, so a deliberate edit would look like it did
     * nothing at all.
     */
    private boolean pendingChange;

    private KeyBinding toggleKey;

    /** A line the server's answer left for the client thread to print. Written from netty. */
    private volatile String announcement;

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
        final boolean was = PIIConfig.enabled;
        PIIConfig.load();
        // The event fires once for the whole screen whenever anything on it changed, and three of
        // the four options are the server's business and do nothing on a client. Announcing an
        // unchanged preference as a deliberate change would overwrite whatever the player had set
        // on that server with /pickupinv - which is the very thing a login preference stopped
        // doing - so only a real change is announced.
        if (PIIConfig.enabled != was) request(PIIConfig.enabled);
    }

    /** The in-game toggle. The Mods config screen only exists on the title screen. */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (toggleKey == null || !toggleKey.func_151468_f()) return; // isPressed

        // Nothing is written to the config file here, and that is the point. 'enabled' is also the
        // default the machine running the world copies at startup, so a host toggling it in game
        // used to hand their personal preference to every guest who had not chosen - immediately
        // before this task, and still on the next world load if the press were persisted. The
        // setting the press asks for is a per-player choice, kept by the server under the player's
        // UUID exactly like a guest's, and it survives a restart there rather than here.
        if (!PIIPolicy.clientKnows()) {
            tell("Pickup into inventory is not running on this server, so there is nothing to toggle.");
            return;
        }

        // Toggle what is actually in force, not what the local file happens to say. A /pickupinv
        // the player ran a moment ago has already been pushed here, and toggling the stale local
        // value would ask the server for the setting they are already on - the press would land,
        // be accepted, and change nothing anybody could see.
        request(!PIIPolicy.clientEnabled());
        // Nothing is printed here: the server answers, and the answer is what gets printed. It may
        // be refusing personal settings altogether, and announcing the value we asked for would be
        // telling the player something that did not happen.
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        connected = true;
        // Belt and braces: the disconnect event only fires where the teardown goes out through the
        // pipeline, and carrying the last server's policy into this one would have us predicting
        // its routing until this one answers.
        PIIPolicy.forgetServerPolicy();
        // A preference the player has not touched is a default the server may fall back on; one
        // they changed while there was nobody to tell is an instruction that has been waiting.
        send(
            pendingChange ? MsgSetPreference.MODE_EXPLICIT : MsgSetPreference.MODE_LOGIN,
            PIIConfig.enabled);
        pendingChange = false;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        connected = false;
        // Nothing is in force any more, so this client goes back to predicting exactly what a
        // client without the mod would - the one prediction that cannot disagree with a server it
        // has not spoken to yet.
        PIIPolicy.forgetServerPolicy();
    }

    /**
     * The server has answered. The policy itself is applied by the message handler the moment it
     * arrives; all that is left is telling the player, and only for a change they asked for - the
     * login handshake, or the push behind a /pickupinv that has already printed its own reply,
     * would just be noise.
     *
     * The line is parked for the next client tick rather than written here. FMLProxyPacket does
     * not override Packet.hasPriority, so this runs out of the client thread's packet queue and
     * writing chat directly would be safe - but the player entity is not guaranteed to exist yet
     * when the answer to a login handshake arrives, and a line written then would go nowhere.
     */
    @Override
    public void policyReceived(boolean announce) {
        if (!announce) return;
        announcement = PIIPolicy
            .describe(PIIPolicy.clientEnabled(), PIIPolicy.clientLocked(), PIIPolicy.clientChoice());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final String line = announcement;
        if (line == null) return;
        // Kept until it has somewhere to go. The answer to a keybind press can arrive in the tick
        // before the player entity exists on this side, and dropping it there would leave the press
        // looking like it did nothing.
        if (tell(line)) announcement = null;
    }

    /**
     * The server keeps a per-player setting; this tells it ours. If the server does not have the
     * mod the payload is simply dropped there, which is why nothing here checks first.
     *
     * The value asked for is passed in rather than read back off PIIConfig, because the two callers
     * mean different things by it: the config screen is announcing what it just wrote to the file,
     * and the keybind is asking for the opposite of what the server said is in force, which it
     * deliberately never writes there.
     */
    private void request(boolean enabled) {
        if (!connected) {
            pendingChange = true;
            return;
        }
        // The policy in force here is deliberately left alone until the answer arrives. The server
        // is still running the old one too until it processes this, so over the request's flight
        // the two sides agree; blanking it here would make them disagree for exactly that long,
        // and the server would not yet know to repair it.
        send(MsgSetPreference.MODE_EXPLICIT, enabled);
    }

    private void send(byte mode, boolean enabled) {
        PIINetwork.channel.sendToServer(new MsgSetPreference(mode, false, enabled));
    }

    private static boolean tell(String message) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc == null || mc.field_71439_g == null) return false;
        mc.field_71439_g.func_145747_a(new ChatComponentText(message)); // addChatMessage
        return true;
    }
}
