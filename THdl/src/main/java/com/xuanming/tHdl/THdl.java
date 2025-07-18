package com.xuanming.tHdl;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public class THdl extends JavaPlugin implements Listener, PluginMessageListener, TabCompleter {
    private Location spawnLocation;
    private FileConfiguration config;
    private List<String> joinMessages;
    private List<String> quitMessages;
    private boolean enableJoinMessage;
    private boolean enableQuitMessage;
    private boolean enableVelocitySupport;
    private Map<String, String> serverPortals;
    private Map<String, Hologram> holograms;
    private Map<String, Integer> serverPlayerCounts;
    private int totalOnlinePlayers;
    private Map<String, Long> areaTeleportCooldowns;
    private Map<String, Long> areaTeleportMessageCooldowns;
    private Map<String, Location> areaSelectionPoints;
    private Random random = new Random();

    // 空间分区索引优化
    private Map<String, Map<String, List<Map<?, ?>>>> areaIndex; // world -> chunkKey -> areas

    // 全息影像分帧刷新优化
    private List<Hologram> hologramUpdateQueue;
    private int hologramUpdateIndex = 0;
    private static final int HOLOGRAM_BATCH_SIZE = 5; // 每tick刷新5个全息影像

    // 区域检测缓存优化
    private Map<String, Boolean> playerInAreaCache; // 玩家 -> 是否在区域内
    private Map<String, String> playerLastChunk; // 玩家 -> 上次检查的区块键
    private Map<String, Long> playerLastCheckTime; // 玩家 -> 上次检查时间
    private static final long CACHE_DURATION = 500L; // 缓存500毫秒，减少频繁检测

    // 新增：玩家加入时间记录，防止刚加入就触发传送
    private Map<String, Long> playerJoinTime; // 玩家 -> 加入时间
    private static final long JOIN_PROTECTION_DURATION = 3000L; // 加入后3秒内不触发传送

    // 新增：传送状态管理
    private Map<String, Boolean> playerTeleportingStatus; // 玩家 -> 是否正在传送中
    private Map<String, Long> playerLastTeleportTime; // 玩家 -> 上次传送时间

    private static final int AREA_CHECK_BATCH_SIZE = 20;
    private int areaCheckIndex = 0;

    @Override
    public void onEnable() {
        getLogger().info("桃韵斋-大厅插件 已启用");
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        config = getConfig();
        loadSpawnLocation();
        loadJoinQuitMessages();
        loadServerPortals();
        registerVelocitySupport();
        startServerInfoTask();
        areaTeleportCooldowns = new ConcurrentHashMap<>();
        areaTeleportMessageCooldowns = new ConcurrentHashMap<>();
        areaSelectionPoints = new ConcurrentHashMap<>();
        loadAreaTeleportConfig();
        playerInAreaCache = new ConcurrentHashMap<>();
        playerLastChunk = new ConcurrentHashMap<>();
        playerLastCheckTime = new ConcurrentHashMap<>();
        playerJoinTime = new ConcurrentHashMap<>();
        playerTeleportingStatus = new ConcurrentHashMap<>();
        playerLastTeleportTime = new ConcurrentHashMap<>();
        buildAreaIndex();
        holograms = new ConcurrentHashMap<>();
        hologramUpdateQueue = new ArrayList<>();
        startHologramBatchUpdateTask();
        getCommand("tyz").setTabCompleter(this);
        startAreaCheckTask();
        // 启动时主动检测主世界是否已加载，若已加载则立即加载全息影像
        org.bukkit.World mainWorld = getServer().getWorld("world");
        if (mainWorld != null) {
            loadHolograms();
        }
    }

    @Override
    public void onDisable() {
        // 清理所有全息影像
        clearAllHolograms(false); // 在禁用时不要保存配置

        getLogger().info("桃韵斋-大厅插件 已禁用");
    }

    private void loadSpawnLocation() {
        if (config.contains("spawn.world")) {
            String worldName = config.getString("spawn.world");
            double x = config.getDouble("spawn.x");
            double y = config.getDouble("spawn.y");
            double z = config.getDouble("spawn.z");
            float yaw = (float) config.getDouble("spawn.yaw");
            float pitch = (float) config.getDouble("spawn.pitch");
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                spawnLocation = new Location(world, x, y, z, yaw, pitch);
                getLogger().info("出生点已加载: " + formatLocation(spawnLocation));
            } else {
                getLogger().warning("出生点世界不存在: " + worldName);
            }
        }
    }

    private void loadJoinQuitMessages() {
        enableJoinMessage = config.getBoolean("join-quit-messages.join.enabled", true);
        enableQuitMessage = config.getBoolean("join-quit-messages.quit.enabled", true);

        joinMessages = config.getStringList("join-quit-messages.join.messages");
        quitMessages = config.getStringList("join-quit-messages.quit.messages");

        // 如果没有配置消息，使用默认消息
        if (joinMessages.isEmpty()) {
            joinMessages.add("&a欢迎 {player} 加入服务器！");
        }
        if (quitMessages.isEmpty()) {
            quitMessages.add("&c{player} 离开了服务器");
        }

        getLogger().info("已加载 " + joinMessages.size() + " 条加入消息");
        getLogger().info("已加载 " + quitMessages.size() + " 条退出消息");
    }

    private void loadServerPortals() {
        enableVelocitySupport = config.getBoolean("velocity-support.enabled", true);
        serverPortals = new ConcurrentHashMap<>();
        serverPlayerCounts = new ConcurrentHashMap<>();

        if (enableVelocitySupport) {
            ConfigurationSection serversSection = config.getConfigurationSection("velocity-support.servers");
            if (serversSection != null) {
                for (String serverName : serversSection.getKeys(false)) {
                    String serverAddress = serversSection.getString(serverName);
                    serverPortals.put(serverName.toLowerCase(), serverAddress.toLowerCase());
                    serverPlayerCounts.put(serverAddress.toLowerCase(), 0);
                }
            }
            getLogger().info("已加载 " + serverPortals.size() + " 个服务器传送点");
        }
    }

    private void cleanupServerPlayerCounts() {
        // 清理可能存在的重复数据（不同大小写）
        Map<String, Integer> cleanedCounts = new ConcurrentHashMap<>();
        for (Map.Entry<String, Integer> entry : serverPlayerCounts.entrySet()) {
            String key = entry.getKey().toLowerCase();
            Integer value = entry.getValue();
            if (cleanedCounts.containsKey(key)) {
                // 如果存在重复，取最大值
                cleanedCounts.put(key, Math.max(cleanedCounts.get(key), value));
            } else {
                cleanedCounts.put(key, value);
            }
        }
        serverPlayerCounts = cleanedCounts;
    }

    private void loadHolograms() {
        try {
            if (holograms == null) {
                holograms = new ConcurrentHashMap<>();
            } else {
                holograms.clear();
            }
            ConfigurationSection hologramsSection = config.getConfigurationSection("holograms");
            if (hologramsSection != null) {
                for (String name : hologramsSection.getKeys(false)) {
                    ConfigurationSection hologramSection = hologramsSection.getConfigurationSection(name);
                    if (hologramSection != null) {
                        String worldName = hologramSection.getString("world");
                        double x = hologramSection.getDouble("x");
                        double y = hologramSection.getDouble("y");
                        double z = hologramSection.getDouble("z");
                        List<String> originalLines = hologramSection.getStringList("lines");
                        World world = Bukkit.getWorld(worldName);
                        if (world != null) {
                            Location location = new Location(world, x, y, z);
                            List<String> processedLines = new ArrayList<>();
                            for (String line : originalLines) {
                                String processedLine = replaceVariables(line, Bukkit.getConsoleSender());
                                processedLines.add(processedLine);
                            }
                            Hologram hologram = new Hologram(this, name, location, processedLines);
                            hologram.setOriginalLines(originalLines);
                            holograms.put(name, hologram);
                            Bukkit.getRegionScheduler().execute(this, location, () -> {
                                hologram.spawn();
                            });
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("[loadHolograms] 加载全息影像时异常: " + e.getMessage());
            e.printStackTrace();
        }
        hologramUpdateQueue = new ArrayList<>(holograms.values());
    }

    private void loadAreaTeleportConfig() {
        if (!config.contains("area-teleport.areas")) {
            config.set("area-teleport.areas", new ArrayList<>());
            saveConfig();
            getLogger().warning("未检测到 area-teleport.areas 配置，已自动修复为默认空列表。");
        }
        // 区域传送功能配置会在第一次使用时自动创建默认配置
        if (!config.contains("area-teleport")) {
            config.set("area-teleport.enabled", true);
            config.set("area-teleport.server", "zcdq");
            config.set("area-teleport.cooldown-seconds", 30);
            config.set("area-teleport.message", "&a检测到玩家进入传送区域，正在传送到子服...");
            config.set("area-teleport.cooldown-message", "&c传送冷却中，请稍后再试！");
            config.set("area-teleport.message-cooldown-seconds", 5);
            config.set("area-teleport.selection-tool", "STICK");
            config.set("area-teleport.areas", new ArrayList<>());
            try {
                config.save(new File(getDataFolder(), "config.yml"));
                getLogger().info("区域传送配置已创建");
            } catch (IOException e) {
                getLogger().severe("保存区域传送配置失败: " + e.getMessage());
            }
        } else {
            getLogger().info("区域传送配置已加载，当前区域数量: " + config.getMapList("area-teleport.areas").size());
        }
    }

    /**
     * 构建空间分区索引，按世界和区块分组区域
     * 大幅提升区域检测效率，从O(n)优化到O(1)
     */
    private void buildAreaIndex() {
        areaIndex = new ConcurrentHashMap<>();
        List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");

        for (Map<?, ?> area : areas) {
            String worldName = (String) area.get("world");
            double x1 = (Double) area.get("x1");
            double z1 = (Double) area.get("z1");
            double x2 = (Double) area.get("x2");
            double z2 = (Double) area.get("z2");

            // 计算区域覆盖的所有区块
            int minChunkX = (int) Math.floor(Math.min(x1, x2) / 16);
            int maxChunkX = (int) Math.floor(Math.max(x1, x2) / 16);
            int minChunkZ = (int) Math.floor(Math.min(z1, z2) / 16);
            int maxChunkZ = (int) Math.floor(Math.max(z1, z2) / 16);

            // 将区域添加到所有覆盖的区块中
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    String chunkKey = chunkX + "," + chunkZ;
                    areaIndex.computeIfAbsent(worldName, k -> new HashMap<>())
                            .computeIfAbsent(chunkKey, k -> new ArrayList<>())
                            .add(area);
                }
            }
        }

        getLogger().info("空间分区索引构建完成，覆盖 " + areaIndex.size() + " 个世界");
    }

    /**
     * 获取位置对应的区块键
     */
    private String getChunkKey(Location location) {
        int chunkX = (int) Math.floor(location.getX() / 16);
        int chunkZ = (int) Math.floor(location.getZ() / 16);
        return chunkX + "," + chunkZ;
    }

    /**
     * 启动全息影像分帧刷新任务
     * 每tick只刷新部分全息影像，避免瞬时主线程压力
     */
    private void startHologramBatchUpdateTask() {
        if (isFolia()) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, scheduledTask -> {
                if (hologramUpdateQueue.isEmpty()) {
                    return;
                }
                for (int i = 0; i < HOLOGRAM_BATCH_SIZE && hologramUpdateIndex < hologramUpdateQueue.size(); i++) {
                    Hologram hologram = hologramUpdateQueue.get(hologramUpdateIndex);
                    try {
                        // Folia: 分发到 RegionScheduler
                        Location loc = hologram.getLocation();
                        if (loc != null && loc.getWorld() != null) {
                            World world = loc.getWorld();
                            Bukkit.getRegionScheduler().execute(this, loc, () -> updateHologramVariables(hologram));
                        }
                    } catch (Exception e) {
                        getLogger().warning("更新全息影像 " + hologram.getName() + " 时发生错误: " + e.getMessage());
                    }
                    hologramUpdateIndex++;
                }
                if (hologramUpdateIndex >= hologramUpdateQueue.size()) {
                    hologramUpdateIndex = 0;
                }
            }, 20L, 1L); // tick
        } else {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (hologramUpdateQueue.isEmpty()) {
                    return;
                }
                for (int i = 0; i < HOLOGRAM_BATCH_SIZE && hologramUpdateIndex < hologramUpdateQueue.size(); i++) {
                    Hologram hologram = hologramUpdateQueue.get(hologramUpdateIndex);
                    try {
                        updateHologramVariables(hologram);
                    } catch (Exception e) {
                        getLogger().warning("更新全息影像 " + hologram.getName() + " 时发生错误: " + e.getMessage());
                    }
                    hologramUpdateIndex++;
                }
                if (hologramUpdateIndex >= hologramUpdateQueue.size()) {
                    hologramUpdateIndex = 0;
                }
            }, 20L, 1L);
        }
    }

    /**
     * 更新单个全息影像的变量
     */
    private void updateHologramVariables(Hologram hologram) {
        List<String> originalLines = hologram.getOriginalLines();
        if (originalLines == null || originalLines.isEmpty()) {
            return;
        }

        // 获取当前数据用于变量替换
        final int onlinePlayers = Bukkit.getOnlinePlayers().size();
        final int maxPlayers = Bukkit.getMaxPlayers();
        final int totalOnline = totalOnlinePlayers;

        // 获取一个在线玩家用于变量替换，如果没有则使用控制台
        CommandSender sender = Bukkit.getConsoleSender();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sender = player;
            break;
        }
        final CommandSender finalSender = sender;

        // 处理每一行的变量
        List<String> newLines = new ArrayList<>();
        for (String line : originalLines) {
            String processedLine = replaceVariablesOptimized(line, finalSender, onlinePlayers, maxPlayers, totalOnline);
            newLines.add(processedLine);
        }

        // 检查新行是否与当前行不同，避免不必要的更新
        List<String> currentLines = hologram.getLines();
        if (currentLines != null && currentLines.equals(newLines)) {
            return; // 如果内容相同，跳过更新
        }

        // 更新全息影像
        hologram.updateLines(newLines);
    }

    private void saveHolograms() {
        config.set("holograms", null); // 清除旧数据
        for (Hologram hologram : holograms.values()) {
            String path = "holograms." + hologram.getName();
            config.set(path + ".world", hologram.getLocation().getWorld().getName());
            config.set(path + ".x", hologram.getLocation().getX());
            config.set(path + ".y", hologram.getLocation().getY());
            config.set(path + ".z", hologram.getLocation().getZ());
            config.set(path + ".lines", hologram.getOriginalLines()); // 保存原始文本
        }
        try {
            config.save(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            getLogger().severe("保存全息影像失败: " + e.getMessage());
        }
    }

    private void registerVelocitySupport() {
        if (enableVelocitySupport) {
            try {
                // 注册插件消息通道
                this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                this.getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", this);
                getLogger().info("Velocity支持已启用");
            } catch (Exception e) {
                getLogger().severe("注册Velocity插件消息通道失败: " + e.getMessage());
                enableVelocitySupport = false;
            }
        }
    }

    private void startServerInfoTask() {
        if (enableVelocitySupport) {
            // Folia 兼容：使用 AsyncScheduler
            if (isFolia()) {
                getServer().getAsyncScheduler().runAtFixedRate(this, scheduledTask -> {
                    for (String serverAddress : serverPortals.values()) {
                        requestServerInfo(serverAddress);
                    }
                }, 20L, 1200L, TimeUnit.MILLISECONDS);

                getServer().getAsyncScheduler().runAtFixedRate(this, scheduledTask -> {
                    // 已移除 playerInAreaCache、lastAreaCheckTimeLRU、playerLastChunk 清理代码
                }, 6000L, 6000L, TimeUnit.MILLISECONDS);
            } else {
                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    for (String serverAddress : serverPortals.values()) {
                        requestServerInfo(serverAddress);
                    }
                }, 20L, 1200L); // 1秒后开始，每60秒执行一次

                Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    // 已移除 playerInAreaCache、lastAreaCheckTimeLRU、playerLastChunk 清理代码
                }, 6000L, 6000L); // 5分钟后开始，每5分钟执行一次
            }

            // 立即执行一次初始更新
            runTaskLaterCompat(() -> {
                for (String serverAddress : serverPortals.values()) {
                    requestServerInfo(serverAddress);
                }
            }, 40L); // 2秒后执行
        }
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void requestServerInfo(String serverAddress) {
        // 发送请求获取服务器信息
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(serverAddress.toLowerCase()); // 确保发送小写服务器名称

        // 需要在主线程中发送插件消息
        if (isFolia()) {
            getServer().getGlobalRegionScheduler().execute(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
                    break;
                }
            });
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
                    break;
                }
            });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        event.setCancelled(true); // 直接禁止破坏方块，无需权限判断
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        event.setCancelled(true); // 直接禁止放置方块，无需权限判断
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        // 记录玩家加入时间，防止刚加入就触发传送
        playerJoinTime.put(playerName, System.currentTimeMillis());
        // 初始化传送状态
        playerTeleportingStatus.put(playerName, false);
        // 延迟清理区域检测缓存，避免立即检测
        runTaskLaterCompat(() -> clearPlayerAreaCache(playerName), 20L); // 1秒后清理缓存
        // 传送到出生点
        if (spawnLocation != null) {
            runTaskLaterCompat(() -> {
                if (isFolia()) {
                    player.getServer().getRegionScheduler().execute(this, spawnLocation, () -> player.teleportAsync(spawnLocation));
                } else {
                    player.teleport(spawnLocation);
                }
            }, 2L);
        }
        // 延迟更新总在线人数，避免频繁更新
        if (enableVelocitySupport) {
            if (isFolia()) {
                getServer().getAsyncScheduler().runDelayed(this, scheduledTask -> updateTotalOnlinePlayers(), 10L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(this, (Runnable) this::updateTotalOnlinePlayers, 10L);
            }
        }
        // 发送自定义加入消息（直接设置，不用RegionScheduler）
        if (enableJoinMessage) {
            final String message = replaceVariables(getRandomMessage(joinMessages), player);
            event.joinMessage(MiniMessage.miniMessage().deserialize(message));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        // 清理所有区域检测相关缓存
        playerInAreaCache.remove(playerName);
        playerLastChunk.remove(playerName);
        playerLastCheckTime.remove(playerName);
        playerJoinTime.remove(playerName);
        playerTeleportingStatus.remove(playerName);
        playerLastTeleportTime.remove(playerName);
        // 延迟更新总在线人数，避免频繁更新
        if (enableVelocitySupport) {
            if (isFolia()) {
                getServer().getAsyncScheduler().runDelayed(this, scheduledTask -> updateTotalOnlinePlayers(), 10L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(this, (Runnable) this::updateTotalOnlinePlayers, 10L);
            }
        }
        // 发送自定义退出消息（直接设置，不用RegionScheduler）
        if (enableQuitMessage) {
            final String message = replaceVariables(getRandomMessage(quitMessages), player);
            event.quitMessage(MiniMessage.miniMessage().deserialize(message));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (spawnLocation != null) {
            if (isFolia()) {
                getServer().getRegionScheduler().execute(this, spawnLocation, () -> event.setRespawnLocation(spawnLocation));
            } else {
                event.setRespawnLocation(spawnLocation);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!config.getBoolean("area-teleport.enabled", true)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (isFolia()) {
            player.getServer().getRegionScheduler().execute(this, player.getLocation(), () -> clearPlayerAreaCache(player.getName()));
        } else {
            clearPlayerAreaCache(player.getName());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (command.getName().equalsIgnoreCase("tyz")) {
                // 检查是否为普通玩家，如果是则拒绝执行命令
                if (sender instanceof Player && !sender.isOp() && !sender.hasPermission("thdl.admin")) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限使用此命令！"));
                    return true;
                }

                if (args.length == 0) {
                    showHelp(sender);
                    return true;
                }

                String subCommand = args[0].toLowerCase();

                switch (subCommand) {
                    case "setspawn":
                        if (!(sender instanceof Player)) {
                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>此命令只能由玩家执行！"));
                            return true;
                        }
                        Player player = (Player) sender;
                        if (!player.isOp()) {
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限设置出生点！"));
                            return true;
                        }
                        spawnLocation = player.getLocation();
                        config.set("spawn.world", spawnLocation.getWorld().getName());
                        config.set("spawn.x", spawnLocation.getX());
                        config.set("spawn.y", spawnLocation.getY());
                        config.set("spawn.z", spawnLocation.getZ());
                        config.set("spawn.yaw", spawnLocation.getYaw());
                        config.set("spawn.pitch", spawnLocation.getPitch());
                        try {
                            config.save(new File(getDataFolder(), "config.yml"));
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<green>出生点已设置: " + formatLocation(spawnLocation)));
                        } catch (IOException e) {
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>保存配置失败: " + e.getMessage()));
                        }
                        break;

                    case "hologram":
                    case "holo":
                        if (!(sender instanceof Player)) {
                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>此命令只能由玩家执行！"));
                            return true;
                        }
                        Player holoPlayer = (Player) sender;
                        if (!holoPlayer.hasPermission("thdl.hologram")) {
                            holoPlayer.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限使用全息影像功能！"));
                            return true;
                        }
                        handleHologramCommand(holoPlayer, args);
                        break;

                    case "reload":
                        if (!sender.isOp()) {
                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限重载配置！"));
                            return true;
                        }

                        // 异步配置加载优化：避免主线程IO阻塞
                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>正在异步重载配置，请稍候..."));

                        // Folia 兼容异步任务
                        if (isFolia()) {
                            getServer().getAsyncScheduler().runNow(this, scheduledTask -> {
                                try {
                                    File configFile = new File(getDataFolder(), "config.yml");
                                    FileConfiguration newConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);

                                    // 主线程应用配置
                                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                                        try {
                                            for (Hologram hologram : holograms.values()) {
                                                hologram.remove();
                                            }
                                            holograms.clear();
                                            this.config = newConfig;
                                            loadSpawnLocation();
                                            loadJoinQuitMessages();
                                            loadServerPortals();
                                            loadHolograms();
                                            buildAreaIndex();
                                            hologramUpdateQueue = new ArrayList<>(holograms.values());
                                            hologramUpdateIndex = 0;
                                            updateHologramVariables();
                                            if (enableVelocitySupport) {
                                                runTaskLaterCompat(() -> {
                                                    for (String serverAddress : serverPortals.values()) {
                                                        requestServerInfo(serverAddress);
                                                    }
                                                }, 20L);
                                            }
                                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>配置已异步重载完成！"));
                                        } catch (Exception e) {
                                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>重载配置时发生错误: " + e.getMessage()));
                                            getLogger().severe("重载配置时发生错误: " + e.getMessage());
                                        }
                                    });
                                } catch (Exception e) {
                                    getServer().getGlobalRegionScheduler().execute(this, () -> {
                                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>异步加载配置失败: " + e.getMessage()));
                                        getLogger().severe("异步加载配置失败: " + e.getMessage());
                                    });
                                }
                            });
                        } else {
                            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                                try {
                                    File configFile = new File(getDataFolder(), "config.yml");
                                    FileConfiguration newConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        try {
                                            for (Hologram hologram : holograms.values()) {
                                                hologram.remove();
                                            }
                                            holograms.clear();
                                            this.config = newConfig;
                                            loadSpawnLocation();
                                            loadJoinQuitMessages();
                                            loadServerPortals();
                                            loadHolograms();
                                            buildAreaIndex();
                                            hologramUpdateQueue = new ArrayList<>(holograms.values());
                                            hologramUpdateIndex = 0;
                                            updateHologramVariables();
                                            if (enableVelocitySupport) {
                                                Bukkit.getScheduler().runTaskLater(this, () -> {
                                                    for (String serverAddress : serverPortals.values()) {
                                                        requestServerInfo(serverAddress);
                                                    }
                                                }, 20L);
                                            }
                                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>配置已异步重载完成！"));
                                        } catch (Exception e) {
                                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>重载配置时发生错误: " + e.getMessage()));
                                            getLogger().severe("重载配置时发生错误: " + e.getMessage());
                                        }
                                    });
                                } catch (Exception e) {
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>异步加载配置失败: " + e.getMessage()));
                                        getLogger().severe("异步加载配置失败: " + e.getMessage());
                                    });
                                }
                            });
                        }
                        break;

                    case "help":
                        showHelp(sender);
                        break;

                    case "area":
                        if (!sender.isOp()) {
                            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限管理区域传送功能！"));
                            return true;
                        }
                        handleAreaCommand(sender, args);
                        break;

                    default:
                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>未知命令！使用 /tyz help 查看帮助"));
                        break;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            getLogger().severe("命令执行异常: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>命令执行异常，请联系管理员！"));
            return true;
        }
    }

    private void handleHologramCommand(Player player, String[] args) {
        if (args.length < 2) {
            showHologramHelp(player);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "create":
                if (args.length < 4) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz hologram create <名称> <文本>"));
                    return;
                }
                String name = args[2];
                String text = String.join(" ", args).substring(args[0].length() + args[1].length() + args[2].length() + 3);
                createHologram(player, name, text);
                break;

            case "edit":
                if (args.length < 4) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz hologram edit <名称> <文本>"));
                    return;
                }
                String editName = args[2];
                String editText = String.join(" ", args).substring(args[0].length() + args[1].length() + args[2].length() + 3);
                editHologram(player, editName, editText);
                break;

            case "delete":
                if (args.length < 3) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz hologram delete <名称>"));
                    return;
                }
                String deleteName = args[2];
                deleteHologram(player, deleteName);
                break;

            case "list":
                listHolograms(player);
                break;

            case "move":
                if (args.length < 3) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz hologram move <名称>"));
                    return;
                }
                String moveName = args[2];
                moveHologram(player, moveName);
                break;

            default:
                showHologramHelp(player);
                break;
        }
    }

    private void createHologram(Player player, String name, String text) {
        if (holograms.containsKey(name)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>全息影像 " + name + " 已存在！"));
            return;
        }

        Location location = player.getLocation();
        // 保存原始文本，不进行变量替换
        List<String> originalLines = List.of(text.split("\\|"));
        // 应用变量替换用于显示
        String processedText = replaceVariables(text, player);
        List<String> lines = List.of(processedText.split("\\|"));

        Hologram hologram = new Hologram(this, name, location, lines);
        hologram.setOriginalLines(originalLines); // 设置原始文本
        holograms.put(name, hologram);
        hologram.spawn();

        // 更新全息影像队列
        hologramUpdateQueue = new ArrayList<>(holograms.values());

        saveHolograms();
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>全息影像 " + name + " 创建成功！"));
    }

    private void editHologram(Player player, String name, String text) {
        Hologram hologram = holograms.get(name);
        if (hologram == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>全息影像 " + name + " 不存在！"));
            return;
        }

        hologram.remove();
        // 保存原始文本，不进行变量替换
        List<String> originalLines = List.of(text.split("\\|"));
        // 应用变量替换用于显示
        String processedText = replaceVariables(text, player);
        List<String> lines = List.of(processedText.split("\\|"));

        hologram.setLines(lines);
        hologram.setOriginalLines(originalLines); // 设置原始文本
        hologram.spawn();

        // 更新全息影像队列
        hologramUpdateQueue = new ArrayList<>(holograms.values());

        saveHolograms();
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>全息影像 " + name + " 编辑成功！"));
    }

    private void deleteHologram(Player player, String name) {
        Hologram hologram = holograms.get(name);
        if (hologram == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>全息影像 " + name + " 不存在！"));
            return;
        }

        hologram.remove();
        holograms.remove(name);

        // 更新全息影像队列
        hologramUpdateQueue = new ArrayList<>(holograms.values());

        saveHolograms();
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>全息影像 " + name + " 删除成功！"));
    }

    private void listHolograms(Player player) {
        // 删除调试日志输出
        if (holograms.isEmpty()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>没有全息影像"));
            return;
        }
        // 强制刷新队列，防止引用丢失
        hologramUpdateQueue = new ArrayList<>(holograms.values());
        player.sendMessage(MiniMessage.miniMessage().deserialize("<gold>=== 全息影像列表 ==="));
        for (String name : holograms.keySet()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>- " + name));
        }
    }

    private void moveHologram(Player player, String name) {
        Hologram hologram = holograms.get(name);
        if (hologram == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>全息影像 " + name + " 不存在！"));
            return;
        }

        Location newLocation = player.getLocation();
        hologram.remove();
        hologram.setLocation(newLocation);
        hologram.spawn();

        // 更新全息影像队列
        hologramUpdateQueue = new ArrayList<>(holograms.values());

        saveHolograms();
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>全息影像 " + name + " 移动成功！"));
    }

    private void showHologramHelp(Player player) {
        player.sendMessage(MiniMessage.miniMessage().deserialize("<gold>=== 全息影像帮助 ==="));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram create <名称> <文本> - 创建全息影像"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram edit <名称> <文本> - 编辑全息影像"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram delete <名称> - 删除全息影像"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram move <名称> - 移动全息影像"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram list - 列出所有全息影像"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>注意: 使用 | 分隔多行文本"));
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gold>=== 桃韵斋大厅插件帮助 ==="));

        if (sender.isOp()) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz setspawn - 设置出生点"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz reload - 重载配置"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz area - 区域传送管理"));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz help - 显示此帮助"));
        }

        if (sender.hasPermission("thdl.hologram")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>用法: /tyz hologram - 全息影像管理"));
        }

        // 如果没有权限，显示提示
        if (!sender.isOp() && !sender.hasPermission("thdl.hologram")) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>你没有权限使用任何功能！"));
        }
    }

    private String getRandomMessage(List<String> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        return messages.get(random.nextInt(messages.size()));
    }

    private String replaceVariables(String message, CommandSender sender) {
        String result = message.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max_online}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{total_online}", String.valueOf(totalOnlinePlayers));

        if (sender instanceof Player) {
            Player player = (Player) sender;
            result = result.replace("{player}", player.getName());
        }

        return result;
    }

    private String replaceVariablesOptimized(String message, CommandSender sender, int onlinePlayers, int maxPlayers, int totalOnline) {
        String result = message.replace("{online}", String.valueOf(onlinePlayers))
                .replace("{max_online}", String.valueOf(maxPlayers))
                .replace("{total_online}", String.valueOf(totalOnline));

        if (sender instanceof Player) {
            Player player = (Player) sender;
            result = result.replace("{player}", player.getName());
        }

        return result;
    }

    private String formatLocation(Location location) {
        return String.format("(%s, %.1f, %.1f, %.1f)",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("BungeeCord")) {
            return;
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            String subChannel = in.readUTF();

            if (subChannel.equals("PlayerCount")) {
                String server = in.readUTF();
                int playerCount = in.readInt();

                // 更新服务器玩家数量
                serverPlayerCounts.put(server.toLowerCase(), playerCount);

                // 异步计算总在线人数，避免阻塞主线程
                if (isFolia()) {
                    getServer().getAsyncScheduler().runNow(this, scheduledTask -> updateTotalOnlinePlayers());
                } else {
                    Bukkit.getScheduler().runTaskAsynchronously(this, (Runnable) this::updateTotalOnlinePlayers);
                }
            }
        } catch (IOException e) {
            getLogger().warning("处理插件消息时出错: " + e.getMessage());
        }
    }

    private void updateTotalOnlinePlayers() {
        // 清理重复数据
        cleanupServerPlayerCounts();

        int newTotalOnlinePlayers = 0;
        for (int count : serverPlayerCounts.values()) {
            newTotalOnlinePlayers += count;
        }

        // 添加当前服务器的玩家数量到总数中
        int currentServerPlayers = Bukkit.getOnlinePlayers().size();
        newTotalOnlinePlayers += currentServerPlayers;

        // 更新总在线人数
        totalOnlinePlayers = newTotalOnlinePlayers;

        // 在主线程中更新全息影像中的变量
        if (isFolia()) {
            getServer().getGlobalRegionScheduler().execute(this, this::updateHologramVariables);
        } else {
            Bukkit.getScheduler().runTask(this, (Runnable) this::updateHologramVariables);
        }
    }

    private void updateHologramVariables() {
        // 分帧刷新优化：不再批量更新所有全息影像
        // 现在使用 startHologramBatchUpdateTask() 进行分帧刷新
        // 此方法保留用于兼容性，但实际不再使用
        if (holograms.isEmpty()) {
            return;
        }

        // 获取当前数据用于变量替换
        final int onlinePlayers = Bukkit.getOnlinePlayers().size();
        final int maxPlayers = Bukkit.getMaxPlayers();
        final int totalOnline = totalOnlinePlayers;

        // 获取一个在线玩家用于变量替换，如果没有则使用控制台
        CommandSender sender = Bukkit.getConsoleSender();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sender = player;
            break;
        }
        final CommandSender finalSender = sender;

        // 分帧刷新：每tick只更新部分全息影像
        int updatedCount = 0;
        for (Hologram hologram : holograms.values()) {
            try {
                List<String> originalLines = hologram.getOriginalLines();
                if (originalLines == null || originalLines.isEmpty()) {
                    continue;
                }

                // 处理每一行的变量
                List<String> newLines = new ArrayList<>();
                for (String line : originalLines) {
                    String processedLine = replaceVariablesOptimized(line, finalSender, onlinePlayers, maxPlayers, totalOnline);
                    newLines.add(processedLine);
                }

                // 检查新行是否与当前行不同，避免不必要的更新
                List<String> currentLines = hologram.getLines();
                if (currentLines != null && currentLines.equals(newLines)) {
                    continue; // 如果内容相同，跳过更新
                }

                // 更新全息影像
                hologram.updateLines(newLines);
                updatedCount++;

                // 限制每tick更新的数量，避免主线程压力
                if (updatedCount >= HOLOGRAM_BATCH_SIZE) {
                    break;
                }
            } catch (Exception e) {
                getLogger().warning("更新全息影像 " + hologram.getName() + " 时发生错误: " + e.getMessage());
            }
        }
    }

    private void clearAllHolograms() {
        clearAllHolograms(true);
    }

    private void clearAllHolograms(boolean saveConfig) {
        for (Hologram hologram : holograms.values()) {
            hologram.remove();
        }
        holograms.clear();
        if (saveConfig) {
            saveHolograms(); // 保存清理后的空配置
        }
    }

    private void cleanupAllHologramEntities() {
        int removedCount = 0;

        // 遍历所有世界
        for (World world : Bukkit.getWorlds()) {
            // 查找所有盔甲架实体
            for (ArmorStand armorStand : world.getEntitiesByClass(ArmorStand.class)) {
                // 检查是否是全息影像的盔甲架（不可见、无重力、标记实体、显示自定义名称）
                if (!armorStand.isVisible() &&
                        !armorStand.hasGravity() &&
                        armorStand.isMarker() &&
                        armorStand.isCustomNameVisible() &&
                        armorStand.isInvulnerable() &&
                        !armorStand.isCollidable()) {

                    try {
                        armorStand.remove();
                        removedCount++;
                    } catch (Exception e) {
                        // 忽略异常，继续清理
                    }
                }
            }
        }

        getLogger().info("清理了 " + removedCount + " 个全息影像残影实体");
    }

    private void forceCleanupAllHologramEntities() {
        int removedCount = 0;

        // 遍历所有世界
        for (World world : Bukkit.getWorlds()) {
            // 查找所有盔甲架实体
            for (ArmorStand armorStand : world.getEntitiesByClass(ArmorStand.class)) {
                // 更宽松的检查条件，清理所有可能的全息影像实体
                if (!armorStand.isVisible() &&
                        !armorStand.hasGravity() &&
                        armorStand.isMarker() &&
                        armorStand.isCustomNameVisible()) {

                    try {
                        armorStand.remove();
                        removedCount++;
                    } catch (Exception e) {
                        // 忽略异常，继续清理
                    }
                }
            }
        }

        getLogger().info("强制清理了 " + removedCount + " 个全息影像残影实体");
    }

    /**
     * 使用空间分区索引优化后的区域检测方法
     * 从O(n)优化到O(1)，大幅提升检测效率
     * 增加了更精确的边界检测和状态检查
     */
    private boolean isPlayerInTeleportAreaOptimized(Player player) {
        String playerName = player.getName();
        Location playerLoc = player.getLocation();
        String worldName = playerLoc.getWorld().getName();
        String chunkKey = getChunkKey(playerLoc);

        // 检查玩家是否刚加入（保护期）
        Long joinTime = playerJoinTime.get(playerName);
        if (joinTime != null && System.currentTimeMillis() - joinTime < JOIN_PROTECTION_DURATION) {
            return false;
        }

        // 检查玩家是否正在传送中
        if (playerTeleportingStatus.getOrDefault(playerName, false)) {
            return false;
        }

        // 检查缓存
        long currentTime = System.currentTimeMillis();
        String lastChunk = playerLastChunk.get(playerName);
        Long lastCheckTime = playerLastCheckTime.get(playerName);

        if (lastChunk != null && lastCheckTime != null &&
                currentTime - lastCheckTime < CACHE_DURATION &&
                lastChunk.equals(chunkKey)) {
            // 如果玩家还在同一个区块且缓存未过期，直接返回缓存结果
            return playerInAreaCache.getOrDefault(playerName, false);
        }

        // 更新缓存
        playerLastChunk.put(playerName, chunkKey);
        playerLastCheckTime.put(playerName, currentTime);

        // 检查区域
        Map<String, List<Map<?, ?>>> worldAreas = areaIndex.get(worldName);
        if (worldAreas == null) {
            playerInAreaCache.put(playerName, false);
            return false;
        }

        List<Map<?, ?>> chunkAreas = worldAreas.get(chunkKey);
        if (chunkAreas == null || chunkAreas.isEmpty()) {
            playerInAreaCache.put(playerName, false);
            return false;
        }

        // 精确检测：检查所有区域
        for (Map<?, ?> area : chunkAreas) {
            double x1 = (Double) area.get("x1");
            double y1 = (Double) area.get("y1");
            double z1 = (Double) area.get("z1");
            double x2 = (Double) area.get("x2");
            double y2 = (Double) area.get("y2");
            double z2 = (Double) area.get("z2");

            // 更精确的边界检测，使用玩家脚部位置
            double playerX = playerLoc.getX();
            double playerY = playerLoc.getY(); // 使用脚部Y坐标
            double playerZ = playerLoc.getZ();

            // 确保坐标顺序正确
            double minX = Math.min(x1, x2);
            double maxX = Math.max(x1, x2);
            double minY = Math.min(y1, y2);
            double maxY = Math.max(y1, y2);
            double minZ = Math.min(z1, z2);
            double maxZ = Math.max(z1, z2);

            // 精确的边界检测，包含边界
            if (playerX >= minX && playerX <= maxX &&
                    playerY >= minY && playerY <= maxY &&
                    playerZ >= minZ && playerZ <= maxZ) {
                playerInAreaCache.put(playerName, true);
                return true;
            }
        }

        // 更新缓存
        playerInAreaCache.put(playerName, false);
        return false;
    }

    /**
     * 强制清除玩家的区域检测缓存
     * 用于确保检测的准确性
     */
    private void clearPlayerAreaCache(String playerName) {
        playerInAreaCache.remove(playerName);
        playerLastChunk.remove(playerName);
        playerLastCheckTime.remove(playerName);
        // 注意：不清理playerJoinTime，因为需要保护期
        // 不清理playerTeleportingStatus，因为需要传送状态
    }

    private boolean isPlayerInTeleportArea(Player player) {
        List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");
        Location playerLoc = player.getLocation();

        // 如果没有区域，直接返回false
        if (areas.isEmpty()) {
            return false;
        }

        // 计算玩家是否在区域内
        boolean inArea = false;
        for (Map<?, ?> area : areas) {
            String worldName = (String) area.get("world");
            if (!playerLoc.getWorld().getName().equals(worldName)) {
                continue;
            }
            double x1 = (Double) area.get("x1");
            double y1 = (Double) area.get("y1");
            double z1 = (Double) area.get("z1");
            double x2 = (Double) area.get("x2");
            double y2 = (Double) area.get("y2");
            double z2 = (Double) area.get("z2");
            if (playerLoc.getX() >= Math.min(x1, x2) && playerLoc.getX() <= Math.max(x1, x2) &&
                    playerLoc.getY() >= Math.min(y1, y2) && playerLoc.getY() <= Math.max(y1, y2) &&
                    playerLoc.getZ() >= Math.min(z1, z2) && playerLoc.getZ() <= Math.max(z1, z2)) {
                inArea = true;
                break;
            }
        }
        return inArea;
    }

    private void handleAreaTeleport(Player player) {
        // 检查玩家是否在线
        if (!player.isOnline()) {
            return;
        }

        String playerName = player.getName();
        long currentTime = System.currentTimeMillis();
        long cooldownTime = config.getLong("area-teleport.cooldown-seconds", 30) * 1000L;

        // 检查冷却时间
        if (areaTeleportCooldowns.containsKey(playerName)) {
            long lastTeleportTime = areaTeleportCooldowns.get(playerName);
            if (currentTime - lastTeleportTime < cooldownTime) {
                // 检查消息冷却时间，避免刷屏
                long messageCooldownTime = config.getLong("area-teleport.message-cooldown-seconds", 5) * 1000L;
                long lastMessageTime = areaTeleportMessageCooldowns.getOrDefault(playerName, 0L);
                if (currentTime - lastMessageTime >= messageCooldownTime) {
                    long remainingTime = (cooldownTime - (currentTime - lastTeleportTime)) / 1000L;
                    String cooldownMessage = config.getString("area-teleport.cooldown-message", "&c传送冷却中，请稍后再试！")
                            .replace("{time}", String.valueOf(remainingTime));
                    player.sendMessage(MiniMessage.miniMessage().deserialize(cooldownMessage));
                    areaTeleportMessageCooldowns.put(playerName, currentTime);
                }
                return;
            }
        }

        // 设置传送状态，防止重复触发
        playerTeleportingStatus.put(playerName, true);
        playerLastTeleportTime.put(playerName, currentTime);

        // 获取目标服务器
        String targetServerName = config.getString("area-teleport.server", "zcdq");
        String targetServerAddress = serverPortals.get(targetServerName.toLowerCase());

        // 检查服务器是否存在
        if (targetServerAddress == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>目标服务器不存在！"));
            // 重置传送状态
            playerTeleportingStatus.put(playerName, false);
            return;
        }

        // 更新冷却时间
        areaTeleportCooldowns.put(playerName, currentTime);

        // 发送传送消息
        String message = config.getString("area-teleport.message", "&a检测到玩家进入传送区域，正在传送到子服...");
        player.sendMessage(MiniMessage.miniMessage().deserialize(message));

        // 执行传送
        boolean sent = false;
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(targetServerAddress.toLowerCase());
            player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
            sent = true;
        } catch (Exception e) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>传送失败: " + (e.getMessage() != null ? e.getMessage() : "未知异常")));
            getLogger().log(java.util.logging.Level.SEVERE, "传送失败", e);
            playerTeleportingStatus.put(playerName, false);
        }
        if (sent) {
            // 延迟重置传送状态，确保传送完成
            runTaskLaterCompat(() -> playerTeleportingStatus.put(playerName, false), 20L);
        }
    }

    private void handleAreaCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area <enable|disable|set-server|set-cooldown|set-message|set-message-cooldown|save|list|delete|status>"));
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "enable":
                config.set("area-teleport.enabled", true);
                saveConfig();
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域传送功能已启用！"));
                break;

            case "disable":
                config.set("area-teleport.enabled", false);
                saveConfig();
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域传送功能已禁用！"));
                break;

            case "set-server":
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area set-server <服务器名>"));
                    return;
                }
                String serverName = args[2];
                if (!serverPortals.containsKey(serverName.toLowerCase())) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>服务器 " + serverName + " 不存在！"));
                    return;
                }
                config.set("area-teleport.server", serverName);
                saveConfig();
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域传送目标服务器已设置为: " + serverName));
                break;

            case "set-cooldown":
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area set-cooldown <秒数>"));
                    return;
                }
                try {
                    int cooldown = Integer.parseInt(args[2]);
                    if (cooldown < 0) {
                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>冷却时间不能为负数！"));
                        return;
                    }
                    config.set("area-teleport.cooldown-seconds", cooldown);
                    saveConfig();
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域传送冷却时间已设置为: " + cooldown + " 秒"));
                } catch (NumberFormatException e) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>请输入有效的数字！"));
                }
                break;

            case "set-message":
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area set-message <消息>"));
                    return;
                }
                String message = String.join(" ", args).substring(args[0].length() + args[1].length() + 2);
                config.set("area-teleport.message", message);
                saveConfig();
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域传送消息已设置为: " + message));
                break;

            case "set-message-cooldown":
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area set-message-cooldown <秒数>"));
                    return;
                }
                try {
                    int messageCooldown = Integer.parseInt(args[2]);
                    if (messageCooldown < 0) {
                        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>消息冷却时间不能为负数！"));
                        return;
                    }
                    config.set("area-teleport.message-cooldown-seconds", messageCooldown);
                    saveConfig();
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>消息冷却时间已设置为: " + messageCooldown + " 秒"));
                } catch (NumberFormatException e) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>请输入有效的数字！"));
                }
                break;

            case "save":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>此命令只能由玩家执行！"));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area save <区域名称>"));
                    return;
                }
                saveArea((Player) sender, args[2]);
                break;

            case "list":
                listAreas(sender);
                break;

            case "delete":
                if (args.length < 3) {
                    sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>用法: /tyz area delete <区域名称>"));
                    return;
                }
                deleteArea(sender, args[2]);
                break;

            case "status":
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<gold>=== 区域传送功能状态 ==="));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>启用状态: " + (config.getBoolean("area-teleport.enabled", true) ? "启用" : "禁用")));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>目标服务器: " + config.getString("area-teleport.server", "zcdq")));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>冷却时间: " + config.getLong("area-teleport.cooldown-seconds", 30) + " 秒"));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>消息冷却时间: " + config.getLong("area-teleport.message-cooldown-seconds", 5) + " 秒"));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>传送消息: " + config.getString("area-teleport.message", "&a检测到玩家进入传送区域，正在传送到子服...")));
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>选择工具: " + config.getString("area-teleport.selection-tool", "STICK")));
                break;

            default:
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>未知命令！使用 /tyz area 查看帮助"));
                break;
        }
    }

    private void saveArea(Player player, String areaName) {
        Location point1 = areaSelectionPoints.get(player.getName() + "_1");
        Location point2 = areaSelectionPoints.get(player.getName() + "_2");

        if (point1 == null || point2 == null) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>请先选择两个点！使用木棍左键和右键选择区域"));
            return;
        }

        // 检查区域名称是否已存在
        List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");
        for (Map<?, ?> area : areas) {
            if (areaName.equals(area.get("name"))) {
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>区域名称 " + areaName + " 已存在！"));
                return;
            }
        }

        // 创建新区域
        Map<String, Object> newArea = new HashMap<>();
        newArea.put("name", areaName);
        newArea.put("world", point1.getWorld().getName());
        newArea.put("x1", point1.getX());
        newArea.put("y1", point1.getY());
        newArea.put("z1", point1.getZ());
        newArea.put("x2", point2.getX());
        newArea.put("y2", point2.getY());
        newArea.put("z2", point2.getZ());

        areas.add(newArea);
        config.set("area-teleport.areas", areas);
        saveConfig();

        // 重新构建空间分区索引
        buildAreaIndex();

        // 清理选择点
        areaSelectionPoints.remove(player.getName() + "_1");
        areaSelectionPoints.remove(player.getName() + "_2");

        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域 " + areaName + " 保存成功！"));
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>区域范围: " + formatLocation(point1) + " 到 " + formatLocation(point2)));
    }

    private void listAreas(CommandSender sender) {
        List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");

        if (areas.isEmpty()) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>没有配置任何传送区域"));
            return;
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize("<gold>=== 传送区域列表 ==="));
        for (Map<?, ?> area : areas) {
            String name = (String) area.get("name");
            String world = (String) area.get("world");
            double x1 = (Double) area.get("x1");
            double y1 = (Double) area.get("y1");
            double z1 = (Double) area.get("z1");
            double x2 = (Double) area.get("x2");
            double y2 = (Double) area.get("y2");
            double z2 = (Double) area.get("z2");

            sender.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>" + name + " <gray>- 世界: " + world));
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<gray>  范围: (" + Math.min(x1, x2) + ", " + Math.min(y1, y2) + ", " + Math.min(z1, z2) + ") 到 (" + Math.max(x1, x2) + ", " + Math.max(y1, y2) + ", " + Math.max(z1, z2) + ")"));
        }
    }

    private void deleteArea(CommandSender sender, String areaName) {
        List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");

        for (int i = 0; i < areas.size(); i++) {
            Map<?, ?> area = areas.get(i);
            if (areaName.equals(area.get("name"))) {
                areas.remove(i);
                config.set("area-teleport.areas", areas);
                saveConfig();

                // 重新构建空间分区索引
                buildAreaIndex();

                sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>区域 " + areaName + " 已删除！"));
                return;
            }
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>区域 " + areaName + " 不存在！"));
    }



    // 全息影像内部类
    private static class Hologram {
        private final Plugin plugin;
        private final String name;
        private Location location;
        private List<String> lines;
        private List<String> originalLines; // 保存原始文本，用于变量更新
        private final List<ArmorStand> armorStands;

        public Hologram(Plugin plugin, String name, Location location, List<String> lines) {
            this.plugin = plugin;
            this.name = name;
            this.location = location;
            this.lines = lines;
            this.originalLines = new ArrayList<>(lines); // 保存原始文本
            this.armorStands = new ArrayList<>();
        }

        public void spawn() {
            if (location == null || location.getWorld() == null || lines == null || lines.isEmpty()) {
                return;
            }
            // Folia: 用 RegionScheduler 包裹所有实体操作
            if (org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("Folia") != null) {
                Location loc = location.clone();
                World world = location.getWorld();
                Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
                    forceRemove();
                    Location currentLocation = location.clone();
                    for (String line : lines) {
                        if (line == null || line.trim().isEmpty()) {
                            currentLocation.add(0, -0.25, 0);
                            continue;
                        }
                        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(currentLocation, EntityType.ARMOR_STAND);
                        armorStand.setVisible(false);
                        armorStand.setGravity(false);
                        armorStand.setCanPickupItems(false);
                        armorStand.customName(MiniMessage.miniMessage().deserialize(line));
                        armorStand.setCustomNameVisible(true);
                        armorStand.setMarker(true);
                        armorStand.setInvulnerable(true);
                        armorStand.setCollidable(false);
                        armorStands.add(armorStand);
                        currentLocation.add(0, -0.25, 0);
                    }
                });
            } else {
                forceRemove();
                Location currentLocation = location.clone();
                for (String line : lines) {
                    if (line == null || line.trim().isEmpty()) {
                        currentLocation.add(0, -0.25, 0);
                        continue;
                    }
                    ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(currentLocation, EntityType.ARMOR_STAND);
                    armorStand.setVisible(false);
                    armorStand.setGravity(false);
                    armorStand.setCanPickupItems(false);
                    armorStand.customName(MiniMessage.miniMessage().deserialize(line));
                    armorStand.setCustomNameVisible(true);
                    armorStand.setMarker(true);
                    armorStand.setInvulnerable(true);
                    armorStand.setCollidable(false);
                    armorStands.add(armorStand);
                    currentLocation.add(0, -0.25, 0);
                }
            }
        }

        public void updateLines(List<String> newLines) {
            if (newLines == null || newLines.isEmpty()) {
                remove();
                return;
            }
            if (this.lines != null && this.lines.equals(newLines)) {
                return;
            }
            // Folia: 用 RegionScheduler 包裹所有实体操作
            if (org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("Folia") != null) {
                Location loc = location.clone();
                World world = location.getWorld();
                Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
                    forceRemove();
                    List<ArmorStand> newArmorStands = new ArrayList<>();
                    Location currentLocation = location.clone();
                    for (String line : newLines) {
                        if (line == null || line.trim().isEmpty()) {
                            currentLocation.add(0, -0.25, 0);
                            continue;
                        }
                        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(currentLocation, EntityType.ARMOR_STAND);
                        armorStand.setVisible(false);
                        armorStand.setGravity(false);
                        armorStand.setCanPickupItems(false);
                        armorStand.customName(MiniMessage.miniMessage().deserialize(line));
                        armorStand.setCustomNameVisible(true);
                        armorStand.setMarker(true);
                        armorStand.setInvulnerable(true);
                        armorStand.setCollidable(false);
                        newArmorStands.add(armorStand);
                        currentLocation.add(0, -0.25, 0);
                    }
                    armorStands.clear();
                    armorStands.addAll(newArmorStands);
                    this.lines = new ArrayList<>(newLines);
                });
            } else {
                forceRemove();
                List<ArmorStand> newArmorStands = new ArrayList<>();
                Location currentLocation = location.clone();
                for (String line : newLines) {
                    if (line == null || line.trim().isEmpty()) {
                        currentLocation.add(0, -0.25, 0);
                        continue;
                    }
                    ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(currentLocation, EntityType.ARMOR_STAND);
                    armorStand.setVisible(false);
                    armorStand.setGravity(false);
                    armorStand.setCanPickupItems(false);
                    armorStand.customName(MiniMessage.miniMessage().deserialize(line));
                    armorStand.setCustomNameVisible(true);
                    armorStand.setMarker(true);
                    armorStand.setInvulnerable(true);
                    armorStand.setCollidable(false);
                    newArmorStands.add(armorStand);
                    currentLocation.add(0, -0.25, 0);
                }
                armorStands.clear();
                armorStands.addAll(newArmorStands);
                this.lines = new ArrayList<>(newLines);
            }
        }

        public void remove() {
            if (org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("Folia") != null) {
                for (ArmorStand armorStand : armorStands) {
                    if (armorStand != null && !armorStand.isDead()) {
                        Location loc = armorStand.getLocation();
                        org.bukkit.Bukkit.getServer().getRegionScheduler().run(plugin, armorStand.getLocation(), scheduledTask -> {
                            armorStand.remove();
                        });
                    }
                }
            } else {
                for (ArmorStand armorStand : armorStands) {
                    if (armorStand != null && !armorStand.isDead()) {
                        armorStand.remove();
                    }
                }
            }
            armorStands.clear();
        }

        public void forceRemove() {
            if (org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("Folia") != null) {
                for (ArmorStand armorStand : armorStands) {
                    if (armorStand != null) {
                        Location loc = armorStand.getLocation();
                        org.bukkit.Bukkit.getServer().getRegionScheduler().run(plugin, armorStand.getLocation(), scheduledTask -> {
                            try {
                                armorStand.remove();
                            } catch (Exception e) {
                                // 忽略异常，继续清理
                            }
                        });
                    }
                }
            } else {
                for (ArmorStand armorStand : armorStands) {
                    if (armorStand != null) {
                        try {
                            armorStand.remove();
                        } catch (Exception e) {
                            // 忽略异常，继续清理
                        }
                    }
                }
            }
            armorStands.clear();

            // 额外清理：移除该位置附近的所有盔甲架
            if (location != null && location.getWorld() != null) {
                for (ArmorStand nearby : location.getWorld().getEntitiesByClass(ArmorStand.class)) {
                    if (nearby.getLocation().distance(location) < 5.0) { // 进一步扩大搜索范围
                        if (!nearby.isVisible() &&
                                nearby.isMarker() &&
                                !nearby.hasGravity() &&
                                nearby.isCustomNameVisible()) {
                            if (org.bukkit.Bukkit.getServer().getPluginManager().getPlugin("Folia") != null) {
                                Location loc = nearby.getLocation();
                                org.bukkit.Bukkit.getServer().getRegionScheduler().run(plugin, nearby.getLocation(), scheduledTask -> {
                                    try {
                                        nearby.remove();
                                    } catch (Exception e) {
                                        // 忽略异常
                                    }
                                });
                            } else {
                                try {
                                    nearby.remove();
                                } catch (Exception e) {
                                    // 忽略异常
                                }
                            }
                        }
                    }
                }
            }
        }

        // Getters and Setters
        public String getName() { return name; }
        public Location getLocation() { return location; }
        public void setLocation(Location location) { this.location = location; }
        public List<String> getLines() { return lines; }
        public void setLines(List<String> lines) { this.lines = lines; }
        public List<String> getOriginalLines() { return originalLines; }
        public void setOriginalLines(List<String> originalLines) { this.originalLines = originalLines; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("tyz")) {
            if (args.length == 1) {
                // 第一个参数：主命令
                List<String> mainCommands = new ArrayList<>();
                mainCommands.add("help");

                // 根据权限添加命令
                if (sender.isOp()) {
                    mainCommands.add("setspawn");
                    mainCommands.add("reload");
                    mainCommands.add("area");
                }

                if (sender.hasPermission("thdl.hologram")) {
                    mainCommands.add("hologram");
                    mainCommands.add("holo");
                }

                // 过滤匹配的命令
                String input = args[0].toLowerCase();
                completions.addAll(mainCommands.stream()
                        .filter(cmd -> cmd.toLowerCase().startsWith(input))
                        .collect(Collectors.toList()));

            } else if (args.length == 2) {
                String subCommand = args[0].toLowerCase();

                switch (subCommand) {
                    case "hologram":
                    case "holo":
                        if (sender.hasPermission("thdl.hologram")) {
                            // 全息影像子命令补全
                            List<String> holoCommands = List.of("create", "edit", "delete", "list", "move", "help");
                            String input = args[1].toLowerCase();
                            completions.addAll(holoCommands.stream()
                                    .filter(cmd -> cmd.toLowerCase().startsWith(input))
                                    .collect(Collectors.toList()));
                        }
                        break;

                    case "area":
                        if (sender.isOp()) {
                            // 区域传送子命令补全
                            List<String> areaCommands = List.of("enable", "disable", "set-server", "set-cooldown",
                                    "set-message", "set-message-cooldown", "save", "list", "delete", "status");
                            String input = args[1].toLowerCase();
                            completions.addAll(areaCommands.stream()
                                    .filter(cmd -> cmd.toLowerCase().startsWith(input))
                                    .collect(Collectors.toList()));
                        }
                        break;
                }

            } else if (args.length == 3) {
                String subCommand = args[0].toLowerCase();
                String action = args[1].toLowerCase();

                switch (subCommand) {
                    case "hologram":
                    case "holo":
                        if (sender.hasPermission("thdl.hologram")) {
                            switch (action) {
                                case "edit":
                                case "delete":
                                case "move":
                                    // 全息影像名称补全
                                    String input = args[2].toLowerCase();
                                    completions.addAll(holograms.keySet().stream()
                                            .filter(name -> name.toLowerCase().startsWith(input))
                                            .collect(Collectors.toList()));
                                    break;
                            }
                        }
                        break;

                    case "area":
                        if (sender.isOp()) {
                            switch (action) {
                                case "set-server":
                                    // 服务器名称补全
                                    String input = args[2].toLowerCase();
                                    completions.addAll(serverPortals.keySet().stream()
                                            .filter(server -> server.toLowerCase().startsWith(input))
                                            .collect(Collectors.toList()));
                                    break;

                                case "delete":
                                    // 区域名称补全
                                    List<Map<?, ?>> areas = config.getMapList("area-teleport.areas");
                                    input = args[2].toLowerCase();
                                    completions.addAll(areas.stream()
                                            .map(area -> (String) area.get("name"))
                                            .filter(name -> name.toLowerCase().startsWith(input))
                                            .collect(Collectors.toList()));
                                    break;
                            }
                        }
                        break;
                }
            }
        }

        return completions;
    }





    /**
     * 全局异常保护装饰器
     */
    private void runWithExceptionProtection(Runnable task, String taskName) {
        try {
            task.run();
        } catch (Exception e) {
            getLogger().severe("执行任务 " + taskName + " 时发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 区域定时检测任务
    private void startAreaCheckTask() {
        if (isFolia()) {
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, scheduledTask -> {
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                int total = players.size();
                if (total == 0) return;
                int start = areaCheckIndex;
                int end = Math.min(start + AREA_CHECK_BATCH_SIZE, total);
                for (int i = start; i < end; i++) {
                    Player player = players.get(i);
                    try {
                        String playerName = player.getName();
                        Long joinTime = playerJoinTime.get(playerName);
                        if (joinTime != null && System.currentTimeMillis() - joinTime < JOIN_PROTECTION_DURATION) {
                            continue;
                        }
                        if (playerTeleportingStatus.getOrDefault(playerName, false)) {
                            continue;
                        }
                        long cooldownTime = config.getLong("area-teleport.cooldown-seconds", 30) * 1000L;
                        if (areaTeleportCooldowns.containsKey(playerName)) {
                            long last = areaTeleportCooldowns.get(playerName);
                            if (System.currentTimeMillis() - last < cooldownTime) continue;
                        }
                        if (isPlayerInTeleportAreaOptimized(player)) {
                            if (playerTeleportingStatus.getOrDefault(playerName, false)) {
                                continue;
                            }
                            handleAreaTeleport(player);
                        }
                    } catch (Exception e) {
                        getLogger().warning("检测玩家区域时发生异常: " + (e.getMessage() != null ? e.getMessage() : "未知异常"));
                        getLogger().log(java.util.logging.Level.SEVERE, "区域检测异常", e);
                    }
                }
                areaCheckIndex = (end >= total) ? 0 : end;
            }, 20L, 1L);
        } else {
            getServer().getScheduler().runTaskTimer(this, () -> {
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                int total = players.size();
                if (total == 0) return;
                int start = areaCheckIndex;
                int end = Math.min(start + AREA_CHECK_BATCH_SIZE, total);
                for (int i = start; i < end; i++) {
                    Player player = players.get(i);
                    try {
                        String playerName = player.getName();
                        Long joinTime = playerJoinTime.get(playerName);
                        if (joinTime != null && System.currentTimeMillis() - joinTime < JOIN_PROTECTION_DURATION) {
                            continue;
                        }
                        if (playerTeleportingStatus.getOrDefault(playerName, false)) {
                            continue;
                        }
                        long cooldownTime = config.getLong("area-teleport.cooldown-seconds", 30) * 1000L;
                        if (areaTeleportCooldowns.containsKey(playerName)) {
                            long last = areaTeleportCooldowns.get(playerName);
                            if (System.currentTimeMillis() - last < cooldownTime) continue;
                        }
                        if (isPlayerInTeleportAreaOptimized(player)) {
                            if (playerTeleportingStatus.getOrDefault(playerName, false)) {
                                continue;
                            }
                            handleAreaTeleport(player);
                        }
                    } catch (Exception e) {
                        getLogger().warning("检测玩家区域时发生异常: " + (e.getMessage() != null ? e.getMessage() : "未知异常"));
                        getLogger().log(java.util.logging.Level.SEVERE, "区域检测异常", e);
                    }
                }
                areaCheckIndex = (end >= total) ? 0 : end;
            }, 20L, 1L);
        }
    }

    // runTaskLater 兼容
    private void runTaskLaterCompat(Runnable task, long delayTicks) {
        if (isFolia()) {
            getServer().getGlobalRegionScheduler().runDelayed(this, scheduledTask -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(this, task, delayTicks);
        }
    }

    // 新增：世界加载事件监听，加载全息影像
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        getLogger().info("[WorldLoadEvent] 加载世界: " + event.getWorld().getName());
        if (event.getWorld().getName().equalsIgnoreCase("world")) {
            loadHolograms();
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        String selectionTool = config.getString("area-teleport.selection-tool", "STICK");
        if (!player.hasPermission("thdl.area.select") || event.getItem() == null || !event.getItem().getType().name().equals(selectionTool)) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        if (isFolia()) {
            player.getServer().getRegionScheduler().execute(this, event.getClickedBlock().getLocation(), () -> {
                event.setCancelled(true);
                if (event.getAction().name().contains("LEFT_CLICK")) {
                    Location location = event.getClickedBlock().getLocation();
                    areaSelectionPoints.put(player.getName() + "_1", location);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>已设置第一个点: " + formatLocation(location)));
                } else if (event.getAction().name().contains("RIGHT_CLICK")) {
                    Location location = event.getClickedBlock().getLocation();
                    areaSelectionPoints.put(player.getName() + "_2", location);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>已设置第二个点: " + formatLocation(location)));
                    Location point1 = areaSelectionPoints.get(player.getName() + "_1");
                    if (point1 != null) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<gold>区域选择完成！使用 /tyz area save <名称> 保存区域"));
                    }
                }
            });
        } else {
            event.setCancelled(true);
            if (event.getAction().name().contains("LEFT_CLICK")) {
                Location location = event.getClickedBlock().getLocation();
                areaSelectionPoints.put(player.getName() + "_1", location);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>已设置第一个点: " + formatLocation(location)));
            } else if (event.getAction().name().contains("RIGHT_CLICK")) {
                Location location = event.getClickedBlock().getLocation();
                areaSelectionPoints.put(player.getName() + "_2", location);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>已设置第二个点: " + formatLocation(location)));
                Location point1 = areaSelectionPoints.get(player.getName() + "_1");
                if (point1 != null) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<gold>区域选择完成！使用 /tyz area save <名称> 保存区域"));
                }
            }
        }
    }
} 