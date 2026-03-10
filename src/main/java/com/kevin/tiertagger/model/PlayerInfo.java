package com.kevin.tiertagger.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.google.gson.annotations.SerializedName;
import com.kevin.tiertagger.TierCache;
import com.kevin.tiertagger.TierTagger;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public record PlayerInfo(String uuid, String name, Map<String, Ranking> rankings, String region, int points,
                         int overall, List<Badge> badges, @SerializedName("combat_master") boolean combatMaster) {
    public record Ranking(int tier, int pos, @Nullable @SerializedName("peak_tier") Integer peakTier,
                          @Nullable @SerializedName("peak_pos") Integer peakPos, long attained,
                          boolean retired) {

        /**
         * Lower is better.
         */
        public int comparableTier() {
            return tier * 2 + pos;
        }

        /**
         * Lower is better.
         */
        public int comparablePeak() {
            if (peakTier == null || peakPos == null) {
                return Integer.MAX_VALUE;
            } else {
                return peakTier * 2 + peakPos;
            }
        }

        public NamedRanking asNamed(GameMode mode) {
            return new NamedRanking(mode, this);
        }
    }

    public record NamedRanking(@Nullable GameMode mode, Ranking ranking) {
    }

    public record Badge(String title, String desc) {
    }

    private static final Map<String, Integer> REGION_COLORS = Map.of(
            "NA", 0xff6a6e,
            "EU", 0x6aff6e,
            "SA", 0xff9900,
            "AU", 0xf6b26b,
            "ME", 0xffd966,
            "AS", 0xc27ba0,
            "AF", 0x674ea7
    );

public static CompletableFuture<PlayerInfo> get(HttpClient client, UUID uuid) {
    String endpoint = TierTagger.getManager().getConfig().getApiUrl() + "/players";
    final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(HttpResponse::body)
            .thenApply(body -> {
                JsonArray arr = TierTagger.GSON.fromJson(body, JsonArray.class);
                List<JsonObject> allPlayers = new ArrayList<>();

                JsonObject matched = null;
                String targetUuid = uuid.toString().replace("-", "");

                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    allPlayers.add(obj);

                    String mcUuid = getNullableString(obj, "mcUuid");
                    if (mcUuid != null && mcUuid.equalsIgnoreCase(targetUuid)) {
                        matched = obj;
                    }
                }

                if (matched == null) {
                    return null;
                }

                return fromSealTiersPlayer(matched, allPlayers);
            })
            .whenComplete((i, t) -> {
                if (t != null) TierTagger.getLogger().warn("Error getting player info ({})", uuid, t);
            });
}

public static CompletableFuture<Map<String, Ranking>> getRankings(HttpClient client, UUID uuid) {
    return get(client, uuid).thenApply(info -> info == null ? Map.of() : info.rankings());
}

public static CompletableFuture<PlayerInfo> search(HttpClient client, String query) {
    String endpoint = TierTagger.getManager().getConfig().getApiUrl() + "/players";
    final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(HttpResponse::body)
            .thenApply(body -> {
                JsonArray arr = TierTagger.GSON.fromJson(body, JsonArray.class);
                List<JsonObject> allPlayers = new ArrayList<>();

                JsonObject matched = null;

                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    allPlayers.add(obj);

                    String mcUsername = getNullableString(obj, "mcUsername");
                    String username = getNullableString(obj, "username");

                    if ((mcUsername != null && mcUsername.equalsIgnoreCase(query)) ||
                        (username != null && username.equalsIgnoreCase(query))) {
                        matched = obj;
                    }
                }

                if (matched == null) {
                    return null;
                }

                return fromSealTiersPlayer(matched, allPlayers);
            })
            .whenComplete((i, t) -> {
                if (t != null) TierTagger.getLogger().warn("Error searching player {}", query, t);
            });
}

private static PlayerInfo fromSealTiersPlayer(JsonObject obj, List<JsonObject> allPlayers) {
    String uuid = getNullableString(obj, "mcUuid");
    String name = getNullableString(obj, "mcUsername");
    String region = normalizeRegion(getNullableString(obj, "region"));

    Map<String, Ranking> rankings = new HashMap<>();

    addRanking(rankings, "endstone", getNullableString(obj, "endstoneTier"), getNullableString(obj, "retiredEndstoneTier"), obj);
    addRanking(rankings, "melee", getNullableString(obj, "meleeTier"), getNullableString(obj, "retiredMeleeTier"), obj);
    addRanking(rankings, "crystalSumo", getNullableString(obj, "crystalSumoTier"), getNullableString(obj, "retiredCrystalSumoTier"), obj);

    int points = calculatePoints(obj);
    int overall = calculateOverallRank(obj, allPlayers);

    return new PlayerInfo(
            uuid == null ? "" : uuid,
            name == null ? "Unknown" : name,
            rankings,
            region == null ? "NA" : region,
            points,
            overall,
            List.of(),
            false
    );
}

private static int calculatePoints(JsonObject obj) {
    int points = 0;

    points += pointsForTier(getBestTierForMode(obj, "endstone"));
    points += pointsForTier(getBestTierForMode(obj, "melee"));
    points += pointsForTier(getBestTierForMode(obj, "crystalSumo"));

    return points;
}

private static String getBestTierForMode(JsonObject obj, String mode) {
    String best = null;

    // Current active tier
    String current = switch (mode) {
        case "endstone" -> getNullableString(obj, "endstoneTier");
        case "melee" -> getNullableString(obj, "meleeTier");
        case "crystalSumo" -> getNullableString(obj, "crystalSumoTier");
        default -> null;
    };

    // Retired tier
    String retired = switch (mode) {
        case "endstone" -> getNullableString(obj, "retiredEndstoneTier");
        case "melee" -> getNullableString(obj, "retiredMeleeTier");
        case "crystalSumo" -> getNullableString(obj, "retiredCrystalSumoTier");
        default -> null;
    };

    best = betterTier(best, current);
    best = betterTier(best, retired);

    // Tier history entries look like: "HT1:1772322379069:crystalSumo"
    if (obj.has("tierHistory") && obj.get("tierHistory").isJsonArray()) {
        for (JsonElement el : obj.getAsJsonArray("tierHistory")) {
            if (el == null || el.isJsonNull()) continue;

            String entry = el.getAsString();
            String[] parts = entry.split(":");
            if (parts.length < 3) continue;

            String tierCode = parts[0];
            String historyMode = parts[2];

            if (mode.equalsIgnoreCase(historyMode)) {
                best = betterTier(best, tierCode);
            }
        }
    }

    return best;
}

private static String betterTier(String a, String b) {
    if (a == null) return b;
    if (b == null) return a;

    return compareTierCodes(a, b) <= 0 ? a : b;
}

private static int compareTierCodes(String a, String b) {
    return Integer.compare(tierSortValue(a), tierSortValue(b));
}

private static int tierSortValue(String tierCode) {
    if (tierCode == null || tierCode.isBlank()) {
        return Integer.MAX_VALUE;
    }

    tierCode = tierCode.toUpperCase(Locale.ROOT);

    if (tierCode.startsWith("R")) {
        tierCode = tierCode.substring(1);
    }

    if (tierCode.length() < 3) {
        return Integer.MAX_VALUE;
    }

    char band = tierCode.charAt(0); // H or L
    int tierNumber;

    try {
        tierNumber = Integer.parseInt(tierCode.substring(2));
    } catch (NumberFormatException e) {
        return Integer.MAX_VALUE;
    }

    int pos = (band == 'H') ? 0 : 1;

    return tierNumber * 2 + pos;
}

private static int calculateOverallRank(JsonObject target, List<JsonObject> allPlayers) {
    int targetId = target.get("id").getAsInt();

    List<Map.Entry<Integer, Integer>> scoredPlayers = new ArrayList<>();

    for (JsonObject obj : allPlayers) {
        if (!obj.has("id") || obj.get("id").isJsonNull()) {
            continue;
        }

        int id = obj.get("id").getAsInt();
        int points = calculatePoints(obj);
        scoredPlayers.add(Map.entry(id, points));
    }

    scoredPlayers.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

    for (int i = 0; i < scoredPlayers.size(); i++) {
        if (scoredPlayers.get(i).getKey() == targetId) {
            return i + 1;
        }
    }

    return 0;
}

private static int pointsForTier(String tierCode) {
    if (tierCode == null || tierCode.length() < 3) {
        return 0;
    }

    tierCode = tierCode.toUpperCase(Locale.ROOT);

    // Remove retired prefix
    if (tierCode.startsWith("R")) {
        tierCode = tierCode.substring(1);
    }

    return switch (tierCode) {
        case "HT1" -> 60;
        case "LT1" -> 45;

        case "HT2" -> 30;
        case "LT2" -> 20;

        case "HT3" -> 10;
        case "LT3" -> 6;

        case "HT4" -> 4;
        case "LT4" -> 3;

        case "HT5" -> 2;
        case "LT5" -> 1;

        default -> 0;
    };
}

private static void addRanking(Map<String, Ranking> rankings, String mode, String activeTier, String retiredTier, JsonObject obj) {
    String shownTier = activeTier != null ? activeTier : retiredTier;
    boolean retired = activeTier == null && retiredTier != null;

    if (shownTier == null) {
        return;
    }

    Ranking current = parseSealTier(shownTier, retired);
    String peakCode = getBestTierForMode(obj, mode);

    Integer peakTier = null;
    Integer peakPos = null;

    if (peakCode != null) {
        PeakParts peak = parsePeakParts(peakCode);
        peakTier = peak.tier();
        peakPos = peak.pos();
    }

    long attained = findAttainedForTier(obj, mode, shownTier);

    rankings.put(mode, new Ranking(
            current.tier(),
            current.pos(),
            peakTier,
            peakPos,
            attained,
            retired
    ));
}

private static long findAttainedForTier(JsonObject obj, String mode, String tierCode) {
    if (tierCode == null || mode == null) {
        return 0L;
    }

    String normalizedTier = tierCode.toUpperCase(Locale.ROOT);

    // Remove retired prefix so RHT1 matches HT1 in history if needed
    if (normalizedTier.startsWith("R")) {
        normalizedTier = normalizedTier.substring(1);
    }

    long latestMatch = 0L;

    if (obj.has("tierHistory") && obj.get("tierHistory").isJsonArray()) {
        for (JsonElement el : obj.getAsJsonArray("tierHistory")) {
            if (el == null || el.isJsonNull()) continue;

            String entry = el.getAsString();
            String[] parts = entry.split(":");
            if (parts.length < 3) continue;

            String historyTier = parts[0].toUpperCase(Locale.ROOT);
            String historyMode = parts[2];

            if (!mode.equalsIgnoreCase(historyMode)) {
                continue;
            }

            if (!normalizedTier.equals(historyTier)) {
                continue;
            }

            try {
                long timestamp = Long.parseLong(parts[1]);

                // use the most recent time they attained this displayed tier
                if (timestamp > latestMatch) {
                    latestMatch = timestamp;
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    return latestMatch;
}

private static Ranking parseSealTier(String tierCode, boolean retired) {
    if (tierCode == null || tierCode.length() < 3) {
        return new Ranking(999, 1, null, null, 0L, retired);
    }

    tierCode = tierCode.toUpperCase(Locale.ROOT);

    // Remove retired prefix like RHT1 -> HT1
    if (tierCode.startsWith("R")) {
        tierCode = tierCode.substring(1);
    }

    if (tierCode.length() < 3) {
        return new Ranking(999, 1, null, null, 0L, retired);
    }

    char highLow = tierCode.charAt(0);
    int tierNumber;

    try {
        tierNumber = Integer.parseInt(tierCode.substring(2));
    } catch (NumberFormatException e) {
        tierNumber = 999;
    }

    int pos = (highLow == 'H') ? 0 : 1;

    return new Ranking(tierNumber, pos, null, null, 0L, retired);
}

private static PeakParts parsePeakParts(String tierCode) {
    if (tierCode == null || tierCode.length() < 3) {
        return new PeakParts(null, null);
    }

    tierCode = tierCode.toUpperCase(Locale.ROOT);

    if (tierCode.startsWith("R")) {
        tierCode = tierCode.substring(1);
    }

    try {
        int tier = Integer.parseInt(tierCode.substring(2));
        int pos = tierCode.charAt(0) == 'H' ? 0 : 1;
        return new PeakParts(tier, pos);
    } catch (NumberFormatException e) {
        return new PeakParts(null, null);
    }
}

private record PeakParts(Integer tier, Integer pos) {}

private static String getNullableString(JsonObject obj, String key) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) {
        return null;
    }
    return obj.get(key).getAsString();
}

private static String normalizeRegion(String region) {
    if (region == null) return "NA";

    return switch (region) {
        case "North America" -> "NA";
        case "Europe" -> "EU";
        case "South America" -> "SA";
        case "Asia" -> "AS";
        case "Africa" -> "AF";
        case "Australia", "Oceania" -> "AU";
        case "Middle East" -> "ME";
        default -> region;
    };
}
    public int getRegionColor() {
        return REGION_COLORS.getOrDefault(this.region.toUpperCase(Locale.ROOT), 0xffffff);
    }

    public static Optional<NamedRanking> getHighestRanking(Map<String, Ranking> rankings) {
        return rankings.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .min(Comparator.comparingInt(e -> e.getValue().comparableTier()))
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())));
    }

    @Getter
    @AllArgsConstructor
    public enum PointInfo {
        LEVIATHAN_SEAL("Leviathan Seal", 0xE6C622, 0xFDE047),
        APEX_SEAL("Apex Seal", 0xFBB03B, 0xFFD13A),
        LEOPARD_SEAL("Leopard Seal", 0xCD285C, 0xD65474),
        RIBBON_SEAL("Ribbon Seal", 0xAD78D8, 0xC7A3E8),
        HARBOR_SEAL("Harbour Seal", 0x9291D9, 0xADACE2),
        GREY_SEAL("Grey Seal", 0x9291D9, 0xFFFFFF),
        PUP("Pup", 0x6C7178, 0x8B979C),
        UNRANKED("Unranked", 0xFFFFFF, 0xFFFFFF);

        private final String title;
        private final int color;
        private final int accentColor;
    }

    public PointInfo getPointInfo() {
        if (this.points >= 400) {
            return PointInfo.LEVIATHAN_SEAL;
        } else if (this.points >= 250) {
            return PointInfo.APEX_SEAL;
        } else if (this.points >= 100) {
            return PointInfo.LEOPARD_SEAL;
        } else if (this.points >= 50) {
            return PointInfo.RIBBON_SEAL;
        } else if (this.points >= 20) {
            return PointInfo.HARBOR_SEAL;
        } else if (this.points >= 10) {
            return PointInfo.GREY_SEAL;
        } else if (this.points >= 0) {
            return PointInfo.PUP;
        } else {
            return PointInfo.UNRANKED;
        }
    }

    public List<NamedRanking> getSortedTiers() {
        List<NamedRanking> tiers = new ArrayList<>(this.rankings.entrySet().stream()
                .map(e -> e.getValue().asNamed(TierCache.findModeOrUgly(e.getKey())))
                .toList());

        tiers.sort(Comparator.comparing((NamedRanking a) -> a.ranking.retired, Boolean::compare)
                .thenComparingInt(a -> a.ranking.tier)
                .thenComparingInt(a -> a.ranking.pos));

        return tiers;
    }
}
