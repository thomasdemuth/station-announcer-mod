package com.stationannouncer.mtraddon.nav;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stationannouncer.StationAnnouncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pairing codes and long-lived browser tokens for in-game journey directions
 * ({@code /nav}, and the dispatch web UI's "send to my HUD" button).
 *
 * <h2>Lifecycle of a pairing</h2>
 * <ol>
 *   <li>A player runs {@code /navpair} in game. That mints a six-character CODE from the
 *       unambiguous alphabet {@value #CODE_ALPHABET} (no I/O/0/1), valid for
 *       {@value #CODE_TTL_MINUTES} minutes and bound to that player's UUID and name.
 *       Exactly ONE code is live per player: a second {@code /navpair} replaces the
 *       first, so a code read off the screen is never ambiguous.</li>
 *   <li>The browser posts the code to {@code /dispatch/api/pair}. The code is CONSUMED
 *       (single use) and exchanged for a 32-hex-character TOKEN, also bound to that
 *       player's UUID + name, carrying an optional browser-supplied label.</li>
 *   <li>The browser stores the token and sends journeys with it. Every accepted use
 *       stamps {@code lastUsedAt}; a token unused for {@value #TOKEN_IDLE_DAYS} days
 *       expires and is dropped at the next purge.</li>
 *   <li>{@code /navpair list} shows the caller's paired browsers, {@code /navpair revoke}
 *       deletes them. A player can only ever see or revoke their OWN tokens.</li>
 * </ol>
 *
 * <p><b>Persistence:</b> {@code <save>/station-announcer-addon/nav-tokens.json}, written
 * with {@code AddonStore}'s debounced daemon-thread pattern (2 s coalescing window, one
 * shared daemon executor, synchronous flush at SERVER_STOPPING). Only TOKENS are
 * persisted — pairing codes live ten minutes and deliberately do not survive a restart.
 * A missing or corrupt file starts empty and never throws.</p>
 *
 * <p><b>Threading:</b> everything is behind one monitor and every getter returns a
 * snapshot by value, because the Jetty workers serving {@code /dispatch/api/pair} and
 * {@code /dispatch/api/navigate} read this while the server thread's commands write it.
 * The {@link #server()} holder is the addon's single reference to the running server for
 * the nav servlet endpoints (captured in {@code AddonInit}'s SERVER_STARTED, dropped in
 * SERVER_STOPPING) — nothing else may keep a second one.</p>
 */
public final class NavStore {
    /** Crockford-ish: no I, L, O, 0 or 1, so a code read off a screen is unambiguous. */
    public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    public static final int CODE_TTL_MINUTES = 10;
    /** A token unused for this long is treated as gone. */
    public static final int TOKEN_IDLE_DAYS = 30;
    public static final int MAX_LABEL_LENGTH = 32;
    /** Per-token and per-target-player send throttle for {@code /dispatch/api/navigate}. */
    public static final long SEND_COOLDOWN_MILLIS = 3_000;
    /** Sanity cap: a player with more than this many paired browsers loses the oldest. */
    public static final int MAX_TOKENS_PER_PLAYER = 16;

    private static final long CODE_TTL_MILLIS = CODE_TTL_MINUTES * 60_000L;
    private static final long TOKEN_IDLE_MILLIS = TOKEN_IDLE_DAYS * 24L * 60L * 60L * 1000L;
    private static final long SAVE_DELAY_MILLIS = 2_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private static final ScheduledExecutorService SAVE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Station Announcer Nav Store");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SAVE_PENDING = new AtomicBoolean();

    /** One paired browser. {@code label} may be empty; it is only ever shown to its owner. */
    public record Token(String token, UUID playerId, String playerName, String label,
                        long createdAt, long lastUsedAt) {
    }

    /** A live pairing code awaiting redemption. Never persisted. */
    private record PairCode(String code, UUID playerId, String playerName, long expiresAt) {
    }

    /** The outcome of redeeming a code: {@code token} is null when it failed. */
    public record Redemption(String token, UUID playerId, String playerName, String error) {
        public boolean ok() {
            return token != null;
        }
    }

    private static Path dataPath;
    /** token → record. */
    private static final Map<String, Token> tokens = new LinkedHashMap<>();
    /** code → record. */
    private static final Map<String, PairCode> codes = new HashMap<>();
    /** player → their one live code, so minting a new one retires the old. */
    private static final Map<UUID, String> codeByPlayer = new HashMap<>();
    /** Rate limiting for the navigate endpoint: token → last accepted send. */
    private static final Map<String, Long> lastSendByToken = new HashMap<>();
    /** …and the same per TARGET PLAYER, so several browsers cannot gang up on one HUD. */
    private static final Map<UUID, Long> lastSendByPlayer = new HashMap<>();

    /**
     * The running server, for the nav servlet endpoints. Volatile rather than
     * lock-guarded: the Jetty workers only ever read it, and a null read simply
     * answers "server unavailable".
     */
    private static volatile MinecraftServer server;

    private NavStore() {
    }

    // ----------------------------------------------------------------- lifecycle

    /** SERVER_STARTED: read the world's token file and capture the server reference. */
    public static void load(MinecraftServer minecraftServer) {
        Path path = minecraftServer.getSavePath(WorldSavePath.ROOT)
                .resolve("station-announcer-addon").resolve("nav-tokens.json").normalize();
        int loaded;
        synchronized (LOCK) {
            dataPath = path;
            tokens.clear();
            codes.clear();
            codeByPlayer.clear();
            lastSendByToken.clear();
            lastSendByPlayer.clear();
            try {
                if (Files.exists(path)) {
                    readTokens(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
                }
            } catch (Exception e) {
                // Corrupt or half-written file: start empty rather than break the world load.
                // The next save rewrites it, so a bad file self-heals (paired browsers
                // simply have to pair again).
                tokens.clear();
                StationAnnouncer.LOGGER.warn("Could not read {}, starting with no paired browsers", path, e);
            }
            purgeLocked(System.currentTimeMillis());
            loaded = tokens.size();
        }
        server = minecraftServer;
        StationAnnouncer.LOGGER.info("Nav store loaded ({} paired browser{})", loaded, loaded == 1 ? "" : "s");
    }

    /** SERVER_STOPPING: write the tokens right now and drop the server reference. */
    public static void stop() {
        SAVE_PENDING.set(false);
        saveNow();
        server = null;
    }

    /** The running server, or null outside a world (nav endpoints then answer 503-ish JSON). */
    public static MinecraftServer server() {
        return server;
    }

    private static void readTokens(JsonObject root) {
        JsonObject tokensJson = root.getAsJsonObject("tokens");
        if (tokensJson == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : tokensJson.entrySet()) {
            try {
                JsonObject value = entry.getValue().getAsJsonObject();
                UUID playerId = UUID.fromString(value.get("player").getAsString());
                String name = value.has("name") ? value.get("name").getAsString() : "";
                String label = value.has("label") ? value.get("label").getAsString() : "";
                long createdAt = value.has("createdAt") ? value.get("createdAt").getAsLong() : 0;
                long lastUsedAt = value.has("lastUsedAt") ? value.get("lastUsedAt").getAsLong() : createdAt;
                String token = entry.getKey().toLowerCase(Locale.ROOT);
                if (token.length() == 32) {
                    tokens.put(token, new Token(token, playerId, name, label, createdAt, lastUsedAt));
                }
            } catch (Exception ignored) {
                // One unreadable entry must not cost the rest of the file.
            }
        }
    }

    // ---------------------------------------------------------------------- codes

    /**
     * Mint (or replace) the caller's pairing code. Server thread — called from
     * {@code /navpair}.
     *
     * @return the six-character code to type into the browser
     */
    public static String mintCode(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        String playerName = player.getName().getString();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            purgeLocked(now);
            String previous = codeByPlayer.remove(playerId);
            if (previous != null) {
                codes.remove(previous);
            }
            String code;
            do {
                StringBuilder builder = new StringBuilder(CODE_LENGTH);
                for (int i = 0; i < CODE_LENGTH; i++) {
                    builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
                }
                code = builder.toString();
            } while (codes.containsKey(code));
            codes.put(code, new PairCode(code, playerId, playerName, now + CODE_TTL_MILLIS));
            codeByPlayer.put(playerId, code);
            return code;
        }
    }

    /**
     * Exchange a pairing code for a long-lived token. Jetty worker thread — called from
     * {@code POST /dispatch/api/pair}. The code is consumed whether or not the browser
     * keeps the token, so a leaked code cannot be replayed.
     *
     * @param rawCode the code the browser typed (case and spacing are forgiven)
     * @param rawLabel an optional browser description, sanitised and capped here
     */
    public static Redemption redeem(String rawCode, String rawLabel) {
        String code = normaliseCode(rawCode);
        if (code.length() != CODE_LENGTH) {
            return new Redemption(null, null, null, "invalid or expired code");
        }
        String label = sanitiseLabel(rawLabel);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            purgeLocked(now);
            PairCode pairCode = codes.remove(code);
            if (pairCode == null || pairCode.expiresAt() <= now) {
                if (pairCode != null) {
                    codeByPlayer.remove(pairCode.playerId(), code);
                }
                return new Redemption(null, null, null, "invalid or expired code");
            }
            codeByPlayer.remove(pairCode.playerId(), code);
            String token = randomHex();
            tokens.put(token, new Token(token, pairCode.playerId(), pairCode.playerName(), label, now, now));
            trimTokensLocked(pairCode.playerId());
            markDirty();
            return new Redemption(token, pairCode.playerId(), pairCode.playerName(), null);
        }
    }

    /** Uppercase, strip everything that is not in the alphabet (spaces, dashes, O→0 typos stay wrong). */
    private static String normaliseCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        String upper = rawCode.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length() && builder.length() <= CODE_LENGTH; i++) {
            char character = upper.charAt(i);
            if (CODE_ALPHABET.indexOf(character) >= 0) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /** Printable ASCII only, collapsed whitespace, capped — it is shown in chat. */
    private static String sanitiseLabel(String rawLabel) {
        if (rawLabel == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(MAX_LABEL_LENGTH);
        boolean lastWasSpace = false;
        for (int i = 0; i < rawLabel.length() && builder.length() < MAX_LABEL_LENGTH; i++) {
            char character = rawLabel.charAt(i);
            if (character < ' ' || character > '~' || character == '§') {
                character = ' ';
            }
            if (character == ' ') {
                if (lastWasSpace || builder.length() == 0) {
                    continue;
                }
                lastWasSpace = true;
            } else {
                lastWasSpace = false;
            }
            builder.append(character);
        }
        return builder.toString().trim();
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        String token;
        do {
            RANDOM.nextBytes(bytes);
            StringBuilder builder = new StringBuilder(32);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            token = builder.toString();
        } while (tokens.containsKey(token));
        return token;
    }

    // --------------------------------------------------------------------- tokens

    /**
     * Look a token up WITHOUT touching it (the {@code navstatus} endpoint). Returns null
     * for an unknown or idle-expired token.
     */
    public static Token peek(String rawToken) {
        String token = normaliseToken(rawToken);
        if (token == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            purgeLocked(now);
            return tokens.get(token);
        }
    }

    /**
     * Look a token up and stamp {@code lastUsedAt} — the call that keeps a browser paired.
     * Returns null for an unknown or idle-expired token.
     */
    public static Token use(String rawToken) {
        String token = normaliseToken(rawToken);
        if (token == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            purgeLocked(now);
            Token existing = tokens.get(token);
            if (existing == null) {
                return null;
            }
            Token touched = new Token(existing.token(), existing.playerId(), existing.playerName(),
                    existing.label(), existing.createdAt(), now);
            tokens.put(token, touched);
            markDirty();
            return touched;
        }
    }

    private static String normaliseToken(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        String token = rawToken.trim().toLowerCase(Locale.ROOT);
        if (token.length() != 32) {
            return null;
        }
        for (int i = 0; i < 32; i++) {
            if (Character.digit(token.charAt(i), 16) < 0) {
                return null;
            }
        }
        return token;
    }

    /** The caller's own paired browsers, oldest first (that is the index {@code revoke} takes). */
    public static List<Token> tokensOf(UUID playerId) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            purgeLocked(now);
            List<Token> result = new ArrayList<>();
            for (Token token : tokens.values()) {
                if (token.playerId().equals(playerId)) {
                    result.add(token);
                }
            }
            result.sort(Comparator.comparingLong(Token::createdAt));
            return result;
        }
    }

    /**
     * Revoke one of the caller's own browsers by its 1-based {@code /navpair list} index.
     *
     * @return the revoked token's label, or null when the index did not name one of theirs
     */
    public static Token revoke(UUID playerId, int oneBasedIndex) {
        List<Token> owned = tokensOf(playerId);
        if (oneBasedIndex < 1 || oneBasedIndex > owned.size()) {
            return null;
        }
        Token target = owned.get(oneBasedIndex - 1);
        synchronized (LOCK) {
            // Re-check ownership under the lock: the list above was a snapshot.
            Token current = tokens.get(target.token());
            if (current == null || !current.playerId().equals(playerId)) {
                return null;
            }
            tokens.remove(target.token());
            lastSendByToken.remove(target.token());
            markDirty();
        }
        return target;
    }

    /** Revoke every browser paired to this player. Returns how many went. */
    public static int revokeAll(UUID playerId) {
        int removed = 0;
        synchronized (LOCK) {
            Iterator<Map.Entry<String, Token>> iterator = tokens.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Token> entry = iterator.next();
                if (entry.getValue().playerId().equals(playerId)) {
                    lastSendByToken.remove(entry.getKey());
                    iterator.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                markDirty();
            }
        }
        return removed;
    }

    // ----------------------------------------------------------------- rate limit

    /**
     * The navigate endpoint's throttle: at most one accepted send per
     * {@value #SEND_COOLDOWN_MILLIS} ms per TOKEN <em>and</em> per TARGET PLAYER, so
     * neither a runaway browser nor several paired browsers can flood one HUD.
     * Consumes the slot when it returns true.
     */
    public static boolean allowSend(String token, UUID playerId) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Long lastToken = lastSendByToken.get(token);
            Long lastPlayer = lastSendByPlayer.get(playerId);
            if ((lastToken != null && now - lastToken < SEND_COOLDOWN_MILLIS)
                    || (lastPlayer != null && now - lastPlayer < SEND_COOLDOWN_MILLIS)) {
                return false;
            }
            lastSendByToken.put(token, now);
            lastSendByPlayer.put(playerId, now);
            return true;
        }
    }

    // ---------------------------------------------------------------------- purge

    /** Caller holds {@link #LOCK}. Drops expired codes and idle-expired tokens. */
    private static void purgeLocked(long now) {
        codes.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() > now) {
                return false;
            }
            codeByPlayer.remove(entry.getValue().playerId(), entry.getKey());
            return true;
        });
        boolean removedToken = tokens.entrySet().removeIf(entry -> {
            long lastUsed = Math.max(entry.getValue().lastUsedAt(), entry.getValue().createdAt());
            if (now - lastUsed <= TOKEN_IDLE_MILLIS) {
                return false;
            }
            lastSendByToken.remove(entry.getKey());
            return true;
        });
        // The throttle maps are tiny, but they must not outlive the world.
        lastSendByToken.entrySet().removeIf(entry -> now - entry.getValue() > TOKEN_IDLE_MILLIS);
        lastSendByPlayer.entrySet().removeIf(entry -> now - entry.getValue() > TOKEN_IDLE_MILLIS);
        if (removedToken) {
            markDirty();
        }
    }

    /** Caller holds {@link #LOCK}. Keeps the newest {@value #MAX_TOKENS_PER_PLAYER} per player. */
    private static void trimTokensLocked(UUID playerId) {
        List<Token> owned = new ArrayList<>();
        for (Token token : tokens.values()) {
            if (token.playerId().equals(playerId)) {
                owned.add(token);
            }
        }
        if (owned.size() <= MAX_TOKENS_PER_PLAYER) {
            return;
        }
        owned.sort(Comparator.comparingLong(Token::createdAt));
        for (int i = 0; i < owned.size() - MAX_TOKENS_PER_PLAYER; i++) {
            tokens.remove(owned.get(i).token());
            lastSendByToken.remove(owned.get(i).token());
        }
    }

    // ----------------------------------------------------------------------- save

    private static void markDirty() {
        if (SAVE_PENDING.compareAndSet(false, true)) {
            SAVE_EXECUTOR.schedule(() -> {
                SAVE_PENDING.set(false);
                saveNow();
            }, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static void saveNow() {
        Path path;
        String json;
        synchronized (LOCK) {
            path = dataPath;
            if (path == null) {
                return;
            }
            JsonObject root = new JsonObject();
            JsonObject tokensJson = new JsonObject();
            tokens.forEach((token, value) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("player", value.playerId().toString());
                entry.addProperty("name", value.playerName());
                entry.addProperty("label", value.label());
                entry.addProperty("createdAt", value.createdAt());
                entry.addProperty("lastUsedAt", value.lastUsedAt());
                tokensJson.add(token, entry);
            });
            root.add("tokens", tokensJson);
            json = GSON.toJson(root);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json);
        } catch (Exception e) {
            StationAnnouncer.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
