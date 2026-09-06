package net.greatkorn.pickupintoinventory;

public class CommonProxy {

    public void preInit() {}

    /**
     * The server has said what is in force for us. Only a client has anywhere to show it, and only
     * this hook keeps net/ free of client classes: SimpleNetworkWrapper.registerMessage calls
     * newInstance on every handler on both physical sides, so a Side.CLIENT handler naming
     * Minecraft directly would be loaded - and fail to verify - on a dedicated server.
     */
    public void policyReceived(boolean announce) {}
}
