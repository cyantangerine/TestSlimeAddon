package me.cyantangerine.slimefunaddon.model;

import me.cyantangerine.slimefunaddon.item.SmartStorageInterface;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「至少一个智能存储交互终端的成员权限」的全局判定。
 *
 * <p>卸载 / 加载终端没有自己的权限页面，所以它们的准入复用交互终端的 ACL：
 * 只要玩家在世界里<b>任意一个</b>已放置的交互终端上是成员（或管理员），
 * 就可以手持背包对任意卸载 / 加载终端执行动作；只有至少在一个交互终端上是
 * 管理员，才能修改卸载 / 加载终端的配置。</p>
 *
 * <p>实现上不存「角色快照」—— ACL 才是唯一数据源，改完权限立刻生效、成员被
 * 删除也立刻失效。这里只持久化一份<b>已知交互终端坐标表</b>（插件数据目录下的
 * 纯文本文件），避免重启后不知道该去查哪些方块。查询时逐个位置读
 * BlockStorage 里的 ACL，并对「方块已被拆掉但没触发事件」的脏数据做自愈。</p>
 */
public final class StorageAccess {

    private static final String FILE_NAME = "smart-storage-terminals.txt";
    private static final Set<String> LOCATIONS = ConcurrentHashMap.newKeySet();
    private static volatile Plugin plugin;
    private static volatile boolean loaded;

    private StorageAccess() {
    }

    public static synchronized void init(Plugin owner) {
        plugin = owner;
        load();
    }

    /** 登记一个已放置的交互终端（右键打开时、放置时调用）。 */
    public static void register(Location location) {
        String key = key(location);
        if (key == null || !LOCATIONS.add(key)) {
            return;
        }
        save();
    }

    /** 交互终端被拆掉时移除，不再参与全局权限判定。 */
    public static void unregister(Location location) {
        String key = key(location);
        if (key == null || !LOCATIONS.remove(key)) {
            return;
        }
        save();
    }

    /** 0 = 无权限，1 = 普通成员，2 = 管理员。 */
    public static int role(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("CyanTestAddon");
        }
        if (!loaded) {
            synchronized (StorageAccess.class) {
                if (!loaded && plugin != null) {
                    load();
                }
            }
        }

        int best = 0;
        List<String> stale = new ArrayList<>();
        for (String key : LOCATIONS) {
            Location location = parse(key);
            if (location == null) {
                stale.add(key);
                continue;
            }
            int role;
            try {
                role = SmartStorageInterface.roleAt(location, uuid);
            } catch (RuntimeException | LinkageError ignored) {
                // 区块未加载 / 数据暂时读不到：跳过但绝不删坐标，否则成员权限会莫名失效
                continue;
            }
            if (role < 0) {
                // roleAt 返回 -1 也可能是「该区块的 BlockStorage 还没加载」，
                // 这里同样只跳过、不删坐标；真正的拆除由 BlockBreakEvent 清理。
                continue;
            }
            if (role > best) {
                best = role;
                if (best >= 2) {
                    break;
                }
            }
        }
        if (!stale.isEmpty()) {
            LOCATIONS.removeAll(stale);
            save();
        }
        return best;
    }

    public static boolean isMember(UUID uuid) {
        return role(uuid) >= 1;
    }

    public static boolean isAdmin(UUID uuid) {
        return role(uuid) >= 2;
    }

    // ------------------------------------------------------------------
    // 坐标表持久化
    // ------------------------------------------------------------------

    private static String key(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getUID() + "|"
                + location.getBlockX() + "|"
                + location.getBlockY() + "|"
                + location.getBlockZ();
    }

    private static Location parse(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String[] parts = key.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) {
                return null;
            }
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Path file() {
        Plugin owner = plugin;
        return owner == null ? null : owner.getDataFolder().toPath().resolve(FILE_NAME);
    }

    private static synchronized void load() {
        if (loaded || plugin == null) {
            return;
        }
        loaded = true;
        Path path = file();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && trimmed.contains("|")) {
                    LOCATIONS.add(trimmed);
                }
            }
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("[CyanTestAddon] 读取智能存储终端坐标表失败，将在打开终端时重建：" + ex.getMessage());
        }
    }

    private static synchronized void save() {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = new ArrayList<>(LOCATIONS);
            lines.sort(String::compareTo);
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            if (plugin != null) {
                plugin.getLogger().warning("[CyanTestAddon] 保存智能存储终端坐标表失败：" + ex.getMessage());
            }
        }
    }
}
