package core.amazake;

import io.papermc.paper.ServerBuildInfo;

/** Who this server is: Amazake's name, version and links. */
public final class Amazake {

    public static final String NAME = "Amazake";
    public static final String AUTHOR = "tearsmilk";
    public static final String REPOSITORY = "https://github.com/tearsmilk/amazake";

    private Amazake() {
    }

    /** "Amazake 1.21.11-DEV-abc1234 (Minecraft 1.21.11)". */
    public static String versionLine() {
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        return info.brandName() + " " + info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE)
            + " (Minecraft " + info.minecraftVersionName() + ")";
    }
}
