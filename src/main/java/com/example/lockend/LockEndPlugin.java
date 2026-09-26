package com.example.lockend;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public class LockEndPlugin extends JavaPlugin implements Listener {
    private static final DateTimeFormatter AUTO_UNLOCK_DISPLAY = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final long[] COUNTDOWN_THRESHOLDS_SECONDS = {
            86400, 43200, 21600, 3600, 1800, 600, 300, 60
    };

    private boolean locked;
    private String message;

    private boolean autoUnlockEnabled;
    private ZoneId autoUnlockZone;
    private DayOfWeek autoUnlockDayOfWeek;
    private Integer autoUnlockDayOfMonth;
    private LocalTime autoUnlockTime;
    private String lastAutoUnlockDate;
    private BukkitTask autoUnlockTask;
    private String countdownTargetKey;
    private final Set<Long> announcedCountdownThresholds = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand lockEndCommand = getCommand("lockend");
        if (lockEndCommand != null) {
            lockEndCommand.setExecutor(this);
            lockEndCommand.setTabCompleter(this);
        }
        restartAutoUnlockTask();
        if (locked) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                ensureNotInEnd(online);
            }
        }
        announceNextUnlockToPlayers();
        getLogger().info("LockEndPlugin enabled. End locked: " + locked);
    }

    @Override
    public void onDisable() {
        cancelAutoUnlockTask();
        getLogger().info("LockEndPlugin disabled.");
    }

    private void loadSettings() {
        FileConfiguration cfg = getConfig();
        locked = cfg.getBoolean("lock-end", true);
        message = ChatColor.translateAlternateColorCodes('&', cfg.getString("kick-message", "&cThe End hien dang khoa!"));
        if (message == null || message.isEmpty()) {
            message = ChatColor.RED + "The End is currently locked.";
        }
        loadAutoUnlockSettings(cfg);
        resetCountdownState();
    }

    private void loadAutoUnlockSettings(FileConfiguration cfg) {
        autoUnlockEnabled = cfg.getBoolean("auto-unlock.enabled", false);
        lastAutoUnlockDate = cfg.getString("auto-unlock.last-run", "");
        String timezoneId = cfg.getString("auto-unlock.timezone", ZoneId.systemDefault().getId());
        try {
            autoUnlockZone = ZoneId.of(timezoneId);
        } catch (Exception ex) {
            autoUnlockZone = ZoneId.systemDefault();
            getLogger().log(Level.WARNING, "Invalid timezone '" + timezoneId + "', using system default.", ex);
        }
        String timeString = cfg.getString("auto-unlock.time", "20:00");
        try {
            autoUnlockTime = LocalTime.parse(timeString);
        } catch (DateTimeParseException ex) {
            autoUnlockTime = null;
            getLogger().log(Level.WARNING, "Invalid auto unlock time '" + timeString + "'.", ex);
        }
        String dowString = cfg.getString("auto-unlock.day-of-week", "").trim();
        if (!dowString.isEmpty()) {
            try {
                autoUnlockDayOfWeek = DayOfWeek.valueOf(dowString.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                autoUnlockDayOfWeek = null;
                getLogger().log(Level.WARNING, "Invalid day-of-week '" + dowString + "', ignoring value.", ex);
            }
        } else {
            autoUnlockDayOfWeek = null;
        }
        int dom = cfg.getInt("auto-unlock.day-of-month", -1);
        if (dom >= 1 && dom <= 31) {
            autoUnlockDayOfMonth = dom;
        } else {
            autoUnlockDayOfMonth = null;
        }
    }

    private void resetCountdownState() {
        countdownTargetKey = null;
        announcedCountdownThresholds.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!locked) return;
        Location to = event.getTo();
        if (to != null && to.getWorld() != null && to.getWorld().getEnvironment() == World.Environment.THE_END) {
            Player player = event.getPlayer();
            Location from = event.getFrom();
            event.setCancelled(true);
            event.setTo(from);
            player.sendMessage(message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!locked) return;
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        if (to.getWorld().getEnvironment() != World.Environment.THE_END) return;
        Location from = event.getFrom();
        if (from != null
                && from.getWorld() != null
                && from.getWorld().getEnvironment() == World.Environment.THE_END) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(message);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        ensureNotInEnd(event.getPlayer());
        if (locked && autoUnlockEnabled && autoUnlockTime != null) {
            ZonedDateTime now = ZonedDateTime.now(autoUnlockZone);
            ZonedDateTime next = findNextScheduledTime(now);
            if (next != null) {
                long seconds = Duration.between(now, next).getSeconds();
                if (seconds > 0) {
                    event.getPlayer().sendMessage(ChatColor.GOLD + "[LockEnd] The End se mo trong "
                            + formatDuration(seconds) + " (luc " + next.format(AUTO_UNLOCK_DISPLAY) + ").");
                }
            }
        }
    }

    private void ensureNotInEnd(Player player) {
        if (!locked) return;
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;
        Location spawn = getOverworldSpawn();
        if (spawn != null) {
            player.teleport(spawn);
            player.sendMessage(message);
        }
    }

    private Location getOverworldSpawn() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world.getSpawnLocation();
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!locked) return;
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || to.getWorld().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity.getPassengers().isEmpty()) return;
        boolean cancelled = false;
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player player) {
                player.sendMessage(message);
                cancelled = true;
            }
        }
        if (cancelled) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lockend.admin")) {
            sender.sendMessage(ChatColor.RED + "Ban khong co quyen su dung lenh nay.");
            return true;
        }

        if (!command.getName().equalsIgnoreCase("lockend")) return false;

        if (args.length != 1) {
            sendUsage(sender);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "lock" -> {
                if (locked) {
                    sender.sendMessage(ChatColor.YELLOW + "The End da duoc khoa san roi.");
                    break;
                }
                locked = true;
                FileConfiguration cfg = getConfig();
                cfg.set("lock-end", true);
                saveConfig();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    ensureNotInEnd(online);
                }
                sender.sendMessage(ChatColor.GREEN + "The End da bi khoa. Nguoi choi khong the vao The End.");
                announceNextUnlockToPlayers();
            }
            case "unlock" -> {
                if (!locked) {
                    sender.sendMessage(ChatColor.YELLOW + "The End dang o trang thai mo khoa.");
                    break;
                }
                locked = false;
                FileConfiguration cfg = getConfig();
                cfg.set("lock-end", false);
                saveConfig();
                resetCountdownState();
                sender.sendMessage(ChatColor.GREEN + "The End da duoc mo khoa. Nguoi choi co the vao/ra binh thuong.");
            }
            case "reload" -> {
                reloadConfig();
                loadSettings();
                restartAutoUnlockTask();
                sender.sendMessage(ChatColor.GREEN + "Da tai lai cau hinh LockEnd.");
                announceNextUnlockToPlayers();
            }
            default -> {
                sendUsage(sender);
                return true;
            }
        }
        sender.sendMessage(ChatColor.AQUA + "Trang thai hien tai: " + (locked ? ChatColor.RED + "Khoa" : ChatColor.GREEN + "Mo"));
        notifyNextUnlock(sender);
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Su dung: /lockend <lock|unlock|reload>");
        sender.sendMessage(ChatColor.GRAY + " - lock: Khoa The End, chan nguoi choi vao.");
        sender.sendMessage(ChatColor.GRAY + " - unlock: Mo khoa The End, cho phep vao lai.");
        sender.sendMessage(ChatColor.GRAY + " - reload: Tai lai config (bao gom lich mo khoa).");
        sender.sendMessage(ChatColor.AQUA + "Trang thai hien tai: " + (locked ? ChatColor.RED + "Khoa" : ChatColor.GREEN + "Mo"));
        notifyNextUnlock(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lockend.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> options = Arrays.asList("lock", "unlock", "reload");
            List<String> matches = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String opt : options) {
                if (opt.startsWith(prefix)) {
                    matches.add(opt);
                }
            }
            return matches;
        }
        return Collections.emptyList();
    }

    private void notifyNextUnlock(CommandSender sender) {
        if (!autoUnlockEnabled || autoUnlockTime == null) return;
        ZonedDateTime now = ZonedDateTime.now(autoUnlockZone);
        ZonedDateTime next = findNextScheduledTime(now);
        if (next == null) return;
        long seconds = Duration.between(now, next).getSeconds();
        if (seconds <= 0) return;
        sender.sendMessage(ChatColor.GOLD + "The End se mo tu dong trong " + formatDuration(seconds)
                + " (luc " + next.format(AUTO_UNLOCK_DISPLAY) + " " + autoUnlockZone.getId() + ").");
    }

    private void announceNextUnlockToPlayers() {
        if (!autoUnlockEnabled || autoUnlockTime == null || !locked) return;
        ZonedDateTime now = ZonedDateTime.now(autoUnlockZone);
        ZonedDateTime next = findNextScheduledTime(now);
        if (next == null) return;
        long seconds = Duration.between(now, next).getSeconds();
        if (seconds <= 0) return;
        countdownTargetKey = next.toInstant().toString();
        announcedCountdownThresholds.clear();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[LockEnd] The End se mo trong " + formatDuration(seconds)
                + " (luc " + next.format(AUTO_UNLOCK_DISPLAY) + " " + autoUnlockZone.getId() + ").");
    }

    private ZonedDateTime findNextScheduledTime(ZonedDateTime reference) {
        if (!autoUnlockEnabled || autoUnlockTime == null) return null;
        ZonedDateTime candidate = reference.withHour(autoUnlockTime.getHour())
                .withMinute(autoUnlockTime.getMinute())
                .withSecond(0)
                .withNano(0);
        if (!candidate.isAfter(reference)) {
            candidate = candidate.plusDays(1).withHour(autoUnlockTime.getHour())
                    .withMinute(autoUnlockTime.getMinute())
                    .withSecond(0)
                    .withNano(0);
        }
        for (int i = 0; i < 366; i++) {
            boolean matchesDayOfWeek = autoUnlockDayOfWeek == null || candidate.getDayOfWeek() == autoUnlockDayOfWeek;
            boolean matchesDayOfMonth = autoUnlockDayOfMonth == null || candidate.getDayOfMonth() == autoUnlockDayOfMonth;
            if (matchesDayOfWeek && matchesDayOfMonth) {
                return candidate;
            }
            candidate = candidate.plusDays(1).withHour(autoUnlockTime.getHour())
                    .withMinute(autoUnlockTime.getMinute())
                    .withSecond(0)
                    .withNano(0);
        }
        return null;
    }

    private void handleCountdown(ZonedDateTime now) {
        if (!locked) return;
        ZonedDateTime next = findNextScheduledTime(now);
        if (next == null) return;
        long secondsRemaining = Duration.between(now, next).getSeconds();
        if (secondsRemaining <= 0) return;
        String key = next.toInstant().toString();
        if (!key.equals(countdownTargetKey)) {
            countdownTargetKey = key;
            announcedCountdownThresholds.clear();
            Bukkit.broadcastMessage(ChatColor.GOLD + "[LockEnd] The End se mo trong " + formatDuration(secondsRemaining)
                    + " (luc " + next.format(AUTO_UNLOCK_DISPLAY) + " " + autoUnlockZone.getId() + ").");
        }
        for (long threshold : COUNTDOWN_THRESHOLDS_SECONDS) {
            if (secondsRemaining <= threshold && announcedCountdownThresholds.add(threshold)) {
                Bukkit.broadcastMessage(ChatColor.YELLOW + "[LockEnd] Con " + formatDuration(secondsRemaining)
                        + " nua The End se mo khoa!");
            }
        }
    }

    private String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + " ngay");
        if (hours > 0) parts.add(hours + " gio");
        if (minutes > 0) parts.add(minutes + " phut");
        if (parts.isEmpty()) parts.add("duoi 1 phut");
        return String.join(" ", parts);
    }

    private void restartAutoUnlockTask() {
        cancelAutoUnlockTask();
        resetCountdownState();
        if (!autoUnlockEnabled || autoUnlockTime == null) return;
        autoUnlockTask = Bukkit.getScheduler().runTaskTimer(this, this::checkAutoUnlock, 20L, 20L * 30);
    }

    private void cancelAutoUnlockTask() {
        if (autoUnlockTask != null) {
            autoUnlockTask.cancel();
            autoUnlockTask = null;
        }
    }

    private void checkAutoUnlock() {
        if (!autoUnlockEnabled || autoUnlockTime == null) return;
        ZonedDateTime now = ZonedDateTime.now(autoUnlockZone);
        handleCountdown(now);
        if (!matchesAutoUnlockSchedule(now)) {
            return;
        }
        String today = now.toLocalDate().toString();
        if (today.equalsIgnoreCase(lastAutoUnlockDate != null ? lastAutoUnlockDate : "")) {
            return;
        }
        lastAutoUnlockDate = today;
        FileConfiguration cfg = getConfig();
        cfg.set("auto-unlock.last-run", today);
        if (locked) {
            locked = false;
            cfg.set("lock-end", false);
            saveConfig();
            resetCountdownState();
            Bukkit.broadcastMessage(ChatColor.GREEN + "[LockEnd] The End da duoc mo tu dong vao luc "
                    + now.format(AUTO_UNLOCK_DISPLAY) + " (" + autoUnlockZone.getId() + ").");
        } else {
            saveConfig();
        }
    }

    private boolean matchesAutoUnlockSchedule(ZonedDateTime now) {
        if (autoUnlockTime == null) return false;
        if (now.getHour() != autoUnlockTime.getHour() || now.getMinute() != autoUnlockTime.getMinute()) {
            return false;
        }
        if (autoUnlockDayOfWeek != null && now.getDayOfWeek() != autoUnlockDayOfWeek) {
            return false;
        }
        if (autoUnlockDayOfMonth != null && now.getDayOfMonth() != autoUnlockDayOfMonth) {
            return false;
        }
        return true;
    }
}
