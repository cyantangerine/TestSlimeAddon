package me.cyantangerine.slimefunaddon.model;

import com.tcoded.folialib.FoliaLib;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import lombok.Getter;
import me.cyantangerine.slimefunaddon.utils.Utils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public class ResourceLib {
    public static final boolean DEBUG_MODE = true;
    public static class Debug {
        public static void msg(Player player, String msg, ItemStack item) {
            msg(player, String.format("%s: %s[%s] x %d", msg, item.getType().name(), item.getItemMeta() != null && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "", item.getAmount()));
        }
        public static void msg(Player player, String msg) {
            if (ResourceLib.DEBUG_MODE) player.sendMessage(msg);
        }
        public static void msg(Player player, Event event, StringBuilder msg) {
            if (msg == null) msg = new StringBuilder();
            msg(player, String.format("[%s] %s %s", event.getClass().getSimpleName(), event.getEventName(), msg));
        }

        public static void msg(PlayerInteractEvent playerEvent) {
            Player player = playerEvent.getPlayer();
            StringBuilder str = new StringBuilder();
            str.append(playerEvent.getAction().name());
            if (playerEvent.getClickedBlock() != null) {
                str.append(" ");
                str.append(playerEvent.getClickedBlock().getState().getType().name());
            }
            msg(player, playerEvent, str);
        }

        public static void msg(InventoryClickEvent event) {
            HumanEntity entity = event.getWhoClicked();
            if (!(entity instanceof Player player)) return;
            StringBuilder str = new StringBuilder();
            str.append(event.getAction().name());
            str.append(" ");
            str.append(event.getClick().name());
            msg(player, event, str);
        }
    }


    @Getter
    private final Plugin plugin;
    @Getter
    private final FoliaLib foliaLib;
    @Getter
    private final Config config;
    public ResourceLib(Plugin plugin) {
        this.foliaLib = new FoliaLib(plugin);
        this.plugin = plugin;
        // 从 config.yml 中读取插件配置
        this.config = new Config(plugin);
    }

    /**
     * 潜行时主动打开容器 GUI：先在方块所属区域线程取实时容器，再回到玩家实体线程开菜单。
     * 不依赖 PlayerInteractEvent 的 useBlock 结果，因为这个 NMS 版本在「潜行 + 手持物品」
     * 时会直接跳过方块交互；容器 Inventory 本身仍写着真实方块实体，改动会正常写穿。
     */
    public void openBlockContainer(Player player, Block block) {
        Location location = block.getLocation();
        runAtLocation(location, ignored -> {
            Inventory container = readBlockContainerInventory(block);
            if (container == null) {
                return;
            }
            runAtEntity(player, () -> {
                try {
                    player.openInventory(container);
                } catch (RuntimeException | LinkageError ex) {
                    warn("打开容器失败", ex);
                }
            });
        });
    }

    /**
     * Paper/Folia 的 {@code getState()} 默认返回快照，优先用 {@code getState(false)} 拿实时状态。
     * 容器 Inventory 的 {@code getInventory()} 直接包着真实方块实体，改动会立即写穿，
     * 所以这里绝不能再调 {@code BlockState#update()}（快照会把旧内容覆盖回去）。
     */
    public Inventory readBlockContainerInventory(Block block) {
        if (!Utils.isContainerBlock(block)) {
            return null;
        }
        try {
            BlockState state = block.getState();
            return state instanceof InventoryHolder holder ? holder.getInventory() : null;
        } catch (RuntimeException | LinkageError ex) {
            warn("读取容器失败", ex);
            return null;
        }
    }

    public void runAtEntity(Player player, Runnable task) {
        runAtEntity(player, ignored -> task.run());
    }
    public void runAtEntity(Player player, FoliaTask task) {
        if (player.isOnline()) {
            foliaLib.getScheduler().runAtEntity(player, task);
        }
    }

    public void runAtLocation(Location location, FoliaTask task) {
        foliaLib.getScheduler().runAtLocation(location, task);
    }

    public void runAtBlock(Block block, FoliaTask task) {
        runAtLocation(block.getLocation(), task);
    }

    public void warn(String message, Throwable ex) {
        plugin.getLogger().log(Level.WARNING, "[ResourceLib] " + message, ex);
    }
}
