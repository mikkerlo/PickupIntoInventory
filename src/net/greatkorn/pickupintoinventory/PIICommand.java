package net.greatkorn.pickupintoinventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.greatkorn.pickupintoinventory.net.PIINetwork;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

/** /pickupinv [on|off|server] - usable by any player, no permissions needed. */
public class PIICommand extends CommandBase {

    @Override
    public String func_71517_b() { // getCommandName
        return "pickupinv";
    }

    @Override
    public String func_71518_a(ICommandSender sender) { // getCommandUsage
        return "/pickupinv [on|off|server]";
    }

    @Override
    public int func_82362_a() { // getRequiredPermissionLevel
        return 0;
    }

    /**
     * getRequiredPermissionLevel is not enough in 1.7.10: EntityPlayerMP.canCommandSenderUseCommand
     * hard-codes a whitelist (seed/tell/help/me) and otherwise demands the player be opped, whatever
     * level the command asks for. So the check has to be overridden outright. Safe here - the command
     * only ever changes the sender's own preference.
     */
    @Override
    public boolean func_71519_b(ICommandSender sender) { // canCommandSenderUseCommand
        return true;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List func_71516_a(ICommandSender sender, String[] args) { // addTabCompletionOptions
        if (args.length == 1) {
            List<String> out = new ArrayList<String>();
            for (String s : Arrays.asList("on", "off", "server")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return null;
    }

    @Override
    public void func_71515_b(ICommandSender sender, String[] args) { // processCommand
        EntityPlayerMP player = func_71521_c(sender); // getCommandSenderAsPlayer

        if (args.length == 0) {
            reply(sender, describe(player));
            return;
        }

        if (PIIPolicy.isLocked()) {
            // The state itself is described in one place, so this sentence is only the refusal:
            // a second hand-rolled wording for a locked server is the drift describe() exists to
            // stop, and it had already started - this one said "for everyone" where describe says
            // "locked by the server".
            reply(sender, "This server does not allow personal settings. " + describe(player));
            return;
        }

        String arg = args[0].toLowerCase();
        if ("on".equals(arg) || "true".equals(arg)) {
            PIIState.set(player.func_110124_au(), PIIState.Choice.ON);
        } else if ("off".equals(arg) || "false".equals(arg)) {
            PIIState.set(player.func_110124_au(), PIIState.Choice.OFF);
        } else if ("server".equals(arg) || "default".equals(arg) || "reset".equals(arg)) {
            // Stored as a choice rather than erased. An erased choice is one the player has never
            // made, and that is what a client's login preference is allowed to fill in - so erasing
            // it here would hand the setting straight back to the client on the next connect.
            PIIState.set(player.func_110124_au(), PIIState.Choice.SERVER);
        } else {
            reply(sender, "Usage: " + func_71518_a(sender));
            return;
        }
        // The client routes pickups on what it was last told, so it has to be told again here or
        // it goes on predicting the setting the player just changed away from - and its keybind
        // would toggle from that stale value too, asking for the setting they are already on.
        PIINetwork.sendPolicy(player, false);
        reply(sender, describe(player));
    }

    private String describe(EntityPlayerMP player) {
        return PIIPolicy.describe(
            PIIPolicy.serverEffective(player.func_110124_au()),
            PIIPolicy.isLocked(),
            PIIPolicy.choiceFor(player.func_110124_au()));
    }

    private static void reply(ICommandSender sender, String text) {
        sender.func_145747_a(new ChatComponentText(text)); // addChatMessage
    }

    /**
     * CommandBase only satisfies Comparable through a synthetic bridge, which javac hides when
     * compiling against the shipped jar rather than an MCP dev workspace. Same ordering it uses.
     */
    @Override
    public int compareTo(Object other) {
        return func_71517_b().compareTo(((ICommand) other).func_71517_b());
    }
}
