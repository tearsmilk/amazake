package core.amazake.systems.api;

import java.util.List;

/** The {@code system.yml} of a .azs file. */
public record SystemInfo(String name, String version, String main, String description, List<String> authors, String apiVersion) {
}
