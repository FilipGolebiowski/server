package gg.elcartel.paper.board;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Prosty, niemigoczacy Scoreboard bazujacy na zespolech (Teams) Bukkit API.
 * Kompatybilny z Paper 1.21+ (Adventure API).
 */
public class FastBoard {

    private final Player player;
    private final Scoreboard scoreboard;
    private final Objective objective;
    private final List<Component> lines = new ArrayList<>();

    public FastBoard(Player player) {
        this.player = player;
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective("elboard", Criteria.DUMMY, Component.empty());
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.player.setScoreboard(scoreboard);
    }

    public void updateTitle(Component title) {
        this.objective.displayName(title);
    }

    public void updateLines(List<Component> newLines) {
        // Upewniamy się, że nie przekraczamy limitu 15 linii
        int size = Math.min(15, newLines.size());

        // Usuwanie niepotrzebnych starszych linii (jeśli nowa lista jest krótsza)
        for (int i = lines.size(); i > size; i--) {
            int scoreIndex = i - 1;
            String entry = ChatColor.values()[scoreIndex].toString();
            scoreboard.resetScores(entry);
            Team team = scoreboard.getTeam("line_" + scoreIndex);
            if (team != null) {
                team.unregister();
            }
        }

        // Aktualizacja/tworzenie linii
        for (int i = 0; i < size; i++) {
            Team team = scoreboard.getTeam("line_" + i);
            if (team == null) {
                team = scoreboard.registerNewTeam("line_" + i);
            }
            
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
            
            team.prefix(newLines.get(i));
            // Wynik na scoreboardzie idzie w dol, np. 15, 14, 13...
            objective.getScore(entry).setScore(15 - i);
        }

        this.lines.clear();
        this.lines.addAll(newLines.subList(0, size));
    }

    public void delete() {
        this.objective.unregister();
        for (Team team : scoreboard.getTeams()) {
            if (team.getName().startsWith("line_")) {
                team.unregister();
            }
        }
    }

    public Player getPlayer() {
        return player;
    }
}
