package net.greatkorn.pickupintoinventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;

/**
 * The one answer to "is the redirect on for this player, right now", and the bookkeeping that keeps
 * both sides giving the same one.
 *
 * There used to be no such answer. Each side worked one out locally from whatever it happened to
 * hold - the client read its own config file, the server read its config, its per-player overrides
 * and its lock - and nothing ever compared the two. That is fine for an item picked up off the
 * ground, which only the server routes, and wrong for anything the client predicts: the number-key
 * swap in a container GUI runs Container.slotClick on both sides, so with the two disagreeing the
 * displaced stack lands in a different slot on each, and the click is still <em>accepted</em>
 * because the slot that was clicked matched. See PIISync.noteForeignSlot for the repair.
 *
 * <h3>The server's copy of the settings</h3>
 *
 * Taken once, when the world starts, dedicated or integrated alike, and never read from PIIConfig
 * again. On a LAN host PIIConfig is the <em>host's own</em> config, and reading it live made the
 * host's personal toggle the default that every guest without a choice of their own ran on - with
 * allowPlayerOverride=false, where guests' own preference packets are refused outright, it silently
 * became the policy for the entire server. A copy taken before anyone can join cannot be moved by
 * anything the host does afterwards, and the keybind no longer writes to that file at all: the
 * host's preference travels the way a guest's does, as a per-player choice keyed by their UUID.
 *
 * <h3>What the client is told, and why it waits to be told</h3>
 *
 * The client holds only what the server last sent it, and until it has been sent something it
 * answers no to everything. That is deliberate: a client with no word from the server cannot know
 * whether the server has the mod at all, and predicting vanilla's routing is the one prediction
 * that cannot disagree with a server it has not spoken to. It does not blank that answer while
 * asking for a change either - it keeps routing on the last policy, which the server is still
 * running too until it processes the request, so the two never disagree over the request's flight.
 *
 * Both halves live in this one class because in singleplayer and on a LAN host they live in one
 * JVM, and keeping them as separate fields - rather than one shared static, which is what
 * PIIConfig.enabled was - is precisely the separation that stops the host's keybind from being a
 * server-wide policy change.
 *
 * Kept to EntityPlayer and java.util so the pickup mixin can reach it without pulling anything
 * heavier onto the transformer classloader; PIINetwork owns the packets.
 */
public final class PIIPolicy {

    /**
     * Everything the logical server knows about one connected client, for exactly as long as it is
     * connected. One record rather than three parallel maps: the pickup path asks two of these
     * questions per insertion that could diverge, and holding them together also makes "none of
     * this outlives the connection" structural rather than three removals to remember.
     *
     * Written from the server thread - FMLProxyPacket does not override Packet.hasPriority, so
     * NetworkManager queues it and the handler runs out of processReceivedPackets - and read from
     * it as well. The fields are volatile anyway, because a mod that dispatches packets early would
     * otherwise be free to publish them unsafely, and because the pickup path reads them.
     */
    private static final class Session {

        /**
         * The client has sent a message carrying a mode byte, so it is new enough to have a handler
         * registered for the answer. FML's indexed codec throws on a discriminator it has no
         * registration for, so a client that has not said this must never be sent one.
         */
        volatile boolean speaks;

        /** What it offered on connect: a default to fall back on, not a decision, never persisted. */
        volatile PIIState.Choice offer;

        /**
         * Serial of the policy last sent, and whether the client has answered for that one.
         *
         * The serial is what stops an answer to a superseded policy counting for the current one.
         * Two pushes can be in flight at once - a keybind pressed twice, or a /pickupinv landing
         * behind a keybind - and the older answer then arrives while the newer policy is still on
         * the wire. Taking it would mean believing the client routes as this side does at the one
         * moment it demonstrably does not, and that belief is exactly what switches off the repair.
         * A byte is enough: only equality is ever asked of it.
         */
        volatile byte sent;
        volatile boolean acked;

        /**
         * A policy has actually been pushed, so `sent` means something.
         *
         * Without it, a Session created by a first message of MODE_ACK with serial 0 satisfies
         * `sent == serial` against the default 0 and the server concludes a client it has never
         * told anything is mirroring it. Only a modified client can do that and the damage is
         * confined to its own inventory, but `sent` legitimately returns to 0 every 256 pushes,
         * so special-casing the value rather than the state gets slowly worse.
         */
        volatile boolean pushed;

        /**
         * This client sent a preference of its own, so there is a mod on the other end - possibly
         * one too old to be told a policy. A bare vanilla client never sends one.
         */
        volatile boolean hasMod;
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<UUID, Session>();

    private static volatile boolean serverEnabled;
    private static volatile boolean serverLocked;
    private static volatile boolean serverHotbar = true;
    private static volatile boolean serverResync;

    /**
     * What the server last said applies here, or null while it has said nothing.
     *
     * One reference rather than a field each, replaced whole, so a pickup on the client thread
     * cannot read the new setting next to the old fallback: the routing decision and the value it
     * falls back on have to come from the same answer, or the client is diverging from the server
     * inside a single insertion, which is the exact failure this class exists to remove.
     */
    private static volatile Told told;

    private static final class Told {

        final boolean enabled;
        final boolean locked;
        final boolean hotbarFallback;
        final PIIState.Choice choice;

        Told(boolean enabled, boolean locked, boolean hotbarFallback, PIIState.Choice choice) {
            this.enabled = enabled;
            this.locked = locked;
            this.hotbarFallback = hotbarFallback;
            this.choice = choice == null ? PIIState.Choice.SERVER : choice;
        }
    }

    private PIIPolicy() {}

    // -------------------------------------------------------------- the server's snapshot

    /** Called as the world starts, before the first player can join. */
    public static void startServer() {
        serverEnabled = PIIConfig.enabled;
        serverLocked = !PIIConfig.allowPlayerOverride;
        serverHotbar = PIIConfig.allowHotbarWhenInventoryFull;
        serverResync = PIIConfig.resyncAfterPickup;
        SESSIONS.clear();
    }

    /**
     * Called as the world stops. The values go inert rather than back to the config, because the
     * client half of this class outlives the integrated server and must not start reading the
     * host's server settings the moment they leave the world.
     */
    public static void stopServer() {
        serverEnabled = false;
        serverLocked = false;
        serverHotbar = true;
        serverResync = false;
        SESSIONS.clear();
    }

    /** The setting players who have made no choice of their own run on. */
    public static boolean serverDefault() {
        return serverEnabled;
    }

    /** allowPlayerOverride=false: personal choices are kept but ignored while the lock is on. */
    public static boolean isLocked() {
        return serverLocked;
    }

    public static boolean serverHotbarFallback() {
        return serverHotbar;
    }

    public static boolean resendsAfterPickup() {
        return serverResync;
    }

    // ------------------------------------------------------------ what this client was told

    public static void applyToClient(
        boolean enabled, boolean locked, boolean hotbarFallback, PIIState.Choice choice) {
        told = new Told(enabled, locked, hotbarFallback, choice);
    }

    /**
     * The connection is gone, so nothing is in force here any more. This client goes back to
     * predicting what a client without the mod predicts, which is the only prediction that cannot
     * disagree with a server that has not told it anything.
     */
    public static void forgetServerPolicy() {
        told = null;
    }

    public static boolean clientKnows() {
        return told != null;
    }

    public static boolean clientEnabled() {
        final Told current = told;
        return current != null && current.enabled;
    }

    public static boolean clientLocked() {
        final Told current = told;
        return current != null && current.locked;
    }

    public static PIIState.Choice clientChoice() {
        final Told current = told;
        return current == null ? PIIState.Choice.SERVER : current.choice;
    }

    // ---------------------------------------------------------------------- the question

    /**
     * The question the pickup mixin asks. Without a player, or without a world to say which side we
     * are on, there is no policy to apply and no way to tell whose it would be - and guessing is
     * exactly the divergence this class exists to prevent, so the answer is vanilla's.
     */
    public static boolean isEnabledFor(EntityPlayer player) {
        if (player == null || player.field_70170_p == null) return false;
        if (player.field_70170_p.field_72995_K) {
            final Told current = told;
            return current != null && current.enabled;
        }
        return serverEffective(player.func_110124_au());
    }

    /** The same question asked of a UUID, for building the answer sent to that player's client. */
    public static boolean serverEffective(UUID id) {
        if (serverLocked) return serverEnabled;
        final PIIState.Choice chosen = choiceFor(id);
        if (chosen == PIIState.Choice.SERVER) return serverEnabled;
        return chosen == PIIState.Choice.ON;
    }

    /**
     * Precedence, in one place: a choice the player made on this server outranks the preference
     * their client offered on connect, which in turn outranks the server's default. The offer is
     * what makes a client's own config mean anything on a server that has never heard from them;
     * the stored choice is what stops that offer overwriting them every time they log in.
     */
    public static PIIState.Choice choiceFor(UUID id) {
        final PIIState.Choice stored = PIIState.get(id);
        if (stored != null) return stored;
        final Session session = SESSIONS.get(id);
        final PIIState.Choice offered = session == null ? null : session.offer;
        return offered == null ? PIIState.Choice.SERVER : offered;
    }

    /**
     * Whether refusing the last empty hotbar slot is allowed. Server-authoritative like the rest,
     * so a client predicting a swap refuses in the same places the server does; unknown means never
     * refuse, which is what vanilla does and the only answer that cannot lose an item.
     */
    public static boolean allowsHotbarFallback(EntityPlayer player) {
        if (player == null || player.field_70170_p == null) return true;
        if (!player.field_70170_p.field_72995_K) return serverHotbar;
        final Told current = told;
        // Unreachable while nothing has been told - the redirect is off then - but true is the
        // answer that cannot lose an item, so that is what an unknown reads as.
        return current == null || current.hotbarFallback;
    }

    // ------------------------------------------------------------------ per-player session

    private static Session sessionFor(UUID id) {
        final Session existing = SESSIONS.get(id);
        if (existing != null) return existing;
        final Session created = new Session();
        final Session raced = SESSIONS.putIfAbsent(id, created);
        return raced == null ? created : raced;
    }

    public static void noteSpeaker(UUID id) {
        sessionFor(id).speaks = true;
    }

    public static boolean speaks(UUID id) {
        final Session session = SESSIONS.get(id);
        return session != null && session.speaks;
    }

    /** The preference a client offered on connect. Not a choice, and never persisted. */
    public static void noteOffer(UUID id, PIIState.Choice offered) {
        sessionFor(id).offer = offered;
    }

    /**
     * Stamps a policy about to go out and returns its serial. Until the client answers for this
     * one it is routing on the previous policy, or on none at all, so it stops counting as
     * mirroring this side - and because the caller changes the policy and stamps it without
     * anything running in between, there is no moment where the two disagree unnoticed.
     */
    public static byte beginPush(UUID id) {
        final Session session = sessionFor(id);
        session.acked = false;
        session.sent = (byte) (session.sent + 1);
        session.pushed = true;
        return session.sent;
    }

    /** An answer, accepted only for the policy currently in force. */
    public static void noteMirror(UUID id, byte serial) {
        final Session session = SESSIONS.get(id);
        if (session != null && session.pushed && session.sent == serial) session.acked = true;
    }

    /** Set by any preference this client sends, which is the one thing only a mod does. */
    public static void noteMod(UUID id) {
        sessionFor(id).hasMod = true;
    }

    /** Whether anything at all on the other end is running this mod, of any version. */
    public static boolean hasMod(UUID id) {
        final Session session = SESSIONS.get(id);
        return session != null && session.hasMod;
    }

    /** Whether this client is known to be routing pickups exactly as this side does. */
    public static boolean mirrors(UUID id) {
        final Session session = SESSIONS.get(id);
        return session != null && session.acked;
    }

    /** The player is gone. Nothing here survives them: the next connection may be a bare client. */
    public static void forget(UUID id) {
        SESSIONS.remove(id);
    }

    // ------------------------------------------------------------------------- reporting

    /**
     * One wording for both sides, so what /pickupinv prints on the server and what the keybind
     * prints on the client cannot drift apart.
     */
    public static String describe(boolean enabled, boolean locked, PIIState.Choice choice) {
        final String state = enabled ? "ON" : "OFF";
        if (locked) return "Pickup into inventory: " + state + " (locked by the server).";
        if (choice == null || choice == PIIState.Choice.SERVER) {
            return "Pickup into inventory: " + state + " (following the server default).";
        }
        return "Pickup into inventory: " + state + " (your setting).";
    }
}
