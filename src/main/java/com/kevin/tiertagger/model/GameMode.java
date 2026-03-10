package com.kevin.tiertagger.model;

import net.minecraft.resources.ResourceLocation;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public record GameMode(String id, String title) {
    public static final GameMode NONE = new GameMode("annoying_long_id_that_no_one_will_ever_use_just_to_make_sure", "§cNone§r");

public static CompletableFuture<List<GameMode>> fetchGamemodes(HttpClient client) {
    return CompletableFuture.completedFuture(List.of(
            new GameMode("endstone", "Endstone"),
            new GameMode("melee", "Melee"),
            new GameMode("crystalSumo", "Crystal Sumo")
    ));
}

    public boolean isNone() {
        return this.id.equals(NONE.id);
    }

    private Pair<Character, TextColor> iconAndColor() {
        return switch (this.id) {
            case "melee" -> Pair.of('\uE701', TextColor.fromRgb(0xffc800));
            case "endstone" -> Pair.of('\uE702', TextColor.fromRgb(0xfeffe3));
            case "crystalSumo" -> Pair.of('\uE703', TextColor.fromRgb(0xdd00ff));
            default -> Pair.of('•', TextColor.fromLegacyFormat(ChatFormatting.WHITE));
        };
    }

public Component asStyled(boolean withDefaultDot) {
    Pair<Character, TextColor> pair = this.iconAndColor();

    if (pair.right().getValue() == 0xFFFFFF && !withDefaultDot) {
        return Component.literal(this.title);
    } else {
        Component icon = Component.literal(String.valueOf(pair.left()))
                .withStyle(s -> s
                        .withColor(pair.right())
                        .withFont(new ResourceLocation("tier-tagger", "icons")));

        Component name = Component.literal(" " + this.title)
                .withStyle(s -> s.withColor(pair.right()));

        return icon.append(name);
    }
}
