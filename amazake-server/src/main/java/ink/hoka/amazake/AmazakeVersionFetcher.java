package ink.hoka.amazake;

import com.destroystokyo.paper.util.VersionFetcher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import static net.kyori.adventure.text.Component.text;

/**
 * What /version says about updates. Paper's own fetcher asks PaperMC's build service about Paper
 * builds and points to Paper's downloads, which says nothing true about an Amazake build: this one
 * names the build and where Amazake's are, and asks nothing over the network.
 */
public final class AmazakeVersionFetcher implements VersionFetcher {

    @Override
    public long getCacheTime() {
        return -1;
    }

    @Override
    public Component getVersionMessage() {
        return text()
            .append(text(Amazake.versionLine(), NamedTextColor.WHITE))
            .append(Component.newline())
            .append(text("Builds and source: ", NamedTextColor.GRAY))
            .append(text(Amazake.REPOSITORY, NamedTextColor.GOLD).clickEvent(ClickEvent.openUrl(Amazake.REPOSITORY)))
            .build();
    }
}
