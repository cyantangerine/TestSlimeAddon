package me.cyantangerine.slimefunaddon.item;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import me.cyantangerine.slimefunaddon.model.StorageAccess;
import me.cyantangerine.slimefunaddon.model.StorageFilter;
import me.cyantangerine.slimefunaddon.model.StorageNetwork;
import me.cyantangerine.slimefunaddon.model.StorageTransfer;
import me.cyantangerine.slimefunaddon.utils.Utils;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 智能存储背包「卸载终端」与「加载终端」。两者共用一个类，用 {@link Kind} 区分。
 *
 * <p><b>形态</b>：可放置方块，通过相邻的智能存储连接器泛洪发现容器网络，
 * 配置（模式 / 匹配标签 / 加载范围 / 40 格过滤列表）按方块持久化在 BlockStorage。</p>
 *
 * <p><b>触发</b>：手持智能背包 / 冰柜右键终端 = 执行卸载或加载（由
 * {@link SmartContainer#onHeldRightClick} 直接派发到这里）；空手或手持其它物品
 * 右键 = 打开 9×6 配置页（BlockUseHandler）。</p>
 *
 * <p><b>权限</b>：执行动作需要「至少在一个交互终端上是成员」；打开配置页人人可看，
 * 但只有「至少在一个交互终端上是管理员」才能改动，否则整页只读。见 {@link StorageAccess}。</p>
 *
 * <p><b>GUI</b>：第一行 + 第一列是菜单（0=模式、1=匹配标签、2=加载范围（仅加载终端）、
 * 3=权限状态、其余黑玻璃），剩余 5 行 × 8 列 = 40 格过滤物品，不分页。
 * 过滤格里的物品只作为模板，数量一律归一为 1，多出来的返还玩家。</p>
 */
public class SmartStorageTransferTerminal extends SlimefunItem implements Listener {

    public enum Kind { UNLOAD, LOAD }

    public static final String UNLOAD_ID = "SMART_STORAGE_UNLOAD_TERMINAL";
    public static final String LOAD_ID = "SMART_STORAGE_LOAD_TERMINAL";

    private static final int GUI_SIZE = 54;
    private static final int FILTER_SLOTS = 40;
    private static final int SLOT_MODE = 0;
    private static final int SLOT_TAGS = 1;
    private static final int SLOT_FILL = 2;
    private static final int SLOT_ACCESS = 3;

    private static final String MODE_KEY = "smart_storage_terminal_mode";
    private static final String TAGS_KEY = "smart_storage_terminal_tags";
    private static final String FILL_KEY = "smart_storage_terminal_fill";
    private static final String FILTERS_KEY = "smart_storage_terminal_filters";

    private static final String TITLE_UNLOAD = colorize("&8智能存储卸载终端 · 配置");
    private static final String TITLE_LOAD = colorize("&8智能存储加载终端 · 配置");

    private static final String NO_MEMBER = colorize(
            "&c你需要至少是一个智能存储交互终端的成员，才能使用这个终端。");
    private static final String READ_ONLY = colorize("&e你没有管理员权限，配置页面为只读。");
    private static final String ADMIN_ONLY = colorize("&c只有管理员可以修改终端配置。");
    private static final String NOT_BOUND = colorize("&c背包尚未初始化，请先手持右键打开一次。");
    private static final String NO_CONTAINER = colorize("&7该终端尚未通过存储连接器连接任何容器。");

    private final Kind kind;
    private final SmartStorageConnector connectorItem;
    private final SmartStorageInterface interfaceItem;

    private Plugin plugin;
    private ResourceLib resourceLib;
    private final Map<UUID, Session> views = new ConcurrentHashMap<>();

    public SmartStorageTransferTerminal(Kind kind, ItemGroup itemGroup, SlimefunItemStack item,
                                        RecipeType recipeType, ItemStack[] recipe,
                                        SmartStorageConnector connectorItem,
                                        SmartStorageInterface interfaceItem) {
        super(itemGroup, item, recipeType, recipe);
        this.kind = kind;
        this.connectorItem = connectorItem;
        this.interfaceItem = interfaceItem;
    }

    @Override
    public void preRegister() {
        super.preRegister();
        plugin = getAddon().getJavaPlugin();
        resourceLib = Utils.initialCheck(plugin);
        addItemHandler((BlockUseHandler) this::onRightClick);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public static SlimefunItemStack createItemStack(Kind kind) {
        if (kind == Kind.UNLOAD) {
            return new SlimefunItemStack(UNLOAD_ID, Material.CAULDRON,
                    "&b智能存储背包卸载终端",
                    "&7放置后通过智能存储连接器连接箱子",
                    "&7手持智能背包 / 冰柜右键：把背包物品卸进网络容器",
                    "&7空手或其它物品右键：打开 9×6 配置页",
                    "&7模式：容器内叠加 / 黑名单 / 白名单",
                    "&7可切换是否匹配 NBT / 附魔 / 耐久等标签",
                    "&7默认 容器内叠加 + 匹配标签，配置按方块持久化",
                    "&7需要至少是一个交互终端的成员才能执行卸载",
                    LoreBuilder.RIGHT_CLICK_TO_OPEN);
        }
        return new SlimefunItemStack(LOAD_ID, Material.DROPPER,
                "&b智能存储背包加载终端",
                "&7放置后通过智能存储连接器连接箱子",
                "&7手持智能背包 / 冰柜右键：从网络容器加载物品进背包",
                "&7空手或其它物品右键：打开 9×6 配置页",
                "&7模式：背包内叠加 / 黑名单 / 白名单",
                "&7可切换是否匹配 NBT / 附魔 / 耐久等标签",
                "&7加载至满堆叠（默认，不占新槽）/ 加载所有",
                "&7默认 背包内叠加 + 匹配标签 + 加载至满堆叠",
                "&7需要至少是一个交互终端的成员才能执行加载",
                LoreBuilder.RIGHT_CLICK_TO_OPEN);
    }

    // ------------------------------------------------------------------
    // 配置（按方块持久化）
    // ------------------------------------------------------------------

    private record Config(StorageFilter.Mode mode, boolean tags, boolean fill,
                          List<ItemStack> filters) { }

    private List<StorageFilter.Mode> modes() {
        return kind == Kind.UNLOAD
                ? List.of(StorageFilter.Mode.CONTAINER_STACK,
                        StorageFilter.Mode.BLACKLIST, StorageFilter.Mode.WHITELIST)
                : List.of(StorageFilter.Mode.BACKPACK_STACK,
                        StorageFilter.Mode.BLACKLIST, StorageFilter.Mode.WHITELIST);
    }

    private StorageFilter.Mode defaultMode() {
        return kind == Kind.UNLOAD
                ? StorageFilter.Mode.CONTAINER_STACK : StorageFilter.Mode.BACKPACK_STACK;
    }

    private Config readConfig(Location location) {
        StorageFilter.Mode mode = defaultMode();
        boolean tags = true;
        boolean fill = true;
        List<ItemStack> filters = emptyFilters();
        if (location == null) {
            return new Config(mode, tags, fill, filters);
        }
        try {
            mode = StorageFilter.Mode.parse(
                    BlockStorage.getLocationInfo(location, MODE_KEY), defaultMode(), modes());
            tags = !"0".equals(BlockStorage.getLocationInfo(location, TAGS_KEY));
            fill = !"0".equals(BlockStorage.getLocationInfo(location, FILL_KEY));
            filters = decodeFilters(BlockStorage.getLocationInfo(location, FILTERS_KEY));
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("读取智能存储终端配置失败", ex);
        }
        return new Config(mode, tags, fill, filters);
    }

    private void writeConfig(Location location, Config config) {
        if (location == null) {
            return;
        }
        try {
            BlockStorage.addBlockInfo(location, MODE_KEY, config.mode().name());
            BlockStorage.addBlockInfo(location, TAGS_KEY, config.tags() ? "1" : "0");
            BlockStorage.addBlockInfo(location, FILL_KEY, config.fill() ? "1" : "0");
            BlockStorage.addBlockInfo(location, FILTERS_KEY, encodeFilters(config.filters()));
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("保存智能存储终端配置失败", ex);
        }
    }

    private static List<ItemStack> emptyFilters() {
        return new ArrayList<>(Collections.nCopies(FILTER_SLOTS, null));
    }

    private static String encodeFilters(List<ItemStack> filters) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(FILTER_SLOTS);
                for (int i = 0; i < FILTER_SLOTS; i++) {
                    out.writeObject(i < filters.size() ? filters.get(i) : null);
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private static List<ItemStack> decodeFilters(String raw) {
        List<ItemStack> filters = emptyFilters();
        if (raw == null || raw.isEmpty()) {
            return filters;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            try (BukkitObjectInputStream in =
                         new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                int count = Math.min(FILTER_SLOTS, Math.max(0, in.readInt()));
                for (int i = 0; i < count; i++) {
                    Object value = in.readObject();
                    if (value instanceof ItemStack stack && !StorageFilter.isEmpty(stack)) {
                        ItemStack template = stack.clone();
                        template.setAmount(1);
                        filters.set(i, template);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError ignored) {
            // 存档损坏时退回空过滤表，不能让界面打不开
        }
        return filters;
    }

    // ------------------------------------------------------------------
    // 右键：空手 / 非背包物品 -> 配置页
    // ------------------------------------------------------------------

    private void onRightClick(PlayerRightClickEvent event) {
        Block block = event.getClickedBlock().orElse(null);
        if (block == null || BlockStorage.check(block) != this
                || SmartStorageInterface.isSneakPlacing(event)) {
            return;
        }
        event.cancel();
        event.getInteractEvent().setUseItemInHand(Event.Result.DENY);
        event.getInteractEvent().setUseInteractedBlock(Event.Result.DENY);
        open(event.getPlayer(), block);
    }

    private void open(Player player, Block block) {
        Location location = block.getLocation();
        Config config = readConfig(location);
        try {
            for (Location found : StorageNetwork.discoverInterfaceBlocks(
                    location, connectorItem, interfaceItem)) {
                StorageAccess.register(found);
            }
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("登记智能存储交互终端坐标失败", ex);
        }
        boolean editable = StorageAccess.isAdmin(player.getUniqueId());

        Session session = new Session();
        session.blockLocation = location;
        session.mode = config.mode();
        session.tags = config.tags();
        session.fill = config.fill();
        session.filters = config.filters();
        session.editable = editable;
        session.inventory = Bukkit.createInventory(null, GUI_SIZE, title());
        render(session);

        try {
            // 先开再登记：openInventory 会同步触发一次 InventoryCloseEvent
            player.openInventory(session.inventory);
            views.put(player.getUniqueId(), session);
            if (!editable) {
                player.sendMessage(READ_ONLY);
            }
        } catch (RuntimeException | LinkageError ex) {
            views.remove(player.getUniqueId(), session);
            resourceLib.warn("打开智能存储终端配置页失败", ex);
        }
    }

    private String title() {
        return kind == Kind.UNLOAD ? TITLE_UNLOAD : TITLE_LOAD;
    }

    private boolean isOurTitle(String value) {
        if (value == null) {
            return false;
        }
        return value.contains(kind == Kind.UNLOAD
                ? "智能存储卸载终端" : "智能存储加载终端");
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private static final class Session {
        private Inventory inventory;
        private Location blockLocation;
        private StorageFilter.Mode mode;
        private boolean tags;
        private boolean fill;
        private List<ItemStack> filters = emptyFilters();
        private boolean editable;
    }

    private void render(Session session) {
        Inventory inventory = session.inventory;
        inventory.clear();
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, glass());
        }
        inventory.setItem(SLOT_MODE, modeItem(session));
        inventory.setItem(SLOT_TAGS, tagsItem(session));
        if (kind == Kind.LOAD) {
            inventory.setItem(SLOT_FILL, fillItem(session));
        }
        inventory.setItem(SLOT_ACCESS, accessItem(session.editable));
        for (int row = 1; row <= 5; row++) {
            inventory.setItem(row * 9, glass());
        }
        for (int i = 0; i < FILTER_SLOTS; i++) {
            ItemStack template = i < session.filters.size() ? session.filters.get(i) : null;
            if (template != null) {
                inventory.setItem(filterSlot(i), template.clone());
            }
        }
    }

    private static int filterSlot(int index) {
        return (1 + index / 8) * 9 + (1 + index % 8);
    }

    /** 40 格区域：第 2~6 行、第 2~9 列。返回 index，-1 表示不在区域内。 */
    private static int filterIndex(int rawSlot) {
        int row = rawSlot / 9;
        int col = rawSlot % 9;
        if (row < 1 || row > 5 || col < 1 || col > 8) {
            return -1;
        }
        return (row - 1) * 8 + (col - 1);
    }

    private ItemStack modeItem(Session session) {
        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7点击切换到下一种模式"));
        for (StorageFilter.Mode candidate : modes()) {
            lore.add(colorize((candidate == session.mode ? "&a» &f" : "&8  &7")
                    + candidate.display()));
        }
        lore.add(colorize(switch (session.mode) {
            case CONTAINER_STACK -> "&7只卸载网络容器里已经存在的物品种类";
            case BACKPACK_STACK -> "&7只加载背包里已经存在的物品种类";
            case BLACKLIST -> "&7过滤列表里的物品不参与搬运";
            case WHITELIST -> "&7只有过滤列表里的物品参与搬运";
        }));
        lore.add(colorize("&7配置保存在当前这个终端方块上"));
        return named(Material.COMPARATOR, "&6模式：" + session.mode.display(),
                lore.toArray(new String[0]));
    }

    private static ItemStack tagsItem(Session session) {
        return named(Material.NAME_TAG,
                session.tags ? "&a匹配标签：开启" : "&c匹配标签：关闭",
                colorize("&7开启：附魔 / 耐久 / 显示名 / NBT 全部一致才算同一种"),
                colorize("&7关闭：仅匹配物品 ID，忽略所有标签差异"),
                colorize("&e点击切换"));
    }

    private static ItemStack fillItem(Session session) {
        return named(Material.HOPPER,
                session.fill ? "&a加载至满堆叠" : "&6加载所有",
                colorize(session.fill
                        ? "&7只把背包已有堆叠补满，不新占用任何空槽"
                        : "&7加载所有匹配物品，允许使用背包的空槽"),
                colorize("&7加载一律按背包槽位正序匹配"),
                colorize("&e点击切换"));
    }

    private static ItemStack accessItem(boolean editable) {
        return editable
                ? named(Material.EMERALD, "&a权限：可修改",
                        colorize("&7你至少是一个交互终端的管理员"),
                        colorize("&7可以切换按钮、编辑下方 40 格过滤列表"))
                : named(Material.BARRIER, "&c权限：只读",
                        colorize("&7只有交互终端的管理员可以修改配置"),
                        colorize("&7你仍然可以查看当前配置"));
    }

    private static ItemStack glass() {
        return named(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    // ------------------------------------------------------------------
    // 点击事件
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = views.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!isOurTitle(event.getView().getTitle())) {
            views.remove(player.getUniqueId(), session);
            return;
        }

        int raw = event.getRawSlot();

        // 只读：连玩家背包的 shift 点击也要挡掉，否则物品会被搬进 40 格
        if (!session.editable) {
            event.setCancelled(true);
            if (raw >= 0 && raw < GUI_SIZE) {
                player.sendMessage(ADMIN_ONLY);
            }
            return;
        }

        if (raw < 0) {
            return;
        }
        if (raw >= GUI_SIZE) {
            // 玩家背包里的 shift 点击可能把物品搬进过滤区，统一在下一 tick 归一化
            scheduleNormalize(player, session);
            return;
        }

        if (filterIndex(raw) >= 0) {
            // 过滤区允许原版放 / 取，事后把数量归一为 1 并持久化
            scheduleNormalize(player, session);
            return;
        }

        event.setCancelled(true);
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        if (raw == SLOT_MODE) {
            session.mode = StorageFilter.Mode.next(session.mode, modes());
        } else if (raw == SLOT_TAGS) {
            session.tags = !session.tags;
        } else if (raw == SLOT_FILL && kind == Kind.LOAD) {
            session.fill = !session.fill;
        } else {
            return; // 黑玻璃 / 权限图标：只读
        }
        saveSession(session);
        render(session);
        scheduleRepaint(player, session);
    }

    /** 客户端在取消点击后会重发被点击槽位的旧内容，延后一 tick 整屏同步。 */
    private void scheduleRepaint(Player player, Session session) {
        UUID uuid = player.getUniqueId();
        try {
            resourceLib.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline() || views.get(uuid) != session) {
                    return;
                }
                render(session);
                player.updateInventory();
            }, 1L);
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("同步智能存储终端配置页失败", ex);
        }
    }

    /**
     * 过滤格是「模板」而不是真实物品：数量一律归一为 1，多出来的返还玩家，
     * 然后把 40 格快照写进 BlockStorage。延后一 tick 才能读到客户端放下的最终结果。
     */
    private void scheduleNormalize(Player player, Session session) {
        UUID uuid = player.getUniqueId();
        try {
            resourceLib.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                Inventory inventory = session.inventory;
                for (int i = 0; i < FILTER_SLOTS; i++) {
                    int slot = filterSlot(i);
                    ItemStack current = inventory.getItem(slot);
                    if (StorageFilter.isEmpty(current)) {
                        session.filters.set(i, null);
                        continue;
                    }
                    if (current.getAmount() > 1) {
                        ItemStack extra = current.clone();
                        extra.setAmount(current.getAmount() - 1);
                        giveBack(player, extra);
                    }
                    ItemStack template = current.clone();
                    template.setAmount(1);
                    inventory.setItem(slot, template);
                    session.filters.set(i, template);
                }
                saveSession(session);
                if (player.isOnline() && views.get(uuid) == session) {
                    player.updateInventory();
                }
            }, 1L);
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("归一化智能存储终端过滤列表失败", ex);
        }
    }

    private static void giveBack(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void saveSession(Session session) {
        writeConfig(session.blockLocation,
                new Config(session.mode, session.tags, session.fill, session.filters));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        views.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // 手持背包右键：执行卸载 / 加载
    // ------------------------------------------------------------------

    /**
     * 由 {@link SmartContainer#onHeldRightClick} 在 ItemUseHandler 里调用。
     * 事件已经被 cancel，交互终端的 BlockUseHandler 不会再触发。
     */
    public void execute(SmartContainer container, Player player,
                        ItemStack backpackItem, Block block) {
        UUID uuid = player.getUniqueId();
        Config config = readConfig(block.getLocation());
        Object token = container.busyLock(uuid);
        if (token == null) {
            return;
        }
        container.scheduleUnlock(uuid, token);

        Location location = block.getLocation();
        resourceLib.runAtBlock(block, ignored -> {
            // 权限判定放在区域线程里，先把同一连接器网络里的交互终端坐标补进全局表。
            // 老存档 / 刚重启时坐标表可能是空的，这一步能让「至少一个交互终端的成员」
            // 立刻生效，不必先手动去右键打开一次交互终端。
            try {
                for (Location found : StorageNetwork.discoverInterfaceBlocks(
                        location, connectorItem, interfaceItem)) {
                    StorageAccess.register(found);
                }
            } catch (RuntimeException | LinkageError ex) {
                resourceLib.warn("登记智能存储交互终端坐标失败", ex);
            }
            if (!StorageAccess.isMember(uuid)) {
                container.busyUnlock(uuid, token);
                resourceLib.runAtEntity(player, () -> player.sendMessage(NO_MEMBER));
                return;
            }

            StorageNetwork.Network network;
            try {
                network = StorageNetwork.discover(location, connectorItem, interfaceItem);
            } catch (RuntimeException | LinkageError ex) {
                resourceLib.warn("发现智能存储终端网络失败", ex);
                container.busyUnlock(uuid, token);
                return;
            }
            List<Inventory> inventories = new ArrayList<>(network.containers.size());
            for (StorageNetwork.ContainerRef ref : network.containers) {
                inventories.add(ref.inventory);
            }
            if (inventories.isEmpty()) {
                container.busyUnlock(uuid, token);
                resourceLib.runAtEntity(player, () -> player.sendMessage(NO_CONTAINER));
                return;
            }

            container.readBackpack(backpackItem,
                    backpack -> resourceLib.runAtBlock(block, task -> {
                        try {
                            runTransfer(container, player, backpackItem, backpack, inventories, config);
                        } finally {
                            container.busyUnlock(uuid, token);
                        }
                    }),
                    () -> {
                        container.busyUnlock(uuid, token);
                        resourceLib.runAtEntity(player, () -> player.sendMessage(NOT_BOUND));
                    });
        });
    }

    /** 一定在终端方块所属区域线程执行：读写容器、读写背包、保存背包、发消息。 */
    private void runTransfer(SmartContainer container, Player player, ItemStack backpackItem,
                             PlayerBackpack backpack, List<Inventory> inventories, Config config) {
        Inventory target = backpack.getInventory();
        if (target == null) {
            resourceLib.runAtEntity(player, () -> player.sendMessage(NOT_BOUND));
            return;
        }
        Predicate<ItemStack> allowed = container::isAllowedItem;
        StorageTransfer.Result result;
        try {
            result = kind == Kind.UNLOAD
                    ? StorageTransfer.unload(inventories, target,
                            unloadPermit(inventories, config), config.tags(), allowed)
                    : StorageTransfer.load(inventories, target,
                            loadPermit(target, config), config.tags(), config.fill(),
                            allowed, container::getStackLimit);
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("智能存储终端搬运失败", ex);
            resourceLib.runAtEntity(player, () ->
                    player.sendMessage(colorize("&c搬运失败，容器与背包内容已尽量保持不变。")));
            return;
        }

        container.saveBackpack(backpack, backpackItem);
        String verb = kind == Kind.UNLOAD ? "卸载" : "加载";
        List<String> lines = new ArrayList<>();
        if (result.moved() == 0) {
            lines.add(colorize("&e没有符合当前配置的物品，未执行" + verb + "。"));
        } else {
            StringBuilder head = new StringBuilder("&a").append(verb).append("完成：共 &f")
                    .append(result.moved()).append(" &a个物品、&f").append(result.kinds())
                    .append(" &a种");
            if (result.outOfSpace()) {
                head.append(" &7| &e存储网络已满，已提前停止");
            }
            lines.add(colorize(head.toString()));
            List<StorageTransfer.Result.Entry> entries = result.entries();
            int limit = Math.min(entries.size(), 20);
            for (int i = 0; i < limit; i++) {
                StorageTransfer.Result.Entry entry = entries.get(i);
                lines.add(colorize("&7· &f" + entry.name() + " &7× &f" + entry.amount()));
            }
            if (entries.size() > limit) {
                lines.add(colorize("&7… 还有 " + (entries.size() - limit) + " 种未列出"));
            }
        }
        List<String> finalLines = lines;
        resourceLib.runAtEntity(player, () -> {
            for (String line : finalLines) {
                player.sendMessage(line);
            }
        });
    }

    private Predicate<ItemStack> unloadPermit(List<Inventory> inventories, Config config) {
        return switch (config.mode()) {
            case CONTAINER_STACK -> {
                List<ItemStack> white = StorageFilter.templatesOf(inventories, config.tags());
                yield stack -> StorageFilter.anyMatch(white, stack, config.tags());
            }
            case BLACKLIST -> stack -> !StorageFilter.anyMatch(config.filters(), stack, config.tags());
            case WHITELIST -> stack -> StorageFilter.anyMatch(config.filters(), stack, config.tags());
            default -> stack -> false;
        };
    }

    private Predicate<ItemStack> loadPermit(Inventory backpack, Config config) {
        return switch (config.mode()) {
            case BACKPACK_STACK -> {
                List<ItemStack> white = StorageFilter.distinct(
                        Arrays.asList(backpack.getContents()), config.tags());
                yield stack -> StorageFilter.anyMatch(white, stack, config.tags());
            }
            case BLACKLIST -> stack -> !StorageFilter.anyMatch(config.filters(), stack, config.tags());
            case WHITELIST -> stack -> StorageFilter.anyMatch(config.filters(), stack, config.tags());
            default -> stack -> false;
        };
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static ItemStack named(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(name));
            if (lore != null && lore.length > 0) {
                List<String> lines = new ArrayList<>();
                for (String line : lore) {
                    if (line != null) {
                        lines.add(line);
                    }
                }
                meta.setLore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
