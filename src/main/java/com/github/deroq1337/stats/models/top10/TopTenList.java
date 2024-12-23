package com.github.deroq1337.stats.models.top10;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public interface TopTenList {

    int getInterval();

    @NotNull Set<TopTenListEntry> getEntries();

    void print(@NotNull CommandSender commandSender);
}
