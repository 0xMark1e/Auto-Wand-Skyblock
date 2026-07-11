package com.nexora.hp;

import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Reads the Skyblock sidebar. Hypixel writes lines as team prefixes/suffixes on fake players and
 * fragments them with formatting codes (e.g. "Slay the b§aoss!"), so lines are composed the same
 * way vanilla renders them and stripped of codes before matching.
 */
final class Sidebar {

    private static final Pattern FORMATTING_CODE = Pattern.compile("§.");

    private Sidebar() {
    }

    /** Whether any sidebar line contains the given plain text. */
    static boolean hasLine(Minecraft client, String contains) {
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return false;
        }
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            if (entry.isHidden()) {
                continue;
            }
            PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
            String line = FORMATTING_CODE
                    .matcher(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString())
                    .replaceAll("");
            if (line.contains(contains)) {
                return true;
            }
        }
        return false;
    }
}
