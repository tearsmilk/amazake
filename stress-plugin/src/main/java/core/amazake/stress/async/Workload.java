package core.amazake.stress.async;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * CPU work typical for heavy plugins: hashing, JSON (de)serialisation of fake player data, sorting.
 * One instance per thread of work (not thread-safe); every input comes from its own seeded Random, so the
 * amount and kind of work per call is identical on every server.
 */
final class Workload {

    private static final Gson GSON = new Gson();
    private static final String[] WORDS = {"amazake", "leaf", "paper", "stress", "chunk", "entity", "tick", "async",
        "plugin", "hopper", "redstone", "village", "nether", "diamond", "emerald", "beacon"};

    private final Random random;
    private final MessageDigest sha;
    private final byte[] buffer = new byte[1024];
    private long checksum;

    Workload(Random random) {
        this.random = random;
        try {
            this.sha = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e); // every JRE has it
        }
        random.nextBytes(this.buffer);
    }

    /** SHA-256 over {@code kib} KiB, chained (every block depends on the previous digest). */
    void hash(int kib) {
        byte[] digest = null;
        for (int i = 0; i < kib; i++) {
            this.sha.update(this.buffer);
            if (digest != null) {
                this.sha.update(digest);
            }
            digest = this.sha.digest();
        }
        if (digest != null) {
            this.checksum += digest[0];
        }
    }

    /** Serialises {@code objects} fake player records to JSON and parses the result back. Returns the JSON. */
    String json(int objects) {
        final List<Map<String, Object>> records = new ArrayList<>(objects);
        for (int i = 0; i < objects; i++) {
            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("name", WORDS[this.random.nextInt(WORDS.length)] + "_" + this.random.nextInt(100_000));
            record.put("level", this.random.nextInt(100));
            record.put("balance", Math.round(this.random.nextDouble() * 1_000_000.0) / 100.0);
            record.put("online", this.random.nextBoolean());
            final List<Map<String, Object>> items = new ArrayList<>();
            final int count = 4 + this.random.nextInt(8);
            for (int k = 0; k < count; k++) {
                final Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "minecraft:" + WORDS[this.random.nextInt(WORDS.length)]);
                item.put("count", 1 + this.random.nextInt(64));
                item.put("lore", WORDS[this.random.nextInt(WORDS.length)] + " " + WORDS[this.random.nextInt(WORDS.length)]);
                items.add(item);
            }
            record.put("inventory", items);
            records.add(record);
        }
        final String json = GSON.toJson(records);
        final Object parsed = GSON.fromJson(json, List.class);
        this.checksum += json.length() + (parsed instanceof List<?> list ? list.size() : 0);
        return json;
    }

    /** Sorts {@code size} random ints and a list of {@code size / 8} random strings. */
    void sort(int size) {
        final int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = this.random.nextInt();
        }
        Arrays.sort(values);
        final int strings = size / 8;
        final List<String> words = new ArrayList<>(strings);
        for (int i = 0; i < strings; i++) {
            words.add(WORDS[this.random.nextInt(WORDS.length)] + this.random.nextInt(1000));
        }
        words.sort(null);
        this.checksum += (size > 0 ? values[size / 2] : 0) + (strings > 0 ? words.get(strings / 2).length() : 0);
    }

    /** Hashes the given text (used by the busy main-thread tasks). */
    void hashText(String text) {
        this.checksum += this.sha.digest(text.getBytes(StandardCharsets.UTF_8))[0];
    }

    /** Keeps the JIT from removing the work. */
    long checksum() {
        return this.checksum;
    }
}
