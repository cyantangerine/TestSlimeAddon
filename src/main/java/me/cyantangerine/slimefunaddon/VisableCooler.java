package me.cyantangerine.slimefunaddon;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.github.thebusybiscuit.slimefun4.api.events.CoolerFeedPlayerEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.backpacks.Cooler;
import io.github.thebusybiscuit.slimefun4.implementation.listeners.BackpackListener;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.ParametersAreNonnullByDefault;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

/**
 * 一个能"看见容量"的小冰柜。
 *
 * <p>Slimefun 的背包内容不在物品里，而在 {@link PlayerBackpack} 的 {@link Inventory} 中
 * （RC-32 存玩家 yml，dev 分支存数据库）。所以容量只能在背包真正打开/改动时统计，
 * 再写回物品让客户端渲染。任何"猜测式"的加减都会和真实存档不一致。</p>
 *
 * <p>本类不注册 Slimefun 自带的 {@code CoolerListener}：它在 Folia 上把
 * {@code addPotionEffect} 丢到全局区域线程，会抛线程检查异常导致果汁没被喝掉，
 * 而 {@code CoolerFeedPlayerEvent} 却在异常之前就发出去了。这里改成自己的
 * Folia 安全实现：只要玩家饱和度低于 {@link #SATURATION_THRESHOLD} 就喝一瓶，
 * 且扫描整份玩家背包（不限快捷栏或手持）。</p>
 *
 * <p>手持右键容器（箱子/桶/潜影盒/漏斗/发射器/投掷器）会把容器里的果汁批量装载进冰柜，
 * 并回报装载了多少瓶、分别是什么果汁；潜行时则按原版行为正常开箱。</p>
 *
 * <p>首次使用要输入背包名称：右键时会先关掉客户端残留的原版物品栏/收纳袋浮窗。
 * 只有在主手/副手时才放行 Slimefun 的聊天输入命名；从物品栏里点开的会关掉浮窗
 * 并提示玩家先放到手上，绝不触发 Slimefun 的「请输入名称」。
 * 未绑定背包的冰柜用满权重的冰占位，盖掉客户端 tooltip 里的原版收纳袋显示；
 * Slimefun 异步完成命名绑定后，再换回正常的容量显示。</p>
 *
 * <p>创造模式的点击是 {@link InventoryCreativeEvent}（ClickType.CREATIVE，左右键被压成同一个类型），
 * 且一次点击会连发两个 SetCreativeModeSlot 包（槽位包 + 光标包）。这里按客户端上报的
 * cursor 反推意图：左键只是搬运整只冰柜，完全放行；右键改了 bundle 内容则取消事件。</p>
 *
 * <p><b>幽灵占位物</b>：Folia 26.3 的 {@code handleSetCreativeModeSlot} 在 DENY 且
 * {@code slotNum < 0}（OUTSIDE 光标包）时是静默 return，一个同步包都不发，
 * 客户端会一直"拿着"它本地预测倒出来的玻璃瓶。所以占位物都带 PDC 标记，
 * 右键时显式广播服务端真实光标，打开/关闭冰柜 GUI 时再强制全量同步并回收泄漏物。</p>
 *
 * <p>三层显示，按能力自动降级：{@link BundleMeta} 图标条 / {@link Damageable} 耐久条 / Lore 文本条。</p>
 */
public class VisableCooler extends Cooler implements Listener {

    /**
     * 容量：GUI 格数、Lore 进度条分母、占位玻璃瓶的最大堆叠数，三者统一用这个值。
     * 1.21 的收纳袋权重 = 数量 / 最大堆叠数，于是 used 个瓶子正好画出 used / CAPACITY。
     */
    public static final int CAPACITY = 36;
    /** 新建背包时传给 Slimefun 的格数，与容量一致 */
    public static final int DEFAULT_SIZE = CAPACITY;

    /** 饱和度低于这个值就自动喝果汁；不依赖饱食度下降事件 */
    private static final float SATURATION_THRESHOLD = 2.0F;
    /** 喝一瓶果汁后恢复到的饱和度，与 Slimefun 官方 Cooler 保持一致 */
    private static final float SATURATION_RESTORE = 6.0F;
    /** 两次自动饮用尝试的最小间隔，避免没果汁时反复查库 */
    private static final long FEED_COOLDOWN_MILLIS = 2000L;
    /** 饱和度轮询周期（tick）。EntityExhaustionEvent 负责即时触发，定时器负责兜底 */
    private static final long CHECK_INTERVAL_TICKS = 10L;
    /** 一次创造模式点击会连发两个包，动作只做一次 */
    private static final long CREATIVE_ACTION_DEDUP_MILLIS = 250L;
    /** 幽灵占位物的兜底回收窗口：客户端可能过几秒才把倒出的物品写回某个格子 */
    private static final long CREATIVE_LEAK_WINDOW_MILLIS = 15_000L;
    /** 当被消耗后，小冰柜内的数量小于指定值时，都发消息，负数表示关闭 **/
    private static final short WARNING_IF_ITEMS_COUNT_BELOW = 3;
    /**
     * 占位物的 PDC 标记。客户端把 BundleMeta 里的玻璃瓶/冰"倒出来"时会保留完整 NBT，
     * 凭这个标记就能精确回收，绝不误伤玩家自己的普通玻璃瓶/冰。
     * 用不带 Plugin 的构造，是为了在注册前的静态物品模板里也能打标。
     */
    private static final NamespacedKey PLACEHOLDER_KEY =
            new NamespacedKey("cyantestaddon", "vc_placeholder");

    /** 1.19.2 客户端把 bundle 满格写死为 64 权重，没有 setMaxStackSize 时用它做映射 */
    private static final int LEGACY_BUNDLE_MAX_WEIGHT = 64;
    /** 首次命名：轮询检测绑定完成的周期与最长次数（10 tick × 60 ≈ 30 秒） */
    private static final long INIT_WATCH_PERIOD_TICKS = 10L;
    private static final int INIT_WATCH_MAX_CHECKS = 60;
    /** Slimefun 绑定背包前留在 lore 里的两行占位 */
    private static final String LORE_ID_PREFIX = ChatColor.GRAY + "ID: ";
    private static final String ID_PLACEHOLDER = "<ID>";
    private static final String LORE_OWNER_PREFIX = ChatColor.GRAY + "所有者: ";
    private static final String BAR_PREFIX = ChatColor.GRAY + "容量 ";
    private static final String JUICE_ONLY = ChatColor.RED + "小冰柜只能存放 Slimefun 果汁！";
    private static final String COOLER_FULL = ChatColor.RED + "小冰柜已经满了！";
    private static final String NO_JUICE_IN_CONTAINER = ChatColor.GRAY + "容器里没有 Slimefun 果汁。";
    private static final String NOT_BOUND = ChatColor.RED + "读取小冰柜失败，请先手持右键打开一次完成初始化。";
    private static final String NOT_HELD = ChatColor.YELLOW
            + "请把小冰柜放到主手或副手，再右键使用来设置名称；设置完成后即可在原版物品栏正常使用。";
    private static final String NOT_ENOUGH_WARN_EMPTY = ChatColor.YELLOW + "[ " + ChatColor.WHITE + " %s " + ChatColor.YELLOW + " ] 里已经没有果汁。";
    private static final String NOT_ENOUGH_WARN = ChatColor.YELLOW + "[ " + ChatColor.WHITE + " %s " + ChatColor.YELLOW + " ] 里只剩下 " + ChatColor.RED + "%d" + ChatColor.YELLOW + " 瓶果汁。";

    /** 1.21 的 {@code ItemMeta#setMaxStackSize(Integer)}；1.19.2 没有，为 null 时退回 64 权重映射 */
    private static final Method SET_MAX_STACK_SIZE = findSetMaxStackSize();

    /** 玩家 UUID -> 右键打开背包时手上那个 Cooler 物品（Folia 下会被多个线程访问） */
    private final ConcurrentMap<UUID, ItemStack> opened = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 真正打开的背包 Inventory（用来区分普通箱子/其它菜单） */
    private final ConcurrentMap<UUID, Inventory> openViews = new ConcurrentHashMap<>();
    /** 防止一次异步背包操作还没回来就又发起一次，导致重复消耗；value 是一次操作的令牌 */
    private final ConcurrentMap<UUID, Object> busy = new ConcurrentHashMap<>();
    /** 每个玩家自己的饱和度轮询任务（Folia 上必须挂在实体所属区域线程） */
    private final ConcurrentMap<UUID, WrappedTask> saturationChecks = new ConcurrentHashMap<>();
    /** 首次命名期间等待绑定完成的任务，完成后把冰占位换成容量显示 */
    private final ConcurrentMap<UUID, WrappedTask> initWatchers = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 下一次允许自动饮用的时间戳 */
    private final ConcurrentMap<UUID, Long> feedCooldown = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 上一次创造模式右键动作的时间，用来合并同一次点击的两个包 */
    private final ConcurrentMap<UUID, Long> creativeActionAt = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 最近一次检测到占位物被倒出的时间（PDC 丢失时靠它兜底识别） */
    private final ConcurrentMap<UUID, Long> creativeSpillAt = new ConcurrentHashMap<>();

    private Plugin plugin;
    private FoliaLib foliaLib;
    private NamespacedKey usedKey;

    /** dev 分支的 {@code PlayerBackpack#getAsync(ItemStack)}；RC-32 没有，为 null 时走 PlayerProfile */
    private Method devGetAsync;
    /** Paper/Folia 的 {@code Block#getState(boolean)}；Spigot 1.19.2 没有，为 null 时退回 getState() + update() */
    private Method liveStateMethod;

    @ParametersAreNonnullByDefault
    public VisableCooler(int size, ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(size, itemGroup, item, recipeType, recipe);
    }

    /**
     * 造一个配套的物品模板。BUNDLE 才有收纳袋那种图标进度条；
     * ID / 所有者两行 lore 是 Slimefun 绑定背包用的，一行都不能删。
     */
    public static SlimefunItemStack createItemStack() {
        SlimefunItemStack stack = new SlimefunItemStack(
                "VISABLE_COOLER",
                Material.BUNDLE,
                "&b可视小冰柜",
                "&7右键打开小冰柜；左键果汁可存入",
                "&7手持右键容器可批量装载果汁",
                "&7潜行右键容器则正常开箱",
                "&7饱和度不足时自动饮用",
                "",
                "&7大小: &e" + CAPACITY,
                ChatColor.GRAY + "ID: <ID>",
                ChatColor.GRAY + "所有者: ",
                "",
                LoreBuilder.RIGHT_CLICK_TO_OPEN);

        // 刚合成出来、还没绑定背包时也要盖住客户端的原版收纳袋 tooltip：
        // 在物品模板里就塞满冰，注册/配方克隆出去的每一份都会带上。
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            bundle.setItems(List.of(createIcePlaceholder()));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Method findSetMaxStackSize() {
        try {
            return ItemMeta.class.getMethod("setMaxStackSize", Integer.class);
        } catch (NoSuchMethodException ignored) {
            return null; // 1.19.2
        }
    }

    @Override
    public void preRegister() {
        super.preRegister(); // SimpleSlimefunItem 在这里装上 getItemHandler()

        plugin = getAddon().getJavaPlugin();
        usedKey = new NamespacedKey(plugin, "visible_cooler_used");
        foliaLib = plugin instanceof CyanTestAddon addon ? addon.getFoliaLib() : new FoliaLib(plugin);

        try {
            devGetAsync = PlayerBackpack.class.getMethod("getAsync", ItemStack.class);
        } catch (NoSuchMethodException ignored) {
            devGetAsync = null; // RC-32
        }

        try {
            liveStateMethod = Block.class.getMethod("getState", boolean.class);
        } catch (NoSuchMethodException ignored) {
            liveStateMethod = null; // Spigot 1.19.2
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 注意：这里绝不能 new CoolerListener(Slimefun.instance(), this)，理由见类注释。
        // 插件重载时可能已有在线玩家，补挂轮询任务
        for (Player player : Bukkit.getOnlinePlayers()) {
            registerSaturationCheck(player);
        }
    }

    @Override
    public ItemUseHandler getItemHandler() {
        ItemUseHandler parent = super.getItemHandler();

        return event -> {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();
            PlayerInteractEvent interact = event.getInteractEvent();

            // 手持小冰柜右键容器：默认改成从容器批量装载果汁
            Block block = interact.getClickedBlock();
            if (interact.getAction() == Action.RIGHT_CLICK_BLOCK
                    && block != null && isContainerBlock(block)) {
                if (player.isSneaking()) {
                    // 潜行 = 正常开箱。Folia 26.3 的 useItemOn 在「潜行且手上有物品」时会跳过
                    // BlockState.useItemOn / useWithoutItem，只把 useItem 放开并不能打开容器，
                    // 所以这里取消原版交互，改为主动打开实时容器 GUI（见 openContainerFor）。
                    event.cancel();
                    interact.setUseItemInHand(Event.Result.DENY);
                    interact.setUseInteractedBlock(Event.Result.DENY);
                    openContainerFor(player, block);
                    return;
                }

                event.cancel(); // 同时把 PlayerRightClickEvent 的 item / block 都设为 DENY
                interact.setUseItemInHand(Event.Result.DENY);
                interact.setUseInteractedBlock(Event.Result.DENY);
                loadFromContainer(player, item, block);
                return;
            }

            boolean uninitialized = !isInitialized(item);
            if (uninitialized && !isHeld(player, item)) {
                // 防御性兜底：未初始化且不在主/副手时不走 Slimefun 的命名流程
                event.setUseItem(Event.Result.DENY);
                closeVanillaOverlay(player);
                player.sendMessage(NOT_HELD);
                return;
            }

            opened.put(player.getUniqueId(), item);
            applyDisplay(item, readUsed(item)); // 打开瞬间的缓存值，开好后会用真实 Inventory 覆盖
            parent.onRightClick(event);

            if (uninitialized) {
                // 首次要输入背包名称，客户端可能还留着原版收纳袋/物品栏浮窗，先关掉再让人看聊天提示。
                // 手持（含快捷栏选中）才会走到这里，交给 Slimefun 的 chat catcher 命名。
                closeVanillaOverlay(player);
                watchInitialization(player, item);
            }
        };
    }

    // ------------------------------------------------------------------
    // 容量显示：只在"看得见真实 Inventory"的时候更新
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBackpackOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        ItemStack item = opened.get(player.getUniqueId());
        if (item == null || !isOurCooler(item)) {
            return;
        }

        Inventory inv = event.getInventory();
        if (!isBackpackInventory(inv)) {
            return;
        }

        openViews.put(player.getUniqueId(), inv);
        // InventoryOpenEvent 可能是在全局区域线程触发的，改物品要在玩家所属线程上做
        runAtEntity(player, () -> applyDisplay(item, countUsedSlots(inv)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBackpackClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Inventory view = openViews.get(uuid);
        Inventory closed = event.getInventory();

        // closeInventory() 可能只是关掉原版物品栏浮窗；别把还没打开的冰柜引用一起清掉
        if (view == null || (view != closed && !view.equals(closed))) {
            return;
        }

        ItemStack item = opened.remove(uuid);
        openViews.remove(uuid);

        if (isOurCooler(item)) {
            applyDisplay(item, countUsedSlots(view));
            // 关掉 GUI 后客户端可能把本地预测的占位物放回物品栏，下一 tick 强制同步一次
            schedulePurge(player);

            // 旧版本创建过的 27 格背包，关掉菜单后异步扩到 CAPACITY，下次打开就是 36 格
            if (view.getSize() != CAPACITY) {
                withBackpack(player, item, this::ensureCapacity);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        registerSaturationCheck(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        opened.remove(uuid);
        openViews.remove(uuid);
        busy.remove(uuid);
        feedCooldown.remove(uuid);
        creativeActionAt.remove(uuid);
        creativeSpillAt.remove(uuid);
        stopInitWatch(uuid);
        WrappedTask task = saturationChecks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------
    // 自动喝果汁：饱和度低于 2 即触发，扫描整份玩家背包（不只是快捷栏）
    // ------------------------------------------------------------------

    /** 每个玩家一个挂在实体区域线程上的轮询任务，保证读饱和度/改实体都是线程安全的。 */
    private void registerSaturationCheck(Player player) {
        WrappedTask task = foliaLib.getScheduler().runAtEntityTimer(
                player,
                () -> {
                    if (player.isOnline()) {
                        maybeFeed(player);
                    }
                },
                CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);

        if (task == null) {
            return;
        }
        WrappedTask previous = saturationChecks.put(player.getUniqueId(), task);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**  exhaustion 事件即时触发；定时器兜底捕捉所有其它饱和度下降途径。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player) {
            maybeFeed(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStarvation(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.STARVATION
                && event.getEntity() instanceof Player player) {
            maybeFeed(player);
        }
    }

    private void maybeFeed(Player player) {
        if (!player.isOnline() || player.getSaturation() >= SATURATION_THRESHOLD) {
            return;
        }
        // GUI 开着时 Inventory 由 Slimefun 的 BackpackListener 负责，异步改另一份实例会互相覆盖
        if (openViews.containsKey(player.getUniqueId())) {
            return;
        }

        long now = System.currentTimeMillis();
        Long until = feedCooldown.get(player.getUniqueId());
        if (until != null && now < until) {
            return;
        }
        feedCooldown.put(player.getUniqueId(), now + FEED_COOLDOWN_MILLIS);

        feed(player, player.getFoodLevel() <= 0);
    }

    private void feed(Player player, boolean restoreFood) {
        if (openViews.containsKey(player.getUniqueId())) {
            return;
        }

        // 背包里可能有多个小冰柜：第一个空的不能就此放弃，要按格子顺序逐个找下去。
        List<ItemStack> coolers = findCoolers(player);
        if (coolers.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Object token = new Object();
        if (busy.putIfAbsent(uuid, token) != null) {
            return;
        }
        scheduleUnlock(uuid, token);
        tryFeedNext(player, coolers, 0, restoreFood, token);
    }

    /**
     * 逐个读取冰柜的背包，直到真的喝到一瓶（或全部都空）。
     * 整条链共用同一把 busy 锁，避免在等待异步读库时被定时器重复触发；
     * 每一步的实体改动都通过 runAtEntity 回到玩家所属的区域线程。
     * 本方法本身可以在任意线程调用，只做异步读和任务调度。
     */
    private void tryFeedNext(Player player, List<ItemStack> coolers, int index,
                             boolean restoreFood, Object token) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || index >= coolers.size()) {
            busy.remove(uuid, token);
            return;
        }

        ItemStack item = coolers.get(index);
        readBackpack(item, backpack -> runAtEntity(player, () -> {
            boolean drank = false;
            try {
                if (player.isOnline() && player.getSaturation() < SATURATION_THRESHOLD) {
                    ensureCapacity(backpack);
                    drank = consume(player, item, backpack, restoreFood);
                }
            } catch (RuntimeException | LinkageError ex) {
                warn("自动饮用失败", ex);
            } finally {
                if (drank || !player.isOnline() || player.getSaturation() >= SATURATION_THRESHOLD) {
                    busy.remove(uuid, token);
                } else {
                    // 这个冰柜没果汁（或还没初始化），继续看下一个
                    tryFeedNext(player, coolers, index + 1, restoreFood, token);
                }
            }
        }), () -> tryFeedNext(player, coolers, index + 1, restoreFood, token));
    }

    /**
     * 一定运行在玩家所属的区域线程上，所以 addPotionEffect / setFoodLevel 都是安全的。
     * 返回是否真的喝掉了一瓶，供 {@link #tryFeedNext} 决定要不要继续找下一个冰柜。
     */
    private boolean consume(Player player, ItemStack coolerItem, PlayerBackpack backpack, boolean restoreFood) {
        if (!player.isOnline() || !isOurCooler(coolerItem)) {
            return false;
        }
        // 多个触发源（exhaustion / 定时器 / 饥饿伤害）可能排队，喝之前再确认一次
        if (player.getSaturation() >= SATURATION_THRESHOLD) {
            return false;
        }

        Inventory inv = backpack.getInventory();
        if (inv == null) {
            return false;
        }

        int slot = -1;
        int count = 0, max = Math.min(CAPACITY, WARNING_IF_ITEMS_COUNT_BELOW);
        boolean warn = false;
        for (int i = 0; i < inv.getSize(); i++) {
            if (isJuice(inv.getItem(i))) {
                slot = i;
                if (max < 0) {
                    break;
                } else {
                    count++;
                    if (count > max) break;
                }
            }
        }

        if (slot < 0) {
            return false;
        }
        if (count <= max) {
            if (coolerItem.getItemMeta() == null) {
                plugin.getLogger().warning("冰柜 " + coolerItem.getType() + " 没有元数据:" + coolerItem);
            } else {
                warn = true;
            }
        }

        ItemStack juice = inv.getItem(slot);
        CoolerFeedPlayerEvent event = new CoolerFeedPlayerEvent(player, this, coolerItem, juice);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled() || !isJuice(inv.getItem(slot))) {
            return false;
        }

        ItemStack consumed = event.getConsumedItem();
        if (consumed != null && consumed.getItemMeta() instanceof PotionMeta potionMeta) {
            for (PotionEffect effect : potionMeta.getCustomEffects()) {
                player.addPotionEffect(effect);
            }
        }

        player.setSaturation(SATURATION_RESTORE);
        if (restoreFood) {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + 1));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1F, 1F);

        inv.setItem(slot, null);

        if (warn) {
            if (count <= 1) {
                player.sendMessage(String.format(NOT_ENOUGH_WARN_EMPTY, coolerItem.getItemMeta().getDisplayName()));
            }else {
                player.sendMessage(String.format(NOT_ENOUGH_WARN, coolerItem.getItemMeta().getDisplayName(), count - 1));
            }

        }

        saveBackpack(backpack);
        applyDisplay(coolerItem, countUsedSlots(inv)); // 用真实格数，不再猜测 -1
        return true;
    }

    // ------------------------------------------------------------------
    // 手持右键容器：批量装载果汁
    // ------------------------------------------------------------------

    /** 容器里一个装着果汁的槽位快照（必须在方块所属区域线程读取）。 */
    private record ContainerSlot(int slot, ItemStack stack) { }

    /** 一次装载结果：总瓶数 + 按果汁名分组的数量。 */
    private record LoadResult(int total, Map<String, Integer> byName) {
        static LoadResult empty() {
            return new LoadResult(0, Collections.emptyMap());
        }
    }

    private void loadFromContainer(Player player, ItemStack coolerItem, Block block) {
        UUID uuid = player.getUniqueId();
        Object token = new Object();
        if (busy.putIfAbsent(uuid, token) != null) {
            return;
        }
        scheduleUnlock(uuid, token);

        // Folia 上容器 Inventory 只能在方块所属区域线程访问
        Location location = block.getLocation();
        foliaLib.getScheduler().runAtLocation(location, ignored -> {
            Inventory container = openContainer(block);
            if (container == null) {
                busy.remove(uuid, token);
                return;
            }

            List<ContainerSlot> found = collectJuice(container);
            if (found.isEmpty()) {
                busy.remove(uuid, token);
                runAtEntity(player, () -> player.sendMessage(NO_JUICE_IN_CONTAINER));
                return;
            }

            // 异步读背包 -> 回到同一个方块区域线程，一次性完成"扣容器 + 写背包"。
            // 读取和写入复用同一个 Inventory 对象，避免两次 getState 拿到不同实例。
            readBackpack(coolerItem, backpack -> foliaLib.getScheduler().runAtLocation(location, task -> {
                try {
                    ensureCapacity(backpack);
                    LoadResult result = transfer(container, backpack, found);

                    if (result.total() == 0) {
                        runAtEntity(player, () -> player.sendMessage(COOLER_FULL));
                        return;
                    }

                    saveBackpack(backpack);
                    String message = loadMessage(result);
                    runAtEntity(player, () -> {
                        applyDisplay(coolerItem, countUsedSlots(backpack.getInventory()));
                        player.sendMessage(message);
                    });
                } finally {
                    busy.remove(uuid, token);
                }
            }), () -> {
                busy.remove(uuid, token);
                runAtEntity(player, () -> player.sendMessage(NOT_BOUND));
            });
        });
    }

    /**
     * Paper/Folia 的 {@code getState()} 默认返回快照，优先用 {@code getState(false)} 拿实时状态。
     * 容器 Inventory 的 {@code getInventory()} 直接包着真实方块实体，改动会立即写穿，
     * 所以这里绝不能再调 {@code BlockState#update()}（快照会把旧内容覆盖回去）。
     */
    private Inventory openContainer(Block block) {
        if (!isContainerBlock(block)) {
            return null;
        }
        try {
            BlockState state = liveStateMethod != null
                    ? (BlockState) liveStateMethod.invoke(block, false)
                    : block.getState();

            return state instanceof InventoryHolder holder ? holder.getInventory() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            warn("读取容器失败", ex);
            return null;
        }
    }

    /**
     * 潜行时主动打开容器 GUI：先在方块所属区域线程取实时容器，再回到玩家实体线程开菜单。
     * 不依赖 PlayerInteractEvent 的 useBlock 结果，因为这个 NMS 版本在「潜行 + 手持物品」
     * 时会直接跳过方块交互；容器 Inventory 本身仍写着真实方块实体，改动会正常写穿。
     */
    private void openContainerFor(Player player, Block block) {
        Location location = block.getLocation();
        foliaLib.getScheduler().runAtLocation(location, ignored -> {
            Inventory container = openContainer(block);
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

    private List<ContainerSlot> collectJuice(Inventory inv) {
        List<ContainerSlot> found = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (isJuice(stack)) {
                found.add(new ContainerSlot(i, stack.clone()));
            }
        }
        return found;
    }

    /** 扣容器 + 写背包，全部在同一个区域线程上完成，避免中途被别的线程改动造成复制/丢失。 */
    private LoadResult transfer(Inventory container, PlayerBackpack backpack, List<ContainerSlot> found) {
        Inventory target = backpack.getInventory();
        if (target == null) {
            return LoadResult.empty();
        }

        int[] freeSlots = new int[target.getSize()];
        int free = 0;
        for (int i = 0; i < target.getSize(); i++) {
            ItemStack stack = target.getItem(i);
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                freeSlots[free++] = i;
            }
        }
        if (free == 0) {
            return LoadResult.empty();
        }

        Map<String, Integer> byName = new LinkedHashMap<>();
        int total = 0;

        for (ContainerSlot snapshot : found) {
            if (total >= free) {
                break;
            }

            ItemStack current = container.getItem(snapshot.slot());
            // 两次区域任务之间容器可能被人动过，只搬仍然对得上的槽位
            if (current == null || !current.isSimilar(snapshot.stack())) {
                continue;
            }

            int take = Math.min(free - total, current.getAmount());
            if (take <= 0) {
                continue;
            }

            int remaining = current.getAmount() - take;
            if (remaining <= 0) {
                container.setItem(snapshot.slot(), null);
            } else {
                ItemStack left = current.clone();
                left.setAmount(remaining);
                container.setItem(snapshot.slot(), left);
            }

            // 一格一瓶，这样图标条/容量显示直接等于瓶数
            for (int n = 0; n < take; n++) {
                ItemStack bottle = snapshot.stack().clone();
                bottle.setAmount(1);
                target.setItem(freeSlots[total + n], bottle);
            }

            byName.merge(juiceName(snapshot.stack()), take, Integer::sum);
            total += take;
        }

        return new LoadResult(total, byName);
    }

    private String loadMessage(LoadResult result) {
        StringBuilder message = new StringBuilder(ChatColor.GREEN + "已从容器装载 ")
                .append(ChatColor.YELLOW).append(result.total())
                .append(ChatColor.GREEN).append(" 瓶果汁");

        if (!result.byName().isEmpty()) {
            message.append(ChatColor.GRAY).append("：");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : result.byName().entrySet()) {
                if (!first) {
                    message.append(ChatColor.GRAY).append("、");
                }
                first = false;
                message.append(ChatColor.WHITE).append(entry.getKey())
                        .append(ChatColor.GRAY).append(" ×")
                        .append(ChatColor.YELLOW).append(entry.getValue());
            }
        }
        return message.toString();
    }

    private String juiceName(ItemStack stack) {
        SlimefunItem sfItem = SlimefunItem.getByItem(stack);
        if (sfItem != null) {
            return sfItem.getItemName();
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return ChatColor.WHITE + stack.getType().name();
    }

    private static boolean isContainerBlock(Block block) {
        try {
            return isContainer(block.getType());
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean isContainer(Material type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DISPENSER, DROPPER -> true;
            default -> Tag.SHULKER_BOXES.isTagged(type);
        };
    }

    // ------------------------------------------------------------------
    // 覆盖原版收纳袋行为：只走"背包存取果汁"，其它物品一律拒绝
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.isCancelled()) {
            return;
        }

        // 客户端从 bundle 里倒出来的占位物一旦被写进服务端槽位，立刻取消并回收。
        // 没有这一步，创造模式把幽灵玻璃瓶"放下"后它就变成了真实物品。
        if (isMarkedPlaceholder(event.getCurrentItem()) || isMarkedPlaceholder(event.getCursor())) {
            event.setCancelled(true);
            event.setCursor(null);
            markSpill(player);
            schedulePurge(player);
            return;
        }

        Inventory top = event.getView().getTopInventory();
        Inventory view = openViews.get(player.getUniqueId());
        boolean inCoolerGui = view != null && (view == top || view.equals(top));

        // 创造模式的点击是 InventoryCreativeEvent（ClickType.CREATIVE），客户端会先把
        // bundle 的取出/放入结果画在本地再发给服务端。必须在"是不是冰柜 GUI"判断之后分流：
        // GUI 里的创造点击只是普通搬格子，绝不能走下面的 putIn / openCooler，否则会重复插入。
        if (event instanceof InventoryCreativeEvent creative) {
            if (inCoolerGui) {
                // CREATIVE 点击的 cursor 就是客户端想写进这一格的物品；
                // 只有点在上半部分 GUI 时才算"放进冰柜"，并且只准放果汁。
                int raw = event.getRawSlot();
                boolean topClicked = raw >= 0 && raw < top.getSize();
                ItemStack incoming = topClicked ? event.getCursor() : null;
                if (incoming != null && !incoming.getType().isAir() && !isJuice(incoming)) {
                    event.setCancelled(true);
                    player.sendMessage(JUICE_ONLY);
                }
                return;
            }
            handleCreativeClick(creative, player);
            return;
        }

        // 1) 背包 GUI 开着：Slimefun 自己只检查 currentItem，会漏掉光标/数字键/副手，
        //    这里补上完整的"只准放果汁"过滤。
        if (inCoolerGui) {
            ItemStack incoming = incomingItem(event, player, top);
            if (incoming != null && !incoming.getType().isAir() && !isJuice(incoming)) {
                event.setCancelled(true);
                player.sendMessage(JUICE_ONLY);
            }
            return;
        }

        // 2) 玩家物品栏里操作 BUNDLE：拦掉原版的插入/取出/倾倒
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean currentCooler = isOurCooler(current);
        boolean cursorCooler = isOurCooler(cursor);

        if (!currentCooler && !cursorCooler) {
            return;
        }

        ClickType click = event.getClick();
        // 1.21 的 BundleItem 除了 PICKUP 之外还有"倾倒/选中"等交互，一律拦掉，
        //    否则画进度条用的占位物会被倒出来。
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP
                || click == ClickType.WINDOW_BORDER_LEFT || click == ClickType.WINDOW_BORDER_RIGHT
                || click == ClickType.MIDDLE || click == ClickType.UNKNOWN) {
            event.setCancelled(true);
            return;
        }

        // Shift / 数字键 / 副手切换不会触发 BundleItem 的 override，属于正常搬运，放行
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }

        ItemStack coolerItem = currentCooler ? current : cursor;
        boolean targetInSlot = cursorCooler && !currentCooler;
        ItemStack target = targetInSlot ? current : cursor;
        boolean hasTarget = target != null && !target.getType().isAir();

        // 空槽左键只是正常拿起/放下收纳袋（原版也不会动 bundle 内容）
        if (click == ClickType.LEFT && !hasTarget) {
            return;
        }

        event.setCancelled(true);

        if (!hasTarget) {
            // 原版/旧行为是"从收纳袋释放一个物品"，这里改成直接打开小冰柜 GUI
            openCooler(player, coolerItem);
        } else if (isOurCooler(target)) {
            return; // 两个冰柜互相点，什么都不做
        } else if (isJuice(target)) {
            putIn(player, coolerItem, target, event, targetInSlot);
        } else {
            player.sendMessage(JUICE_ONLY);
        }
    }

    /**
     * 创造模式：客户端先在本地改好收纳袋内容，再用 {@code SetCreativeModeSlot} 把结果发过来。
     * 一次点击会连发两个包（被点的槽位 + 光标，光标包的 rawSlot 是 -999 / OUTSIDE），
     * 而 {@link InventoryCreativeEvent} 把左右键统一写成 ClickType.CREATIVE，无法直接区分，
     * 只能从客户端上报的 {@code cursor} 反推：
     * <ul>
     *     <li>左键只是拿起/放下整只冰柜：cursor 是冰柜本身或空 → 完全放行，插件不做任何事，
     *         玩家才能把冰柜拖到快捷栏/主手。</li>
     *     <li>右键动了收纳袋内容：cursor 是倒出来的占位物、塞进去的果汁，
     *         或是"内容被改过的冰柜"写回原槽 → 取消事件。</li>
     * </ul>
     * 注意：Folia 26.3 的 {@code handleSetCreativeModeSlot} 只在 DENY 且 {@code slotNum >= 0}
     * 时回发真实槽位并清空光标；OUTSIDE 光标包 ({@code slotNum < 0}) 被 DENY 后是静默 return，
     * 一个包都不发。所以这里必须自己广播服务端真实光标，否则幽灵占位物会一直留在客户端手上。
     */
    private void handleCreativeClick(InventoryCreativeEvent event, Player player) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        boolean currentCooler = isOurCooler(current);
        boolean cursorCooler = isOurCooler(cursor);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean outside = event.getSlotType() == InventoryType.SlotType.OUTSIDE;
        boolean markedSpill = isMarkedPlaceholder(cursor);
        boolean looseSpill = isLoosePlaceholder(cursor);
        // 槽位包用带窗口的识别，避免平时误伤玩家自己的玻璃瓶
        boolean spilled = isOurPlaceholder(player, cursor);
        boolean juice = isJuice(cursor);

        // 只有真正带标记的占位物才刷新回收窗口；宽松匹配若也刷新会自激，窗口永远不过期。
        if (markedSpill) {
            markSpill(player);
        }

        // ---- 1) OUTSIDE / 光标包 ----
        // 左键拿起、或原版丢弃整只冰柜：完全放行，插件不做任何事。
        // 倒出占位物的光标包只取消撤回；动作交给同一次点击的槽位包，
        // 免得玩家自己丢一个普通玻璃瓶也被当成"操作了冰柜"。
        if (outside) {
            // 光标包沿用宽松形状识别（与原行为一致），但若 PDC 丢失、窗口还没打开也不能放行：
            // ALLOW 会真的把物品掉到地上。这里只取消 + 撤回，不触发关栏/提示，误伤极小。
            if (markedSpill || looseSpill) {
                event.setCancelled(true);
                event.setCursor(null);
                // DENY + slotNum < 0 时 Folia 静默 return，一个同步包都不发：
                // 必须自己把服务端真实光标广播给客户端，否则幽灵玻璃瓶会一直留在手上。
                clearCreativeCursor(player);
                schedulePurge(player);
                markSpill(player);
            }
            return;
        }

        // ---- 2) 槽位包：左键搬运整只冰柜（拿起后清空原格 / 放到空格）完全放行 ----
        if ((currentCooler && cursorEmpty) || (!currentCooler && cursorCooler)) {
            return;
        }

        // ---- 3) 槽位包没点到冰柜：普通物品一律放行 ----
        if (!currentCooler && !cursorCooler) {
            // 唯一例外是刚右键倒出来、又被放到别的格子里的占位物：撤回它，但不重复关栏/提示。
            if (spilled) {
                event.setCancelled(true);
                event.setCursor(null);
                clearCreativeCursor(player);
                schedulePurge(player);
            }
            return;
        }

        // ---- 4) 槽位包直接点到冰柜，且 cursor 不是空/整只冰柜 ----
        // 即客户端把"内容被改过的冰柜"写回原槽（右键倒出）或往里塞果汁。
        // 旧实现在这里只 setCancelled 后直接 return，所以第一次右键毫无反应，
        // 要等玩家把倒出来的物品放下才关栏——这里改成当场触发动作。
        event.setCancelled(true);
        event.setCursor(null); // 不让客户端算出来的"倒出来的物品"落到光标上

        // 只用服务端真实物品：cursor 是客户端副本（bundle 内容已被改过），
        // 拿它去和主/副手做 equals 会误判成"没拿在手上"。
        ItemStack coolerItem = currentCooler ? current : findHeldCooler(player, findCooler(player));
        if (!isOurCooler(coolerItem)) {
            return;
        }

        if (juice) {
            putIn(player, coolerItem, cursor, event, false);
            return;
        }
        if (!cursorEmpty && !cursorCooler && !spilled) {
            player.sendMessage(JUICE_ONLY);
            return;
        }
        // 同一次右键的两个包都会走到这里，动作只做一次；两个冰柜互相点也统一按右键处理。
        if (!claimCreativeAction(player)) {
            return;
        }
        // 右键动作一开始就打开回收窗口：客户端可能过几秒才把倒出的占位物写回某个格子。
        markSpill(player);

        // 在主手/副手（含选中的快捷栏）→ 进入 Slimefun 的命名流程；
        // 还躺在背包格子里 → 只关掉原版物品栏浮窗并提示，绝不触发 Slimefun 的「请输入名称」。
        if (!isInitialized(coolerItem) && !isHeld(player, coolerItem)) {
            closeCreativeOverlay(player);
            player.sendMessage(NOT_HELD);
            return;
        }

        openCooler(player, coolerItem);
    }

    /** 是否处在"刚右键倒出过占位物"的回收窗口内。 */
    private boolean withinCreativeLeakWindow(Player player) {
        Long last = creativeSpillAt.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < CREATIVE_LEAK_WINDOW_MILLIS;
    }

    private void markSpill(Player player) {
        creativeSpillAt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * 在事件线程上立刻把服务端真实光标重发给客户端。
     * CraftHumanEntity#setItemOnCursor 会调用 AbstractContainerMenu#broadcastCarriedItem，
     * 这是覆盖 OUTSIDE 包 DENY 后"静默 return"的唯一可靠手段。
     */
    private void clearCreativeCursor(Player player) {
        try {
            ItemStack cursor = player.getItemOnCursor();
            // 只清带标记的占位物，服务端真实光标（即使是玩家自己的玻璃瓶）原样重发
            player.setItemOnCursor(isMarkedPlaceholder(cursor) ? null : cursor);
        } catch (RuntimeException | LinkageError ex) {
            warn("同步创造模式光标失败", ex);
        }
    }

    /** 下一 tick 在玩家所属实体线程做一次完整回收（Folia 要求实体改动必须在区域线程）。 */
    private void schedulePurge(Player player) {
        runAtEntity(player, () -> purgeLeakedPlaceholders(player));
    }

    /**
     * 回收客户端本地预测倒出来的占位物：
     * <ol>
     *     <li>重发服务端真实光标（broadcastCarriedItem），覆盖客户端手里那叠幽灵玻璃瓶；</li>
     *     <li>删掉已经写进服务端槽位的、带 PDC 标记的占位物；</li>
     *     <li>全量回发槽位（sendAllDataToRemote），覆盖客户端自己画出来的物品。</li>
     * </ol>
     * 只在带标记时删物品，所以玩家自己的普通玻璃瓶/冰永远不会被误伤。
     */
    private void purgeLeakedPlaceholders(Player player) {
        if (!player.isOnline()) {
            return;
        }
        try {
            PlayerInventory inv = player.getInventory();

            ItemStack cursor = player.getItemOnCursor();
            // 只清带标记的占位物，服务端真实光标（即使是玩家自己的玻璃瓶）原样重发
            player.setItemOnCursor(isMarkedPlaceholder(cursor) ? null : cursor);

            boolean changed = false;
            ItemStack[] storage = inv.getStorageContents();
            for (int i = 0; i < storage.length; i++) {
                if (isMarkedPlaceholder(storage[i])) {
                    storage[i] = null;
                    changed = true;
                }
            }
            if (changed) {
                inv.setStorageContents(storage);
            }

            changed = false;
            ItemStack[] armor = inv.getArmorContents();
            for (int i = 0; i < armor.length; i++) {
                if (isMarkedPlaceholder(armor[i])) {
                    armor[i] = null;
                    changed = true;
                }
            }
            if (changed) {
                inv.setArmorContents(armor);
            }

            if (isMarkedPlaceholder(inv.getItemInOffHand())) {
                inv.setItemInOffHand(null);
            }

            player.updateInventory();
        } catch (RuntimeException | LinkageError ex) {
            warn("回收创造模式残留占位物失败", ex);
        }
    }

    /** 一次点击两个包，动作只执行第一次；250ms 内的第二个包仅做取消与同步。 */
    private boolean claimCreativeAction(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = creativeActionAt.putIfAbsent(uuid, now);
        if (last == null) {
            return true;
        }
        if (now - last < CREATIVE_ACTION_DEDUP_MILLIS) {
            return false;
        }
        creativeActionAt.put(uuid, now);
        return true;
    }

    /**
     * 关掉客户端残留的原版物品栏/收纳袋浮窗。事件本身在玩家所属线程上处理，先立即关一次；
     * 某些客户端会把浮窗重新画出来，下一 tick 再在实体线程补关一次。
     */
    private void closeCreativeOverlay(Player player) {
        try {
            player.closeInventory();
        } catch (RuntimeException | LinkageError ignored) {
            // 不在实体线程时交给下面这次调度
        }
        runAtEntity(player, () -> closeVanillaOverlay(player));
    }

    /** 占位物的严格识别：PDC 标记。只有它才能被用来删除服务端槽位里的物品。 */
    private static boolean isMarkedPlaceholder(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(PLACEHOLDER_KEY, PersistentDataType.BYTE);
    }

    /** 给 BundleMeta 里的占位物打标记；客户端倒出来时保留完整 NBT，标记也能读回来。 */
    private static ItemStack markPlaceholder(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(PLACEHOLDER_KEY, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 我们从 BundleMeta 里塞进去、用来画条/盖 tooltip 的占位物（玻璃瓶或冰）。
     * PDC 标记最可靠；客户端某些路径可能只回传材质+数量，所以在刚倒出过占位物的
     * 时间窗口内额外按形状宽松识别。宽松识别只用于取消事件/重发光标，绝不删物品。
     */
    private boolean isOurPlaceholder(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return isMarkedPlaceholder(item)
                || (withinCreativeLeakWindow(player) && isLoosePlaceholder(item));
    }

    private static boolean isLoosePlaceholder(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Material type = item.getType();
        if ((type != Material.GLASS_BOTTLE && type != Material.ICE)
                || SlimefunItem.getByItem(item) != null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants())) {
            return false;
        }
        int amount = item.getAmount();
        return type == Material.ICE
                ? amount == LEGACY_BUNDLE_MAX_WEIGHT
                : amount > 0 && amount <= CAPACITY;
    }

    /** 找出这次点击里"会被放进上方背包 Inventory"的那个物品。 */
    private ItemStack incomingItem(InventoryClickEvent event, Player player, Inventory top) {
        Inventory clicked = event.getClickedInventory();
        boolean topClicked = clicked != null && (clicked == top || clicked.equals(top));

        return switch (event.getClick()) {
            case LEFT, RIGHT -> topClicked ? event.getCursor() : null;
            case SHIFT_LEFT, SHIFT_RIGHT -> topClicked ? null : event.getCurrentItem();
            case NUMBER_KEY -> topClicked ? player.getInventory().getItem(event.getHotbarButton()) : null;
            case SWAP_OFFHAND -> topClicked ? player.getInventory().getItemInOffHand() : null;
            default -> null;
        };
    }

    /** 右键果汁 -> 放进背包。原位置先清空，背包满/失败时退还。 */
    private void putIn(Player player, ItemStack coolerItem, ItemStack target,
                       InventoryClickEvent event, boolean targetInSlot) {
        ItemStack toInsert = target.clone();

        if (targetInSlot) {
            Inventory clicked = event.getClickedInventory();
            if (clicked != null) {
                clicked.setItem(event.getSlot(), null);
            }
            event.setCurrentItem(null);
        } else {
            // 直接改服务端状态（取消点击后 CraftBukkit 会用服务端状态重新同步客户端）
            player.setItemOnCursor(null);
        }

        withBackpack(player, coolerItem, backpack -> {
            Inventory inv = backpack.getInventory();
            int slot = inv.firstEmpty();

            if (slot < 0) {
                giveBack(player, toInsert);
                player.sendMessage(COOLER_FULL);
                return;
            }

            inv.setItem(slot, toInsert);
            saveBackpack(backpack);
            applyDisplay(coolerItem, countUsedSlots(inv));
        });
    }

    /**
     * 打开小冰柜。走 Slimefun 自己的 {@code BackpackListener#openBackpack}，
     * 这样首次使用时的命名/建包、PDC 绑定、权限与"背包已被他人打开"检查都和官方一致。
     */
    private void openCooler(Player player, ItemStack coolerItem) {
        UUID uuid = player.getUniqueId();
        opened.put(uuid, coolerItem);
        applyDisplay(coolerItem, readUsed(coolerItem));

        // 等本次点击事件处理完、并回到玩家所属区域线程再开菜单（Folia 上不能在事件里直接切容器）
        runAtEntity(player, () -> {
            boolean uninitialized = !isInitialized(coolerItem);
            boolean creative = player.getGameMode() == GameMode.CREATIVE;

            // 创造模式客户端会本地预测"从 bundle 倒出占位物"。未初始化路径原本就关浮窗，
            // 所以一直正常；已初始化路径直接开 GUI，幽灵玻璃瓶就留在了客户端光标上。
            if (creative || uninitialized) {
                closeVanillaOverlay(player);
            }
            if (creative) {
                purgeLeakedPlaceholders(player);
            }

            if (uninitialized) {
                // Slimefun 的 chat catcher 要求物品必须在主手/副手，否则输入名称必然被拒。
                // 不满足时只发提示，绝不触发它的「请输入名称」机制。
                if (!isHeld(player, coolerItem)) {
                    opened.remove(uuid);
                    player.sendMessage(NOT_HELD);
                    return;
                }
            }

            try {
                BackpackListener listener = Slimefun.getBackpackListener();
                if (listener != null) {
                    listener.openBackpack(player, coolerItem, this);
                    if (uninitialized) {
                        watchInitialization(player, coolerItem);
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                opened.remove(uuid);
                warn("打开小冰柜失败", ex);
            }
        });
    }

    /** 与 Slimefun 的 chat catcher 用同一套判断：物品必须在主手或副手。 */
    private boolean isHeld(Player player, ItemStack item) {
        PlayerInventory inv = player.getInventory();
        return item.equals(inv.getItemInMainHand()) || item.equals(inv.getItemInOffHand());
    }

    /**
     * 首次使用要输入背包名称，客户端会残留原版收纳袋/物品栏浮窗挡住聊天输入框。
     * 调用方必须已经处在玩家实体所属的区域线程上（Folia 要求）。
     */
    private void closeVanillaOverlay(Player player) {
        try {
            player.closeInventory();
        } catch (RuntimeException | LinkageError ex) {
            warn("关闭原版物品栏浮窗失败", ex);
        }
    }

    /**
     * Slimefun 的命名是异步的：聊天输入 → PlayerProfile.get → createBackpack → bindItem。
     * 等待期间绝不能改这个物品，否则 {@code BackpackListener} 里的
     * {@code Objects.equals(itemCopy, item)} 会判定"物品被改动"而拒绝命名。
     * 所以这里只轮询绑定状态，一旦绑定完成就把冰占位换成正常容量显示。
     */
    private void watchInitialization(Player player, ItemStack item) {
        UUID uuid = player.getUniqueId();
        int[] checks = {0};

        WrappedTask task = foliaLib.getScheduler().runAtEntityTimer(player, () -> {
            if (!player.isOnline() || checks[0]++ >= INIT_WATCH_MAX_CHECKS || !isOurCooler(item)) {
                stopInitWatch(uuid);
                return;
            }

            if (isInitialized(item)) {
                applyDisplay(item, readUsed(item));
                stopInitWatch(uuid);
            }
        }, INIT_WATCH_PERIOD_TICKS, INIT_WATCH_PERIOD_TICKS);

        if (task == null) {
            return;
        }
        WrappedTask previous = initWatchers.put(uuid, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void stopInitWatch(UUID uuid) {
        WrappedTask task = initWatchers.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 是否已经绑定过 Slimefun 背包（即"输入名称完成"）。
     * dev / Folia fork 把 UUID 写进 PDC（B_UUID / OWNER_UUID）并把所有者行补上名字；
     * RC-32 只把 lore 里的 {@code §7ID: <ID>} 换成真实 ID。两种都认。
     */
    private boolean isInitialized(ItemStack item) {
        if (!isOurCooler(item) || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            String name = key.getKey();
            if (name.equalsIgnoreCase("b_uuid") || name.equalsIgnoreCase("owner_uuid")) {
                return true;
            }
        }

        List<String> lore = meta.getLore();
        if (lore == null) {
            return false;
        }

        for (String line : lore) {
            if (line.startsWith(LORE_ID_PREFIX) && !line.contains(ID_PLACEHOLDER)) {
                return true;
            }
            if (line.startsWith(LORE_OWNER_PREFIX) && !line.equals(LORE_OWNER_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 未初始化时的占位物：一堆冰。
     * 1.19.2 和 1.21 都按 数量 / 最大堆叠数 计算 bundle 权重，ICE 默认最大堆叠 64，
     * 放 64 个正好把权重拉满，客户端的图标条与 tooltip 就显示"装满了冰"，
     * 而不是原版收纳袋的空内容。绑定完成后由 {@link #applyDisplay} 换回玻璃瓶容量条。
     */
    private static ItemStack createIcePlaceholder() {
        return markPlaceholder(new ItemStack(Material.ICE, LEGACY_BUNDLE_MAX_WEIGHT));
    }

    private void giveBack(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ------------------------------------------------------------------
    // 跨版本访问 PlayerBackpack（编译目标是 RC-32，运行可以是 dev 分支）
    // ------------------------------------------------------------------

    /**
     * 只负责异步读背包，不做线程切换、不管锁。回调可能落在任意线程，
     * 所以调用方必须自己把后续操作调度到正确的区域线程。
     */
    private void readBackpack(ItemStack item, Consumer<PlayerBackpack> callback, Runnable onError) {
        if (devGetAsync != null) {
            try {
                @SuppressWarnings("unchecked")
                CompletableFuture<Object> future = (CompletableFuture<Object>) devGetAsync.invoke(null, item);
                future.thenAccept(result -> {
                    if (result instanceof PlayerBackpack backpack) {
                        callback.accept(backpack);
                    } else {
                        onError.run();
                    }
                }).exceptionally(ex -> {
                    warn("读取背包失败", ex);
                    onError.run();
                    return null;
                });
                return;
            } catch (ReflectiveOperationException | LinkageError ex) {
                warn("读取背包失败", ex);
                onError.run();
                return;
            }
        }

        // RC-32：PlayerProfile.getBackpack(ItemStack, Consumer<PlayerBackpack>)。
        // 用反射调用，这样代码也能直接对着 dev 分支的 jar 编译。
        try {
            Method rcGetBackpack = PlayerProfile.class.getMethod("getBackpack", ItemStack.class, Consumer.class);
            Consumer<PlayerBackpack> rcCallback = callback::accept;
            rcGetBackpack.invoke(null, item, rcCallback);
        } catch (ReflectiveOperationException | LinkageError ex) {
            warn("读取背包失败", ex);
            onError.run();
        }
    }

    /** 异步读背包 -> 回到玩家所属区域线程执行 action。 */
    private void withBackpack(Player player, ItemStack item, Consumer<PlayerBackpack> action) {
        UUID uuid = player.getUniqueId();
        Object token = new Object();

        if (busy.putIfAbsent(uuid, token) != null) {
            return;
        }
        scheduleUnlock(uuid, token);

        readBackpack(item, backpack -> runAtEntity(player, () -> {
            try {
                ensureCapacity(backpack);
                action.accept(backpack);
            } finally {
                busy.remove(uuid, token);
            }
        }), () -> busy.remove(uuid, token));
    }

    /** 兜底：DB 没回调时别把玩家永久锁住。用 remove(key, token)，避免旧任务误清新操作的锁。 */
    private void scheduleUnlock(UUID uuid, Object token) {
        foliaLib.getScheduler().runLater(() -> busy.remove(uuid, token), 100L);
    }

    private void runAtEntity(Player player, Runnable task) {
        if (player.isOnline()) {
            foliaLib.getScheduler().runAtEntity(player, ignored -> task.run());
        }
    }

    /**
     * 把旧版创建的 27 格背包扩到 {@link #CAPACITY}。
     * Slimefun 的 setSize 会新建 Inventory 再 setContents(旧内容)，Bukkit 允许数组比容器短，所以扩容安全。
     */
    private void ensureCapacity(PlayerBackpack backpack) {
        if (backpack.getSize() == CAPACITY) {
            return;
        }

        Inventory inv = backpack.getInventory();
        if (inv != null && !inv.getViewers().isEmpty()) {
            return; // 有玩家正看着，setSize 会关掉菜单，留到下次
        }

        try {
            backpack.setSize(CAPACITY);
        } catch (RuntimeException | LinkageError ex) {
            warn("调整背包容量失败", ex);
        }
    }

    /** dev 分支走数据库的 saveBackpackInventory，RC-32 走 markDirty + save。 */
    private void saveBackpack(PlayerBackpack backpack) {
        if (devGetAsync != null) {
            try {
                Class<?> slimefun = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
                Object database = slimefun.getMethod("getDatabaseManager").invoke(null);
                Object controller = database.getClass().getMethod("getProfileDataController").invoke(database);
                controller.getClass()
                        .getMethod("saveBackpackInventory", PlayerBackpack.class)
                        .invoke(controller, backpack);
                return;
            } catch (ReflectiveOperationException | LinkageError ex) {
                warn("保存背包失败", ex);
                return;
            }
        }

        try {
            // 同样用反射，保证代码对着 dev 分支的 jar 也能编译
            PlayerBackpack.class.getMethod("markDirty").invoke(backpack);
            PlayerBackpack.class.getMethod("save").invoke(backpack);
        } catch (ReflectiveOperationException | LinkageError ex) {
            warn("保存背包失败", ex);
        }
    }

    private void warn(String message, Throwable ex) {
        plugin.getLogger().log(Level.WARNING, "[VisableCooler] " + message, ex);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 用 Cooler 自己的规则判断：只有 Slimefun 的 Juice 能放进去。 */
    private boolean isJuice(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && isItemAllowed(item, SlimefunItem.getByItem(item));
    }

    private boolean isOurCooler(ItemStack item) {
        return item != null && SlimefunItem.getByItem(item) == this;
    }

    /**
     * 按格子顺序找出整份玩家背包里的小冰柜：36 个主格（含 9 格快捷栏 + 27 格背包）、
     * 装备栏、副手。不依赖手持物品或当前选中的快捷栏格子。
     * 返回列表而不是单个，自动饮用时才能在这个冰柜没果汁的情况下继续看下一个。
     */
    private List<ItemStack> findCoolers(Player player) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> found = new ArrayList<>();

        for (ItemStack item : inv.getStorageContents()) {
            if (isOurCooler(item)) {
                found.add(item);
            }
        }
        for (ItemStack item : inv.getArmorContents()) {
            if (isOurCooler(item)) {
                found.add(item);
            }
        }

        ItemStack offHand = inv.getItemInOffHand();
        if (isOurCooler(offHand)) {
            found.add(offHand);
        }
        return found;
    }

    /** 只要一个冰柜时用（创造模式点击的兜底查找）。 */
    private ItemStack findCooler(Player player) {
        List<ItemStack> found = findCoolers(player);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 优先取真正拿在手上的冰柜；Slimefun 的命名校验只认主手/副手。 */
    private ItemStack findHeldCooler(Player player, ItemStack fallback) {
        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (isOurCooler(main)) {
            return main;
        }
        ItemStack off = inv.getItemInOffHand();
        if (isOurCooler(off)) {
            return off;
        }
        return fallback;
    }

    private boolean isBackpackInventory(Inventory inv) {
        // 旧版本创建的背包可能还是 27 格，靠 holder 精确识别，尺寸只做合法性兜底
        if (inv == null || inv.getType() != InventoryType.CHEST
                || inv.getSize() < 9 || inv.getSize() > 54 || inv.getSize() % 9 != 0
                || inv.getLocation() != null) {
            return false;
        }

        try {
            Object holder = inv.getHolder();
            String backpackClass = PlayerBackpack.class.getName();

            if (devGetAsync != null) {
                // dev 分支：holder 就是 PlayerBackpack，精确匹配，别把别的插件的虚拟箱子认进来
                return holder != null && holder.getClass().getName().equals(backpackClass);
            }
            // RC-32 的背包是 createInventory(null, ...) 建的，没有 holder
            return holder == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int countUsedSlots(Inventory inv) {
        int used = 0;

        for (ItemStack stack : inv.getContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                used++;
            }
        }

        return used;
    }

    private int readUsed(ItemStack item) {
        if (usedKey == null || !item.hasItemMeta()) {
            return 0;
        }

        Integer used = item.getItemMeta()
                .getPersistentDataContainer()
                .get(usedKey, PersistentDataType.INTEGER);
        return used == null ? 0 : clamp(used);
    }

    /** 把容量写进物品：Lore 文本条 + 收纳袋/耐久条 + PDC 缓存 */
    private void applyDisplay(ItemStack item, int used) {
        if (!isOurCooler(item)) {
            return;
        }

        used = clamp(used);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        // 1) Lore 文本条（保留 Slimefun 的 ID / 所有者行，只替换自己那一行）
        List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : Collections.emptyList());
        lore.removeIf(line -> line.startsWith(BAR_PREFIX));
        lore.add(BAR_PREFIX
                + ChestMenuUtils.getProgressBar(CAPACITY - used, CAPACITY)
                + ChatColor.GRAY + " " + used + "/" + CAPACITY);
        meta.setLore(lore);

        // 2) 收纳袋式图标条：BUNDLE 专属，客户端依据 BUNDLE_CONTENTS 的权重画条。
        //    还没绑定背包时先塞满冰，盖掉客户端 tooltip 里的原版收纳袋显示；
        //    命名完成（isInitialized）后自动换回正常的容量条。
        if (meta instanceof BundleMeta bundle) {
            if (!isInitialized(item)) {
                bundle.setItems(List.of(createIcePlaceholder()));
            } else if (used == 0) {
                bundle.setItems(Collections.emptyList());
            } else {
                bundle.setItems(List.of(createFiller(used)));
            }
        } else if (meta instanceof Damageable damageable && item.getType().getMaxDurability() > 0) {
            // 3) 没有用 BUNDLE 时的降级方案：耐久条
            int max = item.getType().getMaxDurability();
            int damage = used == 0 ? 0 : Math.min(max - 1, Math.round((float) max * used / CAPACITY));
            damageable.setDamage(damage);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        }

        if (usedKey != null) {
            meta.getPersistentDataContainer().set(usedKey, PersistentDataType.INTEGER, used);
        }
        item.setItemMeta(meta);
    }

    /**
     * 画条用的占位物：空玻璃瓶。
     * 1.21 的 bundle 权重 = 数量 / 最大堆叠数，所以放 used 个并把最大堆叠设为 CAPACITY，
     * 条就精确等于 used / CAPACITY；1.19.2 没有 setMaxStackSize，只能把 used 线性映射到 64 权重。
     */
    private ItemStack createFiller(int used) {
        int legacyAmount = Math.max(1, Math.min(LEGACY_BUNDLE_MAX_WEIGHT,
                (int) Math.ceil(LEGACY_BUNDLE_MAX_WEIGHT * (double) used / CAPACITY)));

        if (SET_MAX_STACK_SIZE == null) {
            return markPlaceholder(new ItemStack(Material.GLASS_BOTTLE, legacyAmount));
        }

        ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE, used);
        try {
            ItemMeta bottleMeta = bottle.getItemMeta();
            if (bottleMeta != null) {
                SET_MAX_STACK_SIZE.invoke(bottleMeta, CAPACITY);
                // maxStackSize 和 PDC 标记一次写完，避免两次 setItemMeta 互相覆盖
                bottleMeta.getPersistentDataContainer()
                        .set(PLACEHOLDER_KEY, PersistentDataType.BYTE, (byte) 1);
                bottle.setItemMeta(bottleMeta);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            return markPlaceholder(new ItemStack(Material.GLASS_BOTTLE, legacyAmount));
        }
        return bottle;
    }

    private int clamp(int used) {
        return Math.max(0, Math.min(CAPACITY, used));
    }
}
