package me.cyantangerine.slimefunaddon.item;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ProfileDataController;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.backpacks.SlimefunBackpack;
import io.github.thebusybiscuit.slimefun4.implementation.listeners.BackpackListener;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import lombok.Getter;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import me.cyantangerine.slimefunaddon.model.VanillaItemName;
import me.cyantangerine.slimefunaddon.utils.Utils;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.swing.*;

public abstract class SmartContainer extends SlimefunBackpack implements Listener {
    /** 一次创造模式点击会连发两个包，动作只做一次 */
    private static final long CREATIVE_ACTION_DEDUP_MILLIS = 250L;
    /** 幽灵占位物的兜底回收窗口：客户端可能过几秒才把倒出的物品写回某个格子 */
    private static final long CREATIVE_LEAK_WINDOW_MILLIS = 15_000L;
    /**
     * 占位物的 PDC 标记。客户端把 BundleMeta 里的玻璃瓶/冰"倒出来"时会保留完整 NBT，
     * 凭这个标记就能精确回收，绝不误伤玩家自己的普通玻璃瓶/冰。
     * 用不带 Plugin 的构造，是为了在注册前的静态物品模板里也能打标。
     */
    private static final NamespacedKey PLACEHOLDER_KEY = new NamespacedKey("cyantestaddon", "sc_placeholder");
    /** 1.19.2 客户端把 bundle 满格写死为 64 权重，没有 setMaxStackSize 时用它做映射 */
    public static final int LEGACY_BUNDLE_MAX_WEIGHT = 64;
    /**
     * 1.20.5+ 的 {@code ItemMeta#setMaxStackSize(Integer)}，写的是 NMS 的 max_stack_size 组件。
     * 编译目标是 1.19.2，只能反射；老服务端上为 null，见 {@link #createFiller(int)}。
     */
    private static final Method SET_MAX_STACK_SIZE = findSetMaxStackSize();
    /** 首次命名：轮询检测绑定完成的周期与最长次数（10 tick × 60 ≈ 30 秒） */
    private static final long INIT_WATCH_PERIOD_TICKS = 10L;
    private static final int INIT_WATCH_MAX_CHECKS = 60;
    /** Slimefun 绑定背包前留在 lore 里的两行占位 */
    private static final String LORE_ID_PREFIX = ChatColor.GRAY + "ID: ";
    private static final String ID_PLACEHOLDER = "<ID>";
    private static final String LORE_OWNER_PREFIX = ChatColor.GRAY + "所有者: ";
    private static final String BAR_PREFIX = ChatColor.GRAY + "容量 ";
    private static final String CONTAINER_FULL = ChatColor.YELLOW + "[ " + ChatColor.WHITE + " %s " + ChatColor.YELLOW + " ] 已经满了，无法再装载。";
    private static final String NOT_BOUND = ChatColor.RED + "背包尚未命名，请先手持右键打开一次完成初始化。";
    private static final String NOT_HELD = ChatColor.YELLOW + "请把背包放到主手或副手，再右键使用来设置名称；设置完成后即可在原版物品栏正常使用。";

    private static final String DEFAULT_LOAD_ONLY_MSG = ChatColor.RED + "该容器只能存放非潜影盒、非背包的物品";
    private static final String DEFAULT_LOAD_ERROR_MSG = ChatColor.GRAY + "容器里没有可装载的物品。";

    protected String getLoadOnlyMsg() {
        return DEFAULT_LOAD_ONLY_MSG;
    }
    protected String getLoadErrorMsg() {
        return DEFAULT_LOAD_ERROR_MSG;
    }

    @Getter
    private Plugin plugin;
    @Getter
    private ResourceLib resourceLib;
    private FoliaLib foliaLib;
    private NamespacedKey usedKey;


    /** 玩家 UUID -> 右键打开背包时手上那个 Cooler 物品（Folia 下会被多个线程访问） */
    private final ConcurrentMap<UUID, ItemStack> opened = new ConcurrentHashMap<>();
    /** 玩家 UUID -> 真正打开的背包 Inventory（用来区分普通箱子/其它菜单） */
    private final ConcurrentMap<UUID, Inventory> openViews = new ConcurrentHashMap<>();
    /** 防止一次异步背包操作还没回来就又发起一次，导致重复消耗；value 是一次操作的令牌 */
    private final ConcurrentMap<UUID, Object> busy = new ConcurrentHashMap<>();
    /**
     * 首次命名期间等待绑定完成的任务，完成后把冰占位换成容量显示
     */
    private final ConcurrentMap<UUID, WrappedTask> initWatchers = new ConcurrentHashMap<>();
    /**
     * 玩家 UUID -> 上一次创造模式右键动作的时间，用来合并同一次点击的两个包
     */
    private final ConcurrentMap<UUID, Long> creativeActionAt = new ConcurrentHashMap<>();
    /**
     * 玩家 UUID -> 最近一次检测到占位物被倒出的时间（PDC 丢失时靠它兜底识别）
     */
    private final ConcurrentMap<UUID, Long> creativeSpillAt = new ConcurrentHashMap<>();
    /**
     * 玩家 UUID -> 客户端光标上正拿着冰柜的时间（创造模式服务端不跟踪 carried item）
     */
    private final ConcurrentMap<UUID, Long> creativeCarryingAt = new ConcurrentHashMap<>();

    @ParametersAreNonnullByDefault
    public SmartContainer(int size, ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(size, itemGroup, item, recipeType, recipe);
    }

    protected abstract Material getDefaultPlaceholder();
    protected abstract Material getPlaceholder();
    protected abstract int getCapacity();

    /**
     * 单格允许的物品数 = min({@link #VANILLA_MAX_STACK_SIZE}, 物品原版上限 × 本系数)。
     * SmartCooler = 1（原版行为，果汁/药水一格一瓶）；SmartBackpack = 4（四倍）。
     * 之所以是系数而不是写死「一格一瓶」，是因为抽离父类后容器不再只装果汁：
     * 每个物品的 {@link ItemStack#getMaxStackSize()} 都不一样，见 {@link #getStackLimit(ItemStack)}。
     */
    protected abstract int getStackMultiplier();

    /**
     * 原版单格堆叠上限。1.20.5+ 由 {@code max_stack_size} 组件决定，默认 <b>64</b>，
     * 组件的合法范围只有 1..99；1.19.2 则由材质写死（1 / 16 / 64）。
     */
    public static final int VANILLA_MAX_STACK_SIZE = 64;

    /**
     * 单格堆叠上限 = min(64, 物品原版上限 × 容器系数)，<b>封顶 64</b>。
     *
     * <p>这里不能按系数无限放大：Bukkit 的 {@code ItemStack#setAmount()} 只是把数字写进
     * NMS 的 count 字段、<b>不做校验</b>，所以 256 确实能写进 Inventory，但那是客户端
     * 不认识的非法状态 —— GUI 同步、点击拆分、Slimefun 背包存档往返都会按原版上限处理，
     * 实测超过 64 的整叠会直接消失。而且 256 已超出 {@code max_stack_size} 组件的
     * 合法上限 99，连声明都做不到。</p>
     *
     * <p>封顶后系数仍对小堆叠物品生效：玻璃瓶 / 药水 1→4、末影珍珠 / 雪球 16→64、
     * 圆石等 64 堆叠物品维持 64。背包 GUI 里的原版点击仍按 vanilla maxStackSize 合并，
     * 插件只在批量装载（{@link #transfer}）与快捷存入（putIn）时执行这个上限。</p>
     */
    protected int getStackLimit(ItemStack item) {
        int base = item.getMaxStackSize();
        if (base <= 0) {
            base = 1; // 空气 / 异常材质兜底
        }
        long limit = (long) base * Math.max(1, getStackMultiplier());
        return (int) Math.min(limit, VANILLA_MAX_STACK_SIZE);
    }

    /**
     * 造一个配套的物品模板。BUNDLE 才有收纳袋那种图标进度条；
     * ID / 所有者两行 lore 是 Slimefun 绑定背包用的，一行都不能删。
     */
    protected static SlimefunItemStack createItemStack(String id, String name, Supplier<ItemStack> getDefaultPlaceholder, String... lore) {
        SlimefunItemStack stack = new SlimefunItemStack(
                id,
                Material.BUNDLE,
                name,
                lore
        );
        // 刚合成出来、还没绑定背包时也要盖住客户端的原版收纳袋 tooltip：
        // 在物品模板里就塞满冰，注册/配方克隆出去的每一份都会带上。
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            bundle.setItems(List.of(markPlaceholder(getDefaultPlaceholder.get())));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Method findSetMaxStackSize() {
        try {
            return ItemMeta.class.getMethod("setMaxStackSize", Integer.class);
        } catch (NoSuchMethodException ignored) {
            return null; // 1.19.2：没有 max_stack_size 组件
        }
    }

    @Override
    public void preRegister() {
        super.preRegister(); // SimpleSlimefunItem 在这里装上 getItemHandler()
        plugin = getAddon().getJavaPlugin();
        resourceLib = Utils.initialCheck(plugin);
        foliaLib = resourceLib.getFoliaLib();
        usedKey = new NamespacedKey(plugin, "smart_container_used");
    }

    @EventHandler
    public void logger(PlayerInteractEvent event) {
        ResourceLib.Debug.msg(event);
    }
    @EventHandler
    public void logger(InventoryClickEvent event) {
        ResourceLib.Debug.msg(event);
    }

    // 在主手右键使用物品
    public void onHeldRightClick(PlayerRightClickEvent event, ItemUseHandler parent) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        PlayerInteractEvent interact = event.getInteractEvent();

//        ResourceLib.Debug.msg(event.getInteractEvent());

        boolean uninitialized = !isInitialized(item);
//        boolean onHeld
//        if (uninitialized && !isHeld(player, item)) {
//            // 防御性兜底：未初始化且不在主/副手时不走 Slimefun 的命名流程
//            event.setUseItem(Event.Result.DENY);
//            closeVanillaOverlay(player);
//            player.sendMessage(NOT_HELD);
//            return;
//        }
        if (uninitialized) {
            // 首次要输入背包名称，客户端可能还留着原版收纳袋/物品栏浮窗，先关掉再让人看聊天提示。
            // 手持（含快捷栏选中）才会走到这里，交给 Slimefun 的 chat catcher 命名。
            // closeVanillaOverlay(player);
            watchInitialization(player, item);
            parent.onRightClick(event);
            return;
        }

        switch (interact.getAction()) {
            case Action.RIGHT_CLICK_BLOCK:
                // 手持智能容器右键容器：默认改成从容器批量装载可收纳的物品
                Block block = interact.getClickedBlock();
                if (Utils.isContainerBlock(block)) {
                    if (player.isSneaking()) {
                        // 潜行 = 正常开箱。Folia 26.3 的 useItemOn 在「潜行且手上有物品」时会跳过
                        // BlockState.useItemOn / useWithoutItem，只把 useItem 放开并不能打开容器，
                        // 所以这里取消原版交互，改为主动打开实时容器 GUI（见 openContainerFor）。
                        event.cancel();
                        interact.setUseItemInHand(Event.Result.DENY);
                        interact.setUseInteractedBlock(Event.Result.DENY);
                        //noinspection DataFlowIssue
                        resourceLib.openBlockContainer(player, block);

                    }
                    event.cancel(); // 同时把 PlayerRightClickEvent 的 item / block 都设为 DENY
                    interact.setUseItemInHand(Event.Result.DENY);
                    interact.setUseInteractedBlock(Event.Result.DENY);
                    loadFromContainer(player, item, block);
                    break;
                } else {
                    // 正常打开背包，同时不要触发原版block的事件
//                    event.getInteractEvent().get()
                    event.cancel();
                }
            default:
                // 无论当前开着原版物品栏还是另一个冰柜 GUI，都先关掉并把光标上的冰柜放回背包。
                // 顺序不能反：closeInventory 会触发 InventoryCloseEvent，它会清掉 opened/openViews，
                // 先 put 的话新冰柜的引用会被旧 GUI 的关闭事件误清。
                UUID uuid = player.getUniqueId();
                closeCurrentView(player);
                openViews.remove(uuid);
                // opened.remove(uuid);
                opened.put(uuid, item);
                applyDisplay(item, readUsed(item)); // 打开瞬间的缓存值，开好后会用真实 Inventory 覆盖
                parent.onRightClick(event);
                break;
        }
    }

    @Override
    @Nonnull
    public ItemUseHandler getItemHandler() { // SlimeFun 包装的右键点击事件
        ItemUseHandler parent = super.getItemHandler();
        return event -> onHeldRightClick(event, parent);
    }

    // ------------------------------------------------------------------
    // 容量显示：只在"看得见真实 Inventory"的时候更新
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBackpackOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        ItemStack item = opened.get(player.getUniqueId());
        if (!isOurContainer(item)) return;
        Inventory inv = event.getInventory();
        if (!isBackpackInventory(inv)) return;
        openViews.put(player.getUniqueId(), inv);
        // InventoryOpenEvent 可能是在全局区域线程触发的，改物品要在玩家所属线程上做
        resourceLib.runAtEntity(player, () -> applyDisplay(item, countUsedSlots(inv)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBackpackClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        // 界面关掉后客户端光标上的东西通常已经放回背包，冰柜携带状态一并作废
        creativeCarryingAt.remove(uuid);
        Inventory view = openViews.get(uuid);
        Inventory closed = event.getInventory();
        // closeInventory() 可能只是关掉原版物品栏浮窗；别把还没打开的冰柜引用一起清掉
        if (view == null || (view != closed && !view.equals(closed))) return;
        ItemStack item = opened.remove(uuid);
        openViews.remove(uuid);

        if (isOurContainer(item)) {
            applyDisplay(item, countUsedSlots(view));
            // 关掉 GUI 后客户端可能把本地预测的占位物放回物品栏，下一 tick 强制同步一次
            schedulePurge(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        opened.remove(uuid);
        openViews.remove(uuid);
        busy.remove(uuid);

        creativeActionAt.remove(uuid);
        creativeSpillAt.remove(uuid);
        creativeCarryingAt.remove(uuid);
        stopInitWatch(uuid);
    }

    protected abstract boolean isOurContainer(ItemStack item);

    // ------------------------------------------------------------------
    // 手持右键容器：批量装载
    // ------------------------------------------------------------------
    protected void loadFromContainer(Player player, ItemStack coolerItem, Block block) {
        UUID uuid = player.getUniqueId();
        Object token = busyLock(uuid);
        if (token == null) return;
        scheduleUnlock(uuid, token);

        // Folia 上容器 Inventory 只能在方块所属区域线程访问
        resourceLib.runAtBlock(block, ignored -> {
            Inventory container = resourceLib.readBlockContainerInventory(block);
            if (container == null) {
                busyUnlock(uuid, token);
                return;
            }

            List<ContainerSlot> found = collectItems(container);
            if (found.isEmpty()) {
                busyUnlock(uuid, token);
                resourceLib.runAtEntity(player, () -> player.sendMessage(getLoadErrorMsg()));
                return;
            }

            // 异步读背包 -> 回到同一个方块区域线程，一次性完成"扣容器 + 写背包"。
            // 读取和写入复用同一个 Inventory 对象，避免两次 getState 拿到不同实例。
            readBackpack(coolerItem, backpack -> resourceLib.runAtBlock(block, task -> {
                try {
                    LoadResult result = transfer(container, backpack, found);
                    if (result.total() == 0) {
                        String full = String.format(CONTAINER_FULL, containerName(coolerItem));
                        resourceLib.runAtEntity(player, () -> player.sendMessage(full));
                        return;
                    }
                    saveBackpack(backpack, null);
                    String message = loadMessage(result);
                    resourceLib.runAtEntity(player, () -> {
                        applyDisplay(coolerItem, countUsedSlots(backpack.getInventory()));
                        player.sendMessage(message);
                    });
                } finally {
                    busy.remove(uuid, token);
                }
            }), () -> {
                busy.remove(uuid, token);
                resourceLib.runAtEntity(player, () -> player.sendMessage(NOT_BOUND));
            });
        });
    }

    protected List<ContainerSlot> collectItems(Inventory inv) {
        List<ContainerSlot> found = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && isItemAllowed(stack, SlimefunItem.getByItem(stack))) {
                found.add(new ContainerSlot(i, stack.clone()));
            }
        }
        return found;
    }

    /**
     * 扣容器 + 写背包，全部在同一个区域线程上完成，避免中途被别的线程改动造成复制/丢失。
     *
     * <p>不能只看空槽：目标里已有的同类部分堆叠也要补满，且单格上限由
     * {@link #getStackLimit(ItemStack)} 决定（果汁 1/格，智能背包里玻璃瓶 4/格、
     * 16 堆叠物品 64/格；64 堆叠物品维持 64/格，一律不超过 {@link #VANILLA_MAX_STACK_SIZE}）。
     * 每个源槽位按「实际能放多少」扣减，可能只搬走一叠的一部分。</p>
     */
    protected LoadResult transfer(Inventory container, PlayerBackpack backpack, List<ContainerSlot> found) {
        Inventory target = backpack.getInventory();
        if (target == null) {
            return LoadResult.empty();
        }

        Map<String, Integer> byName = new LinkedHashMap<>();
        int total = 0;

        for (ContainerSlot snapshot : found) {
            ItemStack current = container.getItem(snapshot.slot());
            // 两次区域任务之间容器可能被人动过，只搬仍然对得上的槽位
            if (current == null || !current.isSimilar(snapshot.stack())) {
                continue;
            }

            int sourceAmount = current.getAmount();
            if (sourceAmount <= 0) {
                continue;
            }

            // 先补同类部分堆叠、再用空槽。每个源槽位都重新扫一遍目标，
            // 因为上一次写入可能刚把某个空槽变成半格，后面的槽位要接着补。
            List<int[]> destinations = findDestinations(target, snapshot.stack());
            int room = 0;
            for (int[] destination : destinations) {
                room += destination[1];
            }

            int take = Math.min(sourceAmount, room);
            if (take <= 0) {
                // 这类物品没地方放了，后面的槽位可能是别的物品，继续试
                continue;
            }

            // 按实际搬走的数量扣减容器库存（一格可能只搬走一部分）
            int remaining = sourceAmount - take;
            if (remaining <= 0) {
                container.setItem(snapshot.slot(), null);
            } else {
                ItemStack left = current.clone();
                left.setAmount(remaining);
                container.setItem(snapshot.slot(), left);
            }

            // destinations 是本轮刚算出来的，且整个回调独占区域线程，必然放得下 take 个
            placeInto(target, snapshot.stack(), destinations, take);

            byName.merge(itemName(snapshot.stack()), take, Integer::sum);
            total += take;
        }

        return new LoadResult(total, byName);
    }

    /**
     * 找出目标里能放 template 的位置，返回 {@code {槽位, 还能放几个}} 的列表，
     * 顺序是「已有同类部分堆叠」在前、「空槽」在后。
     * 单格上限由 {@link #getStackLimit(ItemStack)} 决定，所以同一物品在不同容器里容量不同。
     */
    private List<int[]> findDestinations(Inventory target, ItemStack template) {
        int limit = getStackLimit(template);
        List<int[]> partial = new ArrayList<>();
        List<int[]> empty = new ArrayList<>();

        for (int i = 0; i < target.getSize(); i++) {
            ItemStack stack = target.getItem(i);
            if (isEmptySlot(stack)) {
                empty.add(new int[]{i, limit});
            } else if (stack.isSimilar(template)) {
                int room = getStackLimit(stack) - stack.getAmount();
                if (room > 0) {
                    partial.add(new int[]{i, room});
                }
            }
        }
        partial.addAll(empty);
        return partial;
    }

    /** 按 {@link #findDestinations} 的结果写入，返回实际写入的数量（正常等于 amount）。 */
    private int placeInto(Inventory target, ItemStack template, List<int[]> destinations, int amount) {
        int left = amount;
        for (int[] destination : destinations) {
            if (left <= 0) {
                break;
            }
            int add = Math.min(destination[1], left);
            if (add <= 0) {
                continue;
            }

            ItemStack stack = target.getItem(destination[0]);
            if (isEmptySlot(stack)) {
                ItemStack placed = template.clone();
                placed.setAmount(add);
                target.setItem(destination[0], placed);
            } else {
                ItemStack grown = stack.clone();
                grown.setAmount(stack.getAmount() + add);
                target.setItem(destination[0], grown);
            }
            left -= add;
        }
        return amount - left;
    }

    private static boolean isEmptySlot(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** 容器里一个装着可收纳物品的槽位快照（必须在方块所属区域线程读取）。 */
    protected record ContainerSlot(int slot, ItemStack stack) { }

    /** 一次装载结果：总数量（按物品个数，不是格数） + 按物品名分组的数量。 */
    protected record LoadResult(int total, Map<String, Integer> byName) {
        static LoadResult empty() {
            return new LoadResult(0, Collections.emptyMap());
        }
    }

    protected String loadMessage(LoadResult result) {
        StringBuilder message = new StringBuilder(ChatColor.GREEN + "已从容器装载 ")
                .append(ChatColor.YELLOW).append(result.total())
                .append(ChatColor.GREEN).append(" 个物品");

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

    /** 装载汇总用的名字：Slimefun 物品用显示名，其余用自定义名 / 原版中文名。 */
    private static String itemName(ItemStack stack) {
        SlimefunItem sfItem = SlimefunItem.getByItem(stack);
        if (sfItem != null) {
            return sfItem.getItemName();
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        String chinese = VanillaItemName.of(stack.getType());
        return ChatColor.WHITE + (chinese != null ? chinese : stack.getType().name());
    }

    /** 提示消息里容器自己的名字，找不到显示名时退回材质名。 */
    private static String containerName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return item.getType().name();
    }

    // ------------------------------------------------------------------
    // 覆盖原版收纳袋行为：只走"背包存取允许的物品"，其它物品一律拒绝
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        Inventory view = openViews.get(player.getUniqueId());
        boolean inCoolerGui = view != null && (view == top || view.equals(top));

        // SmartCooler / SmartBackpack 各注册一份 Listener，但 isOurContainer 是排他的：
        // 一次点击里只有一种容器会被认领，另一实例会自然落到「无关背包」分支直接返回。
        boolean eventCanceledBySlimefun = event.isCancelled();
        if (eventCanceledBySlimefun) {
            // 在容器Gui内无法点击其他容器，event可能被slimefun取消。
            ResourceLib.Debug.msg(player,"侦测到被取消事件！incooler: " + inCoolerGui);
            if (inCoolerGui) { // 在容器内点击另一个容器，跳转到另一个容器的页面。
                if (ClickType.RIGHT.equals(event.getClick())) {
                    ItemStack current = event.getCurrentItem();
                    boolean currentCooler = isOurContainer(current);
                    if (currentCooler) {
                        // 右击另一个容器，关闭当前容器并打开另一容器。
                        openCooler(player, current);
                    }
                }
            }
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

        // 创造模式的点击是 InventoryCreativeEvent（ClickType.CREATIVE），客户端会先把
        // bundle 的取出/放入结果画在本地再发给服务端。必须在"是不是冰柜 GUI"判断之后分流：
        // GUI 里的创造点击只是普通搬格子，绝不能走下面的 putIn / openCooler，否则会重复插入。
        if (event instanceof InventoryCreativeEvent creative) {
            if (inCoolerGui) {
                int raw = event.getRawSlot();
                boolean topClicked = raw >= 0 && raw < top.getSize();
                ItemStack incoming = topClicked ? event.getCursor() : null;
                if (incoming != null && !incoming.getType().isAir() && !isAllowedItem(incoming)) {
                    event.setCancelled(true);
                    player.sendMessage(getLoadOnlyMsg());
                }
                return;
            }
            handleCreativeClick(creative, player);
            return;
        }


        ResourceLib.Debug.msg(player,"incoolergui:" + inCoolerGui);
        // 1) 背包 GUI 开着：Slimefun 自己只检查 currentItem，会漏掉光标/数字键/副手，
        //    这里补上完整的过滤。
        if (inCoolerGui) {
            ItemStack incoming = incomingItem(event, player, top);
            if (incoming != null) {
                ResourceLib.Debug.msg(player,"incoming " + incoming.getType().name() + incoming.getAmount());
            }
            if (incoming != null && !incoming.getType().isAir() && !isAllowedItem(incoming)) {
                // 存入物品
                event.setCancelled(true);
                player.sendMessage(getLoadOnlyMsg());
                return;
            }

            if (ClickType.RIGHT.equals(event.getClick())) {
                ItemStack current = event.getCurrentItem();
                boolean currentCooler = isOurContainer(current);
                if (currentCooler) {
                    // 右击另一个容器，关闭当前容器并打开另一容器。
                    openCooler(player, current);
                }
            }
            return;
        }

        // 2) 玩家物品栏里操作 BUNDLE：拦掉原版的插入/取出/倾倒
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean currentCooler = isOurContainer(current);
        boolean cursorCooler = isOurContainer(cursor);
        if (!currentCooler && !cursorCooler) {
            // 操作无关背包，无需任何处理。
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

        boolean uninitialized = !isInitialized(coolerItem);
        if (uninitialized) { // 未初始化，仅允许拿起和放下操作。
            // 空手左键点击 拿起/放下
            if (!hasTarget && ClickType.LEFT.equals(click)) return;
            // 拿物品点收纳袋或收纳袋点物品，仅允许右键（交换），其他禁止
            if (hasTarget && ClickType.RIGHT.equals(click)) return;
            event.setCancelled(true);
            player.sendMessage(NOT_BOUND);
            resourceLib.runAtEntity(player, e -> closeCurrentView(player));
            return;
        }

        // 已初始化的背包。
        if (!hasTarget) { // 空手/空槽
            switch (click) {
                case LEFT: return; // 左键 拿起/放下 收纳袋
                case RIGHT: // 右键
                    event.setCancelled(true); // 拦截原版操作
                    if (targetInSlot) { // 手里收纳袋右击空槽，拦截原版物品释放，并打开背包
                        openCooler(player, coolerItem);
                    } else { // 空手右击收纳袋，原版无效果，打开背包
                        openCooler(player, coolerItem);
                    }
                    break;
            }
        } else { // 手有物品/槽有物品
            if (isOurContainer(target)) { // 两个背包相互点
                ResourceLib.Debug.msg(player,"手里的物品", cursor);
                ResourceLib.Debug.msg(player,"槽里的物品", current);
                if (ClickType.RIGHT.equals(click)) return; // 原版操作，交换两个背包
                // if (ClickType.LEFT.equals(click)) {
                // 这里覆盖为交换两个背包
                event.setCancelled(true);
                int targetSlot = event.getSlot();
                player.setItemOnCursor(current);
                player.getInventory().setItem(targetSlot, cursor);
//                player.saveData();
                player.updateInventory();
                ResourceLib.Debug.msg(player, "交换成功");
                return;
            }
            switch (click) {
                case LEFT: // 存入收纳袋，需拦截事件，并存入背包
//                    if (targetInSlot) {
//                        // 用手里的背包 点槽里的物品
//                    } else {
//                        // 用手里的物品，点槽里的收纳袋
//                    }
                    event.setCancelled(true);
                    if (isAllowedItem(target)) {
                        putIn(player, coolerItem, target, event, targetInSlot);
                    } else {
                        player.sendMessage(getLoadOnlyMsg());
                    }
                case RIGHT: return; // 手里/槽里的物品/背包 正常交换
            }
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

        boolean currentCooler = isOurContainer(current);
        boolean cursorCooler = isOurContainer(cursor);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean outside = event.getSlotType() == InventoryType.SlotType.OUTSIDE;
        boolean markedSpill = isMarkedPlaceholder(cursor);
        boolean looseSpill = isLoosePlaceholder(cursor);
        // 槽位包用带窗口的识别，避免平时误伤玩家自己的玻璃瓶
        boolean spilled = isOurPlaceholder(player, cursor);
        boolean juice = isAllowedItem(cursor);

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
        if (!isOurContainer(coolerItem)) {
            return;
        }

        if (juice) {
            putIn(player, coolerItem, cursor, event, false);
            return;
        }
        if (!cursorEmpty && !cursorCooler && !spilled) {
            player.sendMessage(getLoadOnlyMsg());
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
            resourceLib.warn("同步创造模式光标失败", ex);
        }
    }

    /** 下一 tick 在玩家所属实体线程做一次完整回收（Folia 要求实体改动必须在区域线程）。 */
    private void schedulePurge(Player player) {
        resourceLib.runAtEntity(player, () -> purgeLeakedPlaceholders(player));
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
            resourceLib.warn("回收创造模式残留占位物失败", ex);
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
        resourceLib.runAtEntity(player, () -> closeVanillaOverlay(player));
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

    private boolean isLoosePlaceholder(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Material type = item.getType();
        if ((type != getDefaultPlaceholder() && type != getPlaceholder())
                || SlimefunItem.getByItem(item) != null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants())) {
            return false;
        }
        int amount = item.getAmount();
        // createFiller 在 1.20.5+ 用 used(≤capacity) 个，1.19.2 用映射后的 1..64 个，
        // 宽松识别要覆盖两条路径的数量域，否则 used 较高时倒出的占位物会漏判。
        return type == getDefaultPlaceholder()
                ? amount == LEGACY_BUNDLE_MAX_WEIGHT
                : amount > 0 && amount <= Math.max(getCapacity(), LEGACY_BUNDLE_MAX_WEIGHT);
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

    /** 快捷存入：原位置先清空，先合并同类部分堆叠再用空槽，放不下的部分退还。 */
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
            List<int[]> destinations = findDestinations(inv, toInsert);
            int room = 0;
            for (int[] destination : destinations) {
                room += destination[1];
            }

            int amount = toInsert.getAmount();
            int placed = placeInto(inv, toInsert, destinations, Math.min(amount, room));
            if (placed < amount) {
                // 一部分（或全部）塞不下：把没放进去的退还给玩家
                ItemStack rest = toInsert.clone();
                rest.setAmount(amount - placed);
                giveBack(player, rest);
                player.sendMessage(String.format(CONTAINER_FULL, containerName(coolerItem)));
            }
            if (placed > 0) {
                saveBackpack(backpack, coolerItem);
            }
        });
    }

    /**
     * 打开小冰柜。走 Slimefun 自己的 {@code BackpackListener#openBackpack}，
     * 这样首次使用时的命名/建包、PDC 绑定、权限与"背包已被他人打开"检查都和官方一致。
     */
    private void openCooler(Player player, ItemStack coolerItem) {
        // 等本次点击事件处理完、并回到玩家所属区域线程再开菜单（Folia 上不能在事件里直接切容器）
        resourceLib.runAtEntity(player, () -> {
            UUID uuid = player.getUniqueId();
            // 无论当前是原版物品栏还是另一个冰柜 GUI，一律先关掉：关界面会把客户端
            // 光标上的冰柜放回背包，避免"GUI 打开了冰柜还挂在手上"，也让 Slimefun 的
            // BackpackListener 清掉旧背包的占用记录。
            // 顺序很重要：closeInventory 触发的 InventoryCloseEvent 会清 opened/openViews，
            // 所以本次冰柜的状态必须在关完之后再登记，否则会被旧 GUI 的关闭事件误清。
            closeCurrentView(player);
            openViews.remove(uuid);
            opened.remove(uuid);
            creativeCarryingAt.remove(uuid);

            boolean uninitialized = !isInitialized(coolerItem);
            boolean creative = player.getGameMode() == GameMode.CREATIVE;
            if (creative) {
                purgeLeakedPlaceholders(player);
            }

            opened.put(uuid, coolerItem);
            applyDisplay(coolerItem, readUsed(coolerItem));

            // Slimefun 的 chat catcher 要求物品必须在主手/副手；不满足时只提示，
            // 绝不触发它的「请输入名称」机制。
            if (uninitialized && !isHeld(player, coolerItem)) {
                opened.remove(uuid);
                player.sendMessage(NOT_HELD);
                return;
            }

            try {
                BackpackListener listener = Slimefun.getBackpackListener();
                ResourceLib.Debug.msg(player, "打开背包...");
                listener.openBackpack(player, coolerItem, this);
                // onHeldRightClick();
                if (uninitialized) {
                    watchInitialization(player, coolerItem);
                }
            } catch (RuntimeException | LinkageError ex) {
                opened.remove(uuid);
                resourceLib.warn("打开小冰柜失败", ex);
            }
        });
    }

    /**
     * 关闭当前界面前先把光标上的冰柜放回背包。
     * 生存模式的服务端 carried 是真实物品；创造模式主要靠客户端关界面时自己放回，
     * 两条路都覆盖，保证打开 GUI 后冰柜不会还挂在光标上。
     */
    private void closeCurrentView(Player player) {
        try {
            ItemStack carried = player.getItemOnCursor();
            if (!carried.getType().isAir()) {
                player.setItemOnCursor(null);
                giveBack(player, carried.clone());
            }
            ResourceLib.Debug.msg(player, "关闭页面 " + carried.getType().name());
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("放回光标上的小冰柜失败", ex);
        }
        closeVanillaOverlay(player);
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
            resourceLib.warn("关闭原版物品栏浮窗失败", ex);
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
            if (!player.isOnline() || checks[0]++ >= INIT_WATCH_MAX_CHECKS || !isOurContainer(item)) {
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
        if (!isOurContainer(item) || !item.hasItemMeta()) {
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
    private ItemStack createIcePlaceholder() {
        return markPlaceholder(new ItemStack(getDefaultPlaceholder(), LEGACY_BUNDLE_MAX_WEIGHT));
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
    public void readBackpack(ItemStack item, Consumer<PlayerBackpack> callback, Runnable onError) {
        try {
            CompletableFuture<PlayerBackpack> future = PlayerBackpack.getAsync(item);
            future.thenAccept(result -> {
                if (result instanceof PlayerBackpack backpack) {
                    callback.accept(backpack);
                } else {
                    onError.run();
                }
            }).exceptionally(ex -> {
                resourceLib.warn("读取背包失败", ex);
                onError.run();
                return null;
            });
        } catch (LinkageError ex) {
            resourceLib.warn("读取背包失败", ex);
            onError.run();
        }

    }

    /** 异步读背包 -> 回到玩家所属区域线程执行 action。 */
    private void withBackpack(Player player, ItemStack item, Consumer<PlayerBackpack> action) {
        UUID uuid = player.getUniqueId();
        Object token = busyLock(uuid);
        if (token == null) return;
        scheduleUnlock(uuid, token);

        readBackpack(item, backpack -> resourceLib.runAtEntity(player, () -> {
            try {
                action.accept(backpack);
            } finally {
                busyUnlock(uuid, token);
            }
        }), () -> busyUnlock(uuid, token));
    }

    /** 兜底：DB 没回调时别把玩家永久锁住。用 remove(key, token)，避免旧任务误清新操作的锁。 */
    protected void scheduleUnlock(UUID uuid, Object token) {
        foliaLib.getScheduler().runLater(() -> busyUnlock(uuid, token), 100L);
    }
    protected void busyUnlock(UUID uuid, Object token) {
        busy.remove(uuid, token);
    }
    protected Object busyLock(UUID uuid) {
        Object token = new Object();
        if (busy.putIfAbsent(uuid, token) != null) {
            return null;
        }
        return token;
    }


    /** dev 分支走数据库的 saveBackpackInventory，RC-32 走 markDirty + save。 */
    public void saveBackpack(PlayerBackpack backpack, ItemStack containerItem) {
        try {
            ProfileDataController controller = Slimefun.getDatabaseManager().getProfileDataController();
            if (controller == null) throw new NullPointerException();
            controller.saveBackpackInventory(backpack);
        } catch (LinkageError | NullPointerException ex) {
            resourceLib.warn("保存背包失败", ex);
        }
        if (containerItem != null) applyDisplay(containerItem, countUsedSlots(backpack.getInventory())); // 用真实格数，不再猜测 -1
    }



    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

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
        return used == null ? 0 : Utils.clamp(used, getCapacity());
    }

    /** 把容量写进物品：Lore 文本条 + 收纳袋/耐久条 + PDC 缓存 */
    private void applyDisplay(ItemStack item, int used) {
        if (!isOurContainer(item)) {
            return;
        }
        int capacity = getCapacity();
        used = Utils.clamp(used, capacity);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        // 1) Lore 文本条（保留 Slimefun 的 ID / 所有者行，只替换自己那一行）
        List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : Collections.emptyList());
        lore.removeIf(line -> line.startsWith(BAR_PREFIX));
        lore.add(BAR_PREFIX + ChestMenuUtils.getProgressBar(getCapacity() - used, capacity)
                + ChatColor.GRAY + " " + used + "/" + capacity);
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
        }

//        else if (meta instanceof Damageable damageable && item.getType().getMaxDurability() > 0) {
//            // 3) 没有用 BUNDLE 时的降级方案：耐久条
//            int max = item.getType().getMaxDurability();
//            int damage = used == 0 ? 0 : Math.min(max - 1, Math.round((float) max * used / CAPACITY));
//            damageable.setDamage(damage);
//            meta.setUnbreakable(true);
//            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
//        }

        if (usedKey != null) {
            meta.getPersistentDataContainer().set(usedKey, PersistentDataType.INTEGER, used);
        }
        item.setItemMeta(meta);
    }

    /**
     * 画条用的占位物。两代客户端的 bundle 权重算法不同，必须分开处理：
     * <ul>
     *     <li><b>1.20.5+</b>：权重 = 数量 / {@code max_stack_size} 组件（早期按 64 权重计，
     *         26.x 归一化到 1.0，比例一致）。把组件设成 {@link #getCapacity()}、数量设成 used，
     *         条就是 used / CAPACITY。此时占位物用什么材质都不影响权重，冰柜可继续用玻璃瓶。</li>
     *     <li><b>1.19.2</b>：没有这个组件，客户端固定按 64 权重算，只能把 used 线性映射成
     *         1..64 个，分母由材质原版上限决定。</li>
     * </ul>
     * 重构时只搬了 legacy 映射，导致 1.21 上 {@code GLASS_BOTTLE}（原版上限 1）的权重恒为
     * 数量 × 64 / 1 ≥ 64，图标条一直画满。这里恢复被删掉的反射分支，对所有子类统一生效。
     */
    private ItemStack createFiller(int used) {
        int capacity = getCapacity();
        Material type = getPlaceholder();
        int amount = Math.max(1, Math.min(used, capacity));
        int legacyAmount = Math.max(1, Math.min(LEGACY_BUNDLE_MAX_WEIGHT,
                (int) Math.ceil(LEGACY_BUNDLE_MAX_WEIGHT * (double) amount / capacity)));

        if (SET_MAX_STACK_SIZE == null) {
            // 1.19.2：客户端固定按 64 权重算，只能用数量映射
            return markPlaceholder(new ItemStack(type, legacyAmount));
        }

        ItemStack filler = new ItemStack(type, amount);
        try {
            ItemMeta meta = filler.getItemMeta();
            if (meta == null) {
                return markPlaceholder(new ItemStack(type, legacyAmount));
            }
            SET_MAX_STACK_SIZE.invoke(meta, capacity);
            // maxStackSize 与 PDC 标记一次写完，避免两次 setItemMeta 互相覆盖
            meta.getPersistentDataContainer()
                    .set(PLACEHOLDER_KEY, PersistentDataType.BYTE, (byte) 1);
            filler.setItemMeta(meta);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            // 新版服务端上反射意外失败时仍要能画条，退回 64 权重映射
            return markPlaceholder(new ItemStack(type, legacyAmount));
        }
        return filler;
    }


    private boolean isBackpackInventory(Inventory inv) {
        // 靠 holder 精确识别，尺寸只做合法性兜底
        if (inv == null || inv.getType() != InventoryType.CHEST
                || inv.getSize() < 9 || inv.getSize() > 54 || inv.getSize() % 9 != 0
                || inv.getLocation() != null) {
            return false;
        }

        try {
            Object holder = inv.getHolder();
            String backpackClass = PlayerBackpack.class.getName();
            // dev 分支：holder 就是 PlayerBackpack，精确匹配，别把别的插件的虚拟箱子认进来
            return holder != null && holder.getClass().getName().equals(backpackClass);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public boolean hasOpenView(UUID uuid) {
        return openViews.containsKey(uuid);
    }

    /**
     * 用具体容器自己的 {@link #isItemAllowed} 规则判断能否收纳。
     * SmartCooler 只认 Slimefun 的 Juice；SmartBackpack 沿用 Slimefun 背包的默认过滤
     * （非潜影盒、非背包）。
     */
    public boolean isAllowedItem(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && isItemAllowed(item, SlimefunItem.getByItem(item));
    }


    /**
     * 按格子顺序找出整份玩家背包里的小冰柜：36 个主格（含 9 格快捷栏 + 27 格背包）、
     * 装备栏、副手。不依赖手持物品或当前选中的快捷栏格子。
     * 返回列表而不是单个，自动饮用时才能在这个冰柜没果汁的情况下继续看下一个。
     */
    protected List<ItemStack> findCoolers(Player player) {
        PlayerInventory inv = player.getInventory();
        List<ItemStack> found = new ArrayList<>();

        for (ItemStack item : inv.getStorageContents()) {
            if (isOurContainer(item)) {
                found.add(item);
            }
        }
        for (ItemStack item : inv.getArmorContents()) {
            if (isOurContainer(item)) {
                found.add(item);
            }
        }

        ItemStack offHand = inv.getItemInOffHand();
        if (isOurContainer(offHand)) {
            found.add(offHand);
        }
        return found;
    }

    /**
     * 只要一个冰柜时用（创造模式点击的兜底查找）。
     */
    private ItemStack findCooler(Player player) {
        List<ItemStack> found = findCoolers(player);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 优先取真正拿在手上的冰柜；Slimefun 的命名校验只认主手/副手。
     */
    private ItemStack findHeldCooler(Player player, ItemStack fallback) {
        PlayerInventory inv = player.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (isOurContainer(main)) {
            return main;
        }
        ItemStack off = inv.getItemInOffHand();
        if (isOurContainer(off)) {
            return off;
        }
        return fallback;
    }

}
