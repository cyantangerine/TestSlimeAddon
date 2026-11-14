package me.cyantangerine.slimefunaddon.item;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.utils.compatibility.VersionedParticle;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import me.cyantangerine.slimefunaddon.model.StorageNetwork;
import me.cyantangerine.slimefunaddon.model.StorageNetwork.BlockKey;
import me.cyantangerine.slimefunaddon.utils.Utils;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能存储连接器。
 *
 * <p>放置后会连接上下左右前后 1 格的箱子 / 陷阱箱 / 木桶；遇到另一个连接器就
 * 泛洪，组成一条连接器链。箱子是叶子节点，不会把两条链合并成一个网络。
 * 单个网络最多 32 个容器（大箱子算 1 个），距离 = 经过的连接器个数（最短路），
 * 具体发现逻辑见 {@link StorageNetwork#discover}。</p>
 *
 * <p><b>可视化</b>：连接成功后，从网络入口到「最后一个有链接箱子的连接器」为
 * 止，每个连接器按 Slimefun 货运节点的方式画黄色 DUST 粒子
 * （{@link VersionedParticle#DUST} + {@link Particle.DustOptions}）。
 * 右键任意一个连接器会把<b>整条连通链</b>的可视化一起开 / 关，状态写进
 * Slimefun 的 BlockStorage，重启后仍然记得。</p>
 *
 * <p><b>Folia</b>：粒子由全局定时器只负责「派单」，真正读方块 / 发射粒子都通过
 * {@code runAtLocation} 落到该连接器自己的区域线程执行。</p>
 */
public class SmartStorageConnector extends SlimefunItem implements Listener {

    public static final String ID = "SMART_STORAGE_CONNECTOR";
    private static final String VISUAL_KEY = "smart_storage_visual";

    /** 黄色粒子参数，与 Slimefun 的 NetworkVisualizer 同款（只是把颜色换成黄）。 */
    private static final Particle.DustOptions DUST_YELLOW = new Particle.DustOptions(Color.YELLOW, 1.0F);
    private static final long PARTICLE_PERIOD_TICKS = 10L;

    private Plugin plugin;
    private ResourceLib resourceLib;
    /** 交互接口物品，用来挑选可视化链的入口（离接口最近的那个连接器）。 */
    private SlimefunItem interfaceItem;

    /** 已登记的连接器（放置 / 点击时把整条链登记进来）。 */
    private final Set<BlockKey> placed = ConcurrentHashMap.newKeySet();
    /** 被关掉可视化的连接器，与 BlockStorage 中的 "0" 对应。 */
    private final Set<BlockKey> disabled = ConcurrentHashMap.newKeySet();
    /** 当前需要画粒子的位置；整体替换引用，避免遍历时被并发修改。 */
    private volatile Set<BlockKey> visible = Set.of();
    /** 粒子定时器计数，用来每隔几轮重算一次网络（捕捉后来放上的箱子）。 */
    private volatile int ticks;

    public SmartStorageConnector(ItemGroup itemGroup, SlimefunItemStack item,
                                 RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    public void setInterfaceItem(SlimefunItem interfaceItem) {
        this.interfaceItem = interfaceItem;
    }

    @Override
    public void preRegister() {
        super.preRegister();
        plugin = getAddon().getJavaPlugin();
        resourceLib = Utils.initialCheck(plugin);

        BlockUseHandler useHandler = this::onRightClick;
        BlockPlaceHandler placeHandler = new BlockPlaceHandler(false) {
            @Override
            public void onPlayerPlace(org.bukkit.event.block.BlockPlaceEvent event) {
                onPlaced(event.getBlock());
            }
        };
        addItemHandler(useHandler, placeHandler);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // 全局定时器不碰世界，只把粒子任务派到各个连接器自己的区域线程
        resourceLib.getFoliaLib().getScheduler().runTimer(this::particleTick,
                PARTICLE_PERIOD_TICKS, PARTICLE_PERIOD_TICKS);
    }

    public static SlimefunItemStack createItemStack() {
        // 一次制作 8 个：增强工作台会克隆输出物品，保留其数量，所以这里把数量设为 8。
        SlimefunItemStack stack = new SlimefunItemStack(ID, Material.END_ROD,
                "&a智能存储连接器",
                "&7放置后连接上下左右前后 1 格的箱子 / 木桶",
                "&7遇到其它连接器会泛洪成一条链",
                "&7单条链最多 32 个容器（大箱子算 1 个）",
                "&7箱子不会把两条独立的链合并",
                "&e右键 &7切换整条链的黄色粒子可视化",
                "&7潜行手持方块右键仍可正常放置方块",
                "&7一次合成 &e8 &7个");
        stack.setAmount(8);
        return stack;
    }

    // ------------------------------------------------------------------
    // 放置 / 破坏
    // ------------------------------------------------------------------

    private void onPlaced(Block block) {
        BlockKey key = BlockKey.of(block);
        placed.add(key);
        disabled.remove(key);
        // 下一 tick 再写 BlockStorage：放置事件里 Slimefun 可能还没把方块 ID 存进去，
        // 过早 addBlockInfo 会造出一条没有 ID 的数据。此时方块数据已就绪，也能正确泛洪。
        resourceLib.runAtLocation(block.getLocation(), task -> {
            setDisabled(key, false); // 新放置的一律默认开启，覆盖同坐标残留的旧状态
            rebuildVisible();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BlockKey key = BlockKey.of(event.getBlock());
        if (placed.remove(key)) {
            disabled.remove(key);
            rebuildVisible();
        }
    }

    // ------------------------------------------------------------------
    // 右键：切换整条链的可视化
    // ------------------------------------------------------------------

    private void onRightClick(PlayerRightClickEvent event) {
        Block block = event.getClickedBlock().orElse(null);
        if (block == null || BlockStorage.check(block) != this) {
            return;
        }
        // 潜行 + 手持物品时不接管右键，让原版把手里的方块正常放上去
        if (SmartStorageInterface.isSneakPlacing(event)) {
            return;
        }

        // 只响应右键，别让原版的方块交互 / 手持物品使用插进来
        event.cancel();
        PlayerInteractEvent interact = event.getInteractEvent();
        interact.setUseItemInHand(Event.Result.DENY);
        interact.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        BlockKey root = BlockKey.of(block);
        Location location = block.getLocation();

        // 泛洪 / 读相邻箱子都要在方块所属区域线程
        resourceLib.runAtBlock(block, ignored -> {
            Map<BlockKey, Integer> component = StorageNetwork.connectorComponent(location, this);
            placed.addAll(component.keySet());

            boolean enable = disabled.contains(root) || isDisabledPersisted(root);
            int size = component.size();
            for (BlockKey key : component.keySet()) {
                setDisabled(key, !enable);
            }
            rebuildVisible();

            String message = (enable ? ChatColor.GREEN + "已开启" : ChatColor.GRAY + "已关闭")
                    + ChatColor.YELLOW + " 智能存储网络可视化"
                    + ChatColor.GRAY + "（" + size + " 个连接器）";
            resourceLib.runAtEntity(player, () -> player.sendMessage(message));
        });
    }

    // ------------------------------------------------------------------
    // 可视化开关状态
    // ------------------------------------------------------------------

    private boolean isDisabledPersisted(BlockKey key) {
        Location location = key.location();
        if (location == null) {
            return false;
        }
        try {
            return "0".equals(BlockStorage.getLocationInfo(location, VISUAL_KEY));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void setDisabled(BlockKey key, boolean value) {
        if (value) {
            disabled.add(key);
        } else {
            disabled.remove(key);
        }
        Location location = key.location();
        if (location == null) {
            return;
        }
        try {
            BlockStorage.addBlockInfo(location, VISUAL_KEY, value ? "0" : "1");
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("写入连接器可视化状态失败", ex);
        }
    }

    private boolean isEnabled(BlockKey key) {
        return !disabled.contains(key) && !isDisabledPersisted(key);
    }

    // ------------------------------------------------------------------
    // 计算「该画粒子」的连接器集合
    // ------------------------------------------------------------------

    /**
     * 把已登记的连接器按连通分量分组。每个分量以「挨着交互接口的那个连接器」
     * （找不到就取坐标最小的一个）为根做 BFS，求出每个连接器的连接器跳数；
     * 取所有「有链接箱子」的连接器里最远的距离 maxD，只保留距离 &lt;= maxD 的
     * 连接器 —— 即「至最后一个有链接箱子的连接器止」，后面没有箱子的尾巴不画。
     *
     * <p>会读相邻方块判断是否有箱子，因此调用点必须在区域线程上。</p>
     */
    private void rebuildVisible() {
        List<Set<BlockKey>> components = StorageNetwork.connectorComponents(placed, this);
        Set<BlockKey> result = new HashSet<>();
        for (Set<BlockKey> component : components) {
            BlockKey root = chooseRoot(component);
            Location rootLocation = root == null ? null : root.location();
            if (rootLocation == null) {
                continue;
            }
            Map<BlockKey, Integer> distances = StorageNetwork.connectorComponent(rootLocation, this);
            int maxChestDistance = -1;
            for (Map.Entry<BlockKey, Integer> entry : distances.entrySet()) {
                if (StorageNetwork.hasLinkedContainer(entry.getKey())) {
                    maxChestDistance = Math.max(maxChestDistance, entry.getValue());
                }
            }
            if (maxChestDistance < 0) {
                continue; // 整条链没连到任何箱子，不画
            }
            for (Map.Entry<BlockKey, Integer> entry : distances.entrySet()) {
                if (entry.getValue() <= maxChestDistance && isEnabled(entry.getKey())) {
                    result.add(entry.getKey());
                }
            }
        }
        visible = result;
    }

    /** 优先选挨着交互接口的连接器当根，否则取坐标最小的一个保证结果稳定。 */
    private BlockKey chooseRoot(Set<BlockKey> component) {
        if (interfaceItem != null) {
            for (BlockKey key : component) {
                for (BlockKey neighbour : key.neighbours()) {
                    if (StorageNetwork.isInterface(neighbour, interfaceItem)) {
                        return key;
                    }
                }
            }
        }
        List<BlockKey> sorted = new ArrayList<>(component);
        sorted.sort(Comparator.comparing(BlockKey::x).thenComparing(BlockKey::y).thenComparing(BlockKey::z));
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    // ------------------------------------------------------------------
    // 粒子：全局定时器派单，逐个位置落到自己的区域线程
    // ------------------------------------------------------------------

    private void particleTick() {
        // 玩家后来才摆上箱子 / 拆掉箱子时也要重新算「最后一个有链接箱子的连接器」。
        // 重算要读世界方块，所以派到某个连接器自己的区域线程去做。
        if (++ticks % 4 == 0) {
            BlockKey any = placed.stream().findFirst().orElse(null);
            Location hint = any == null ? null : any.location();
            if (hint != null) {
                resourceLib.runAtLocation(hint, task -> rebuildVisible());
            }
        }

        Set<BlockKey> snapshot = visible;
        if (snapshot.isEmpty()) {
            return;
        }
        for (BlockKey key : snapshot) {
            Location location = key.location();
            if (location == null) {
                continue;
            }
            resourceLib.runAtLocation(location, task -> spawnAt(key, location));
        }
    }

    private void spawnAt(BlockKey key, Location location) {
        // 方块可能已被拆掉，顺手把登记清掉
        if (BlockStorage.check(location) != this) {
            placed.remove(key);
            disabled.remove(key);
            visible = without(visible, key);
            return;
        }
        if (!visible.contains(key) || !isEnabled(key)) {
            return;
        }
        try {
            location.getWorld().spawnParticle(
                    VersionedParticle.DUST,
                    location.getX() + 0.5D, location.getY() + 0.5D, location.getZ() + 0.5D,
                    1, 0.0D, 0.0D, 0.0D, 1.0D, DUST_YELLOW);
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("绘制存储连接器粒子失败", ex);
        }
    }

    private static Set<BlockKey> without(Set<BlockKey> source, BlockKey removed) {
        Set<BlockKey> copy = new LinkedHashSet<>(source);
        copy.remove(removed);
        return copy;
    }
}
