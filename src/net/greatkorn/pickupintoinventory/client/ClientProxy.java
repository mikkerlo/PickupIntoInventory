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

    /**
     * All four of the fields below are written from the netty event loop and read on the client
     * thread, so all four are volatile.
     *
     * FML posts ClientConnectedToServerEvent and ClientDisconnectionFromServerEvent from inside its
     * own pipeline handler - FMLHandshakeClientState$6.accept -> NetworkDispatcher.completeHandshake
     * -> completeClientSideConnection, all under channelRead0 - not from the client thread's packet
     * queue. The queue is where policyReceived runs, which is a different thread again. Nothing here
     * is a compound update, so volatile is the whole of what is needed; the visible symptom of it
     * missing was a clear at disconnect that the tick handler never saw.
     */
    private volatile boolean connected;

    /**
     * Set when the player changes their setting with nowhere to send it - the config screen is
     * reachable from the title screen, where there is no server. Without it the change would go out
     * on the next connect as an ordinary login preference, and a server that already held a choice
     * for this player would quite correctly ignore it, so a deliberate edit would look like it did
     * nothing at all.
     */
    private boolean pendingChange;

    private KeyBinding toggleKey;

    /** A line the server's answer left for the client thread to print. */
    private volatile String announcement;

    /**
     * Client ticks since connecting, while the server still owes an answer. Counted up to the
     * retry below and then left alone.
     *
     * The login preference is sent from ClientConnectedToServerEvent, which fires before FML's
     * final handshake ack, so the server may still be on the login handler when it drains the
     * queue. The server's guard there is right - casting would cost the player the connection -
     * but it drops the message, and the drop is terminal: the server never marks this client as
     * one it can answer, so it never sends a policy, this side predicts vanilla routing for the
     * rest of the session and the keybind reports that the mod is not on the server. One resend a
     * couple of seconds in costs a client that was answered nothing at all.
     */
    private volatile int login;

    /**
     * The mode onConnect sent, replayed by the retry rather than assumed.
     *
     * A resend that hardcoded MODE_LOGIN would downgrade the one case the retry exists for. A
     * config-screen edit made from the title screen goes out as MODE_EXPLICIT, because a server
     * already holding a stored choice for this player is right to ignore a default - so replaying
     * it as a default is the same as losing it, which is precisely what the drop had already done.
     */
    private volatile byte loginMode;

    /**
     * What this client last asked for while the server has said nothing back, or null.
     *
     * A server running 1.3.0 has the mod and reads a preference out of this client's message
     * perfectly well - the two booleans are where 1.3.0 left them and the mode byte rides behind
     * them - it simply has no way to say so, so clientKnows() is false there all session. Refusing
     * to send anything in that case took a working toggle away from a server where it had worked,
     * and told the player the mod was not running when it was.
     *
     * There is nothing to toggle from in that state: no answer, and the local file is deliberately
     * not written by a keypress. So the value asked for is remembered here for the next press.
     *
     * It also stands the login retry down. A press inside the first forty ticks has already sent a
     * MODE_EXPLICIT of its own, and a 1.3.0 server applies that and says nothing; the retry would
     * then arrive behind it with the file's value under MODE_LOGIN, which that server applies just
     * as readily - silently undoing the toggle the player had just asked for.
     */
    private volatile Boolean blind;

    private static final int LOGIN_RETRY_TICKS = 40;

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
            // Which of the two it is cannot be told apart from here - a server without the mod
            // drops the payload at the channel and a 1.3.0 server applies it without replying, and
            // both look identical. So the press goes out and the player is told exactly that,
            // rather than being told something false about either.
            final boolean want = !(blind == null ? PIIConfig.enabled : blind.booleanValue());
            blind = Boolean.valueOf(want);
            request(want);
            tell("Asked this server for pickup into inventory " + (want ? "ON" : "OFF")
                + ". It has not told this client what it is running, so there is nothing to confirm"
                + " that with: an older version of the mod applies it without answering, and a"
                + " server without the mod ignores it.");
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
        login = 1;
        // Belt and braces, for the teardown paths the disconnect event never reaches: carrying the
        // last server's policy into this one would have us predicting its routing until this one
        // answers, a parked announcement would print the old server's policy into this server's
        // first tick, and a stale `blind` would decide the next keypress from what some other
        // server had been asked for.
        PIIPolicy.forgetServerPolicy();
        announcement = null;
        blind = null;
        // A preference the player has not touched is a default the server may fall back on; one
        // they changed while there was nobody to tell is an instruction that has been waiting.
        loginMode = pendingChange ? MsgSetPreference.MODE_EXPLICIT : MsgSetPreference.MODE_LOGIN;
        send(loginMode, PIIConfig.enabled);
        pendingChange = false;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        connected = false;
        // Including a line that never found a tick with a player in it to print into. It describes
        // a policy that is no longer in force, and the next server's first tick is where it would
        // otherwise land.
        announcement = null;
        login = 0;
        blind = null;
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

        if (login > 0) {
            if (++login > LOGIN_RETRY_TICKS) {
                login = 0;
                // Only where nothing came back. A server with the mod has answered long before
                // this, and one without it drops the payload at the channel either way. A press
                // in the meantime stands the retry down: see `blind`.
                if (connected && blind == null && !PIIPolicy.clientKnows()) {
                    send(loginMode, PIIConfig.enabled);
                }
            }
        }

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
