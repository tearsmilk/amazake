package core.amazake.systems.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void readsValuesAndFixesBadOnes() throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("welcome: hi\nactionbar:\n  enabled: false\n  interval-ticks: 0\n  format: x\n");
        final List<String> warnings = new ArrayList<>();
        final Settings s = Settings.from(yaml, warnings::add);
        assertEquals(new Settings("hi", false, 20L, "x"), s);
        assertEquals(1, warnings.size());
    }

    @Test
    void formatsOneDecimal() {
        assertEquals("19.9", Settings.oneDecimal(19.94));
    }
}
