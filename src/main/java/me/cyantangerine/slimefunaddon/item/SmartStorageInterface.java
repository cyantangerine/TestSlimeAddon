package me.cyantangerine.slimefunaddon.item;

import com.tcoded.folialib.wrapper.task.WrappedTask;
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
import me.cyantangerine.slimefunaddon.model.StorageNetwork;
import me.cyantangerine.slimefunaddon.model.StorageOrganizer;
import me.cyantangerine.slimefunaddon.model.StorageSnapshot;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能存储交互终端（发射器物品，放置后生效）。
 *
 * <p><b>只认右键</b>：{@link BlockUseHandler} 里 {@code cancel()} 掉原版开箱 /
 * 手持使用，同时拦掉 {@link BlockDispenseEvent}（红石）、{@link InventoryMoveItemEvent}
 * （漏斗）以及 {@code CargoOperationEvent}（Slimefun 货运）。本物品不是货运节点
 * （{@code CargoNet#classifyLocation} 只认 5 个 CARGO_* ID），但发射器材质在
 * {@code CARGO_SUPPORTED_STORAGE_BLOCKS} 标签里，货运的插入 / 取出节点会把它当成
 * 普通容器直接写 Inventory —— 所以必须显式拦货运事件，单靠漏斗事件是不够的。</p>
 *
 * <p>与 Slimefun 自身的约定一致：<b>潜行 + 手持物品时不接管右键</b>，让原版把
 * 手里的方块正常放到接口 / 连接器上。</p>
 *
 * <p><b>界面</b>：9×6 容器页。第一行 + 第一列是菜单：第 0 格「显示标记和位置」
 * 开关（默认关闭）、第 1 格排序按钮、第 2 格容量监控（只读容量展示）、
 * 第 3 格权限管理、第 4~6 格三种整理按钮、第 7、8 格翻页，其余黑色玻璃板。
 * 展示区是 5×8 = 40 格：默认（关闭标记）时 40 格全放物品，一页 40 种，
 * 物品数量 = 容器内真实总数、标题带「x真实总数」；开启标记时按
 * 「1 格物品(数量 1、保留物品本身名称) + 1 格纸标记(数量 = 组数，最多 64)」成对展示，
 * 一页 20 种；组数超过 64 时纸只叠 64 张，真实组数写在标记 lore 里。</p>
 *
 * <p><b>整理</b>：三种操作见 {@link StorageOrganizer} —— 每个容器内整理、
 * 跨容器整理（只补已有非满堆叠、绝不新增槽位）、压缩整理（允许用空槽把近容器填满）。</p>
 *
 * <p>物品格与标记格<b>行为完全一致</b>：左击取出一组、右击取出一个、
 * 光标持有物品时点击即存入；顶部所有格子都拒绝原版交互。</p>
 *
 * <p><b>三个页面</b>：主界面、权限管理（第一行左返回 / 右添加玩家，下面 45 个成员头颅，
 * 左击切换角色、右击删除）、添加玩家（列出在线玩家头颅）。三个页面各有独立标题，
 * 切换时下一 tick 在实体线程换 Inventory，{@code switching} 标志挡掉旧界面的关闭事件。
 * 显示标记与排序模式都按接口方块持久化在 BlockStorage。</p>
 *
 * <p><b>权限</b>：每个接口方块在 BlockStorage 里保存一份 ACL（UUID / 名字 / 角色）。
 * 第一个右键打开且 ACL 为空的玩家自动成为管理员；之后不在列表里的玩家右键只收到
 * 拒绝消息。管理员可以在权限页增删成员、切换角色，但不能删除自己或切换自己的角色。</p>
 *
 * <p><b>外部改动</b>：界面打开期间漏斗 / 货运 / 其它玩家都可能改动连接的箱子。
 * 缓存的 {@link Inventory} 指向真实方块实体，所以存取永远读的是实时内容；
 * 界面每 20 tick 用库存指纹比对一次、有变化才重绘，每 100 tick 重新发现一次网络。
 * 默认<b>不冻结</b>外部搬运；想要「打开期间库存严格不变」可在 config.yml 打开
 * {@code options.smart-storage-lock-external-transfers}。</p>
 *
 * <p><b>客户端同步</b>：服务端取消一次点击后会单独重发「被点击槽位」的旧内容，
 * 如果只在处理事件时 clear()+重绘，刚取空的那一对会留下纸标记（物品消失、标记还在）。
 * 所以每次点击结束后再排一个 1 tick 后的强制重绘 + {@code updateInventory()}。</p>
 */
public class SmartStorageInterface extends SlimefunItem implements Listener {

    public static final String ID = "SMART_STORAGE_INTERFACE";

    private static final int GUI_SIZE = 54;
    private static final int PAIRS_PER_PAGE = 20;
    private static final int COMPACT_PER_PAGE = 40;
    /** 权限页 / 玩家选择页：第 2 行起 5 行 × 9 列 = 45 个头颅。 */
    private static final int HEADS_PER_PAGE = 45;
    private static final int SLOT_TOGGLE = 0;
    private static final int SLOT_SORT = 1;
    private static final int SLOT_CAPACITY = 2;
    private static final int SLOT_PERMISSION = 3;
    /** 权限管理右侧的三个整理按钮。 */
    private static final int SLOT_COMPRESS = 4;
    private static final int SLOT_ORGANIZE_CROSS = 5;
    private static final int SLOT_ORGANIZE_LOCAL = 6;
    private static final int SLOT_PREV = 7;
    private static final int SLOT_NEXT = 8;
    /** 子页面第一行的菜单槽位。 */
    private static final int SLOT_BACK = 0;
    private static final int SLOT_MENU_PREV = 6;
    private static final int SLOT_MENU_NEXT = 7;
    private static final int SLOT_ADD_PLAYER = 8;

    /** BlockStorage 键：显示标记、排序模式、权限列表，全部按接口方块单独持久化。 */
    private static final String LABEL_KEY = "smart_storage_show_labels";
    private static final String SORT_KEY = "smart_storage_sort";
    private static final String ACL_KEY = "smart_storage_acl";

    /** 界面实时刷新周期（tick），以及每隔几轮做一次完整的网络重发现。 */
    private static final long REFRESH_PERIOD_TICKS = 20L;
    private static final int FULL_REDISCOVER_EVERY = 5;

    private static final String GUI_TITLE = colorize("&8智能存储交互终端");
    private static final String PERMISSION_TITLE = colorize("&8智能存储交互终端 · 权限管理");
    private static final String ADD_PLAYER_TITLE = colorize("&8智能存储交互终端 · 添加玩家");

    private static final String STORAGE_FULL = colorize(
            "&c存储已满：连接的箱子里没有空位，且对应物品已达到堆叠上限，无法存入。");
    private static final String CURSOR_OCCUPIED = colorize(
            "&e光标上有其它物品，取出的物品已放入背包。");
    private static final String STORED_PARTIAL = colorize(
            "&e存储空间不足，仅存入 &f%d &e个，剩余已退回。");
    private static final String NO_CONNECTOR = colorize("&7尚未通过存储连接器连接任何容器。");
    private static final String NO_PERMISSION = colorize(
            "&c你没有权限使用这个智能存储交互终端，请联系管理员添加。");
    private static final String BECOME_ADMIN = colorize(
            "&a你是第一个打开该接口的玩家，已自动成为管理员。");
    private static final String ADMIN_ONLY = colorize("&c只有管理员可以修改权限。");
    private static final String CANNOT_SELF = colorize("&c管理员无法删除自己或切换自己的权限。");
    private static final String ALREADY_MEMBER = colorize("&e该玩家已经在权限列表里了。");
    private static final String PLAYER_ADDED = colorize("&a已添加 &f%s &a为普通成员。");
    private static final String PLAYER_REMOVED = colorize("&e已移除玩家 &f%s&e。");
    private static final String ROLE_CHANGED = colorize("&a已将 &f%s &a的权限改为 &f%s&a。");

    private Plugin plugin;
    private ResourceLib resourceLib;
    private final SmartStorageConnector connectorItem;
    /** config.yml：打开界面时是否暂时冻结已连接容器的外部搬运，默认 false。 */
    private boolean lockExternalTransfers;

    /** 玩家 UUID -> 打开的界面会话。 */
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    /** 防止一次异步发现还没回来就重复开界面。 */
    private final Map<UUID, Object> opening = new ConcurrentHashMap<>();
    /** 每个玩家最多挂一个后台重发现任务，连点时不会堆一串区域任务。 */
    private final java.util.Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public SmartStorageInterface(ItemGroup itemGroup, SlimefunItemStack item,
                                 RecipeType recipeType, ItemStack[] recipe,
                                 SmartStorageConnector connectorItem) {
        super(itemGroup, item, recipeType, recipe);
        this.connectorItem = connectorItem;
    }

    @Override
    public void preRegister() {
        super.preRegister();
        plugin = getAddon().getJavaPlugin();
        resourceLib = Utils.initialCheck(plugin);
        StorageAccess.init(plugin);
        // 缺失该键时 Bukkit 的 getBoolean 返回 false，老配置文件也能平滑升级
        lockExternalTransfers = resourceLib.getConfig()
                .getBoolean("options.smart-storage-lock-external-transfers");
        addItemHandler((BlockUseHandler) this::onRightClick);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCargoGuard();
    }

    public static SlimefunItemStack createItemStack() {
        return new SlimefunItemStack(ID, Material.OBSERVER,
                "&b智能存储交互终端",
                "&7放置后右键打开容器交互界面",
                "&7需通过存储连接器连接到网络，可操作网络内所有容器",
                "&7左击物品 / 标记取出一组，右击取出一个",
                "&7Shift+左击取一组到背包，Shift+右击取一个到背包",
                "&7光标持有物品时点击界面可存入",
                "&7提供容器内整理/跨容器整理/压缩整理/权限管理",
                "&7不响应红石、漏斗与 Slimefun 货运系统",
                LoreBuilder.RIGHT_CLICK_TO_OPEN);
    }

    // ------------------------------------------------------------------
    // 右键开界面 + 屏蔽红石 / 漏斗 / 货运 + 权限校验
    // ------------------------------------------------------------------

    /**
     * 潜行 + 手持物品时不接管：Slimefun 自己的 SlimefunItemInteractListener 也用
     * 同一个约定（isSneaking && item != AIR 就跳过方块交互），此时服务端会走
     * 正常的「放置方块」流程。我们一旦 cancel + DENY useItemInHand，放置就没了。
     */
    public static boolean isSneakPlacing(PlayerRightClickEvent event) {
        if (!event.getPlayer().isSneaking()) {
            return false;
        }
        ItemStack held = event.getItem();
        return held != null && !held.getType().isAir();
    }

    private void onRightClick(PlayerRightClickEvent event) {
        Block block = event.getClickedBlock().orElse(null);
        if (block == null || BlockStorage.check(block) != this || isSneakPlacing(event)) {
            return;
        }
        // 无论是否放行，都必须掐掉原版发射器的 3×3 界面
        event.cancel();
        PlayerInteractEvent interact = event.getInteractEvent();
        interact.setUseItemInHand(Event.Result.DENY);
        interact.setUseInteractedBlock(Event.Result.DENY);

        openFrom(event.getPlayer(), block);
    }

    /**
     * 打开存储界面的统一入口：方块右键与无线终端远程打开都走这里，
     * 所以两者的 ACL 校验、首个打开者自动成为管理员的行为完全一致。
     */
    public void openFrom(Player player, Block block) {
        if (player == null || block == null || BlockStorage.check(block) != this) {
            return;
        }
        Location location = block.getLocation();
        StorageAccess.register(location);
        Acl acl = readAcl(location);
        if (acl.role(player.getUniqueId()) == Role.NONE) {
            if (acl.isEmpty() || !acl.hasAdmin()) {
                // 默认第一个打开的玩家自动拥有管理员权限
                writeAcl(location, acl.with(player.getUniqueId(), player.getName(), true));
                player.sendMessage(BECOME_ADMIN);
            } else {
                player.sendMessage(NO_PERMISSION);
                return;
            }
        }
        open(player, block);
    }

    /**
     * 全局权限判定用：读取某个位置上的交互终端 ACL，返回玩家角色。
     * 0 = 无权限，1 = 普通成员，2 = 管理员，-1 = 该位置已经不是交互终端
     * （调用方会据此把失效坐标从登记表里剔除）。
     */
    public static int roleAt(Location location, UUID uuid) {
        if (location == null || uuid == null) {
            return -1;
        }
        try {
            if (!(BlockStorage.check(location) instanceof SmartStorageInterface)) {
                return -1;
            }
            Acl acl = Acl.parse(BlockStorage.getLocationInfo(location, ACL_KEY));
            return switch (acl.role(uuid)) {
                case NONE -> 0;
                case MEMBER -> 1;
                case ADMIN -> 2;
            };
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    /** 发射器收到红石脉冲会尝试发射物品，这里直接掐掉。 */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (BlockStorage.check(event.getBlock()) == this) {
            event.setCancelled(true);
        }
    }

    /** 红石线给本方块供电时不受影响：本方块永远不会因红石做任何事。 */
    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (BlockStorage.check(event.getBlock()) == this) {
            event.setNewCurrent(0);
        }
    }

    /** 漏斗搬运：接口方块本身一律拒绝；已连接容器按配置决定是否临时冻结。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent event) {
        if (isOurInventory(event.getSource()) || isOurInventory(event.getDestination())) {
            event.setCancelled(true);
            return;
        }
        if (lockExternalTransfers
                && (isConnectedContainer(event.getSource())
                || isConnectedContainer(event.getDestination()))) {
            event.setCancelled(true);
        }
    }

    private boolean isOurInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        try {
            Location location = inventory.getLocation();
            return location != null && BlockStorage.check(location) == this;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 这个 Inventory 是否属于「当前有玩家打开着的某个界面」所连接的容器。 */
    private boolean isConnectedContainer(Inventory inventory) {
        String key = StorageNetwork.inventoryKey(inventory);
        if (key.isEmpty() || views.isEmpty()) {
            return false;
        }
        for (View view : views.values()) {
            for (StorageNetwork.ContainerRef container : view.network.containers) {
                if (container.key.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 货运事件放在独立内部类里注册：只有真正注册时才会去加载 slimefuncomplib 的
     * {@code CargoOperationEvent}。换回不带该事件的 Slimefun 版本时只是少了货运拦截，
     * 不会让整个物品因 NoClassDefFoundError 加载失败。
     */
    private void registerCargoGuard() {
        try {
            Bukkit.getPluginManager().registerEvents(new CargoGuard(), plugin);
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("[CyanTestAddon] 当前 Slimefun 版本没有货运操作事件，"
                    + "已跳过货运拦截；红石与漏斗拦截仍然生效。");
        }
    }

    public final class CargoGuard implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onCargo(com.xzavier0722.mc.plugin.slimefuncomplib.event.cargo.CargoOperationEvent event) {
            // 接口方块（发射器）永远不接受货运插入 / 取出，与配置无关
            Block target = event.getTarget();
            if (target != null && BlockStorage.check(target) == SmartStorageInterface.this) {
                event.setCancelled(true);
                return;
            }
            // 可选：打开界面时冻结已连接容器
            if (lockExternalTransfers && isConnectedContainer(event.getInv())) {
                event.setCancelled(true);
            }
        }
    }

    // ------------------------------------------------------------------
    // 权限数据（按接口方块存进 BlockStorage）
    // ------------------------------------------------------------------

    private enum Role { NONE, MEMBER, ADMIN }

    private enum Screen { MAIN, PERMISSIONS, ADD_PLAYER }

    /** 菜单里三个整理按钮，label 只用于完成提示。 */
    private enum OrganizeMode {
        COMPRESS("压缩整理"),
        CROSS("整理物品（跨容器）"),
        LOCAL("整理物品（每个容器内）");

        private final String label;

        OrganizeMode(String label) {
            this.label = label;
        }
    }

    /** 一条权限记录；name 只用于离线时显示，角色由 admin 布尔值表示。 */
    private record Member(UUID uuid, String name, boolean admin) { }

    /**
     * 序列化格式：{@code uuid|1|name;uuid|0|name;...}（分号分隔条目，竖线分隔字段）。
     * 玩家名只能是 [A-Za-z0-9_]，不会撞分隔符；解析时逐条容错，坏数据不会毁掉整份 ACL。
     */
    private static final class Acl {
        private final LinkedHashMap<UUID, Member> members;

        private Acl(LinkedHashMap<UUID, Member> members) {
            this.members = members;
        }

        private static Acl empty() {
            return new Acl(new LinkedHashMap<>());
        }

        private static Acl parse(String raw) {
            LinkedHashMap<UUID, Member> map = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return new Acl(map);
            }
            for (String part : raw.split(";")) {
                if (part.isEmpty()) {
                    continue;
                }
                String[] fields = part.split("\\|", 3);
                if (fields.length < 2) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(fields[0]);
                    String name = fields.length > 2 && !fields[2].isEmpty()
                            ? fields[2] : uuid.toString().substring(0, 8);
                    map.put(uuid, new Member(uuid, name, "1".equals(fields[1])));
                } catch (IllegalArgumentException ignored) {
                    // 跳过损坏的条目
                }
            }
            return new Acl(map);
        }

        private String serialize() {
            StringBuilder builder = new StringBuilder();
            for (Member member : members.values()) {
                builder.append(member.uuid()).append('|')
                        .append(member.admin() ? '1' : '0').append('|')
                        .append(member.name()).append(';');
            }
            return builder.toString();
        }

        private boolean isEmpty() {
            return members.isEmpty();
        }

        private boolean hasAdmin() {
            for (Member member : members.values()) {
                if (member.admin()) {
                    return true;
                }
            }
            return false;
        }

        private Member get(UUID uuid) {
            return members.get(uuid);
        }

        private Role role(UUID uuid) {
            Member member = members.get(uuid);
            if (member == null) {
                return Role.NONE;
            }
            return member.admin() ? Role.ADMIN : Role.MEMBER;
        }

        private List<Member> list() {
            return new ArrayList<>(members.values());
        }

        private Acl with(UUID uuid, String name, boolean admin) {
            LinkedHashMap<UUID, Member> copy = new LinkedHashMap<>(members);
            copy.put(uuid, new Member(uuid, name, admin));
            return new Acl(copy);
        }

        private Acl without(UUID uuid) {
            LinkedHashMap<UUID, Member> copy = new LinkedHashMap<>(members);
            copy.remove(uuid);
            return new Acl(copy);
        }

        private Acl withRole(UUID uuid, boolean admin) {
            Member member = members.get(uuid);
            if (member == null) {
                return this;
            }
            LinkedHashMap<UUID, Member> copy = new LinkedHashMap<>(members);
            copy.put(uuid, new Member(member.uuid(), member.name(), admin));
            return new Acl(copy);
        }
    }

    private Acl readAcl(Location location) {
        if (location == null) {
            return Acl.empty();
        }
        try {
            return Acl.parse(BlockStorage.getLocationInfo(location, ACL_KEY));
        } catch (RuntimeException | LinkageError ex) {
            return Acl.empty();
        }
    }

    private void writeAcl(Location location, Acl acl) {
        if (location == null) {
            return;
        }
        try {
            BlockStorage.addBlockInfo(location, ACL_KEY, acl.serialize());
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("保存智能存储权限列表失败", ex);
        }
    }

    /** 移除成员后，把仍在看着这个接口、但已经没权限的玩家当场踢出界面。 */
    private void enforceAcl(Location location, Acl acl) {
        if (location == null || views.isEmpty()) {
            return;
        }
        StorageNetwork.BlockKey origin = StorageNetwork.BlockKey.of(location);
        for (Map.Entry<UUID, View> entry : views.entrySet()) {
            UUID uuid = entry.getKey();
            View view = entry.getValue();
            if (view.blockLocation == null
                    || !origin.equals(StorageNetwork.BlockKey.of(view.blockLocation))
                    || acl.role(uuid) != Role.NONE) {
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                resourceLib.runAtEntity(player, () -> {
                    views.remove(uuid, view);
                    view.ready = false;
                    stopRefresh(view);
                    player.closeInventory();
                    player.sendMessage(NO_PERMISSION);
                });
            }
        }
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    private static final class View {
        private Inventory inventory;
        private Screen screen = Screen.MAIN;
        private Location blockLocation;
        private volatile StorageNetwork.Network network;
        private volatile StorageSnapshot snapshot;
        /** 当前排序模式下的展示列表，render 时刷新。 */
        private List<StorageSnapshot.Group> groups = List.of();
        private int page;
        private int menuPage;
        private boolean showLabels = false;
        private StorageSnapshot.SortMode sortMode = StorageSnapshot.SortMode.DISTANCE_ASC;
        private volatile boolean ready;
        /** 页面切换期间抑制关闭事件与点击处理，避免会话被旧界面的关闭事件误清。 */
        private volatile boolean switching;
        /**
         * Folia 上旧界面的 InventoryCloseEvent 有可能延迟到下一 tick 才派发，
         * 这段窗口内只认「关掉的是当前 inventory」的关闭事件，其余一律视为切换残留。
         */
        private volatile long closeGuardUntil;
        /** 展示格（物品格 / 标记格）-> groups 下标。两种模式共用。 */
        private final Map<Integer, Integer> entrySlots = new HashMap<>();
        /** 头颅格 -> 玩家 UUID。权限页与玩家选择页共用。 */
        private final Map<Integer, UUID> headSlots = new HashMap<>();
        /** 每 20 tick 的实时刷新任务；关界面 / 退出时取消。 */
        private WrappedTask refreshTask;
        private int refreshTicks;
    }

    /** 默认不显示标记和位置；只有显式开启（存 "1"）才显示。 */
    private boolean readShowLabels(Location location) {
        if (location == null) {
            return false;
        }
        try {
            return "1".equals(BlockStorage.getLocationInfo(location, LABEL_KEY));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void writeShowLabels(Location location, boolean value) {
        if (location == null) {
            return;
        }
        try {
            BlockStorage.addBlockInfo(location, LABEL_KEY, value ? "1" : "0");
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("保存智能存储显示设置失败", ex);
        }
    }

    private StorageSnapshot.SortMode readSort(Location location) {
        if (location == null) {
            return StorageSnapshot.SortMode.DISTANCE_ASC;
        }
        try {
            String raw = BlockStorage.getLocationInfo(location, SORT_KEY);
            if (raw == null || raw.isEmpty()) {
                return StorageSnapshot.SortMode.DISTANCE_ASC;
            }
            return StorageSnapshot.SortMode.valueOf(raw);
        } catch (RuntimeException | LinkageError ignored) {
            // valueOf 对未知名字抛 IllegalArgumentException，属于 RuntimeException
            return StorageSnapshot.SortMode.DISTANCE_ASC;
        }
    }

    private void writeSort(Location location, StorageSnapshot.SortMode mode) {
        if (location == null) {
            return;
        }
        try {
            BlockStorage.addBlockInfo(location, SORT_KEY, mode.name());
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("保存智能存储排序设置失败", ex);
        }
    }

    // ------------------------------------------------------------------
    // 手持智能背包 / 冰柜右键本终端：把整个网络的容器批量装载进背包
    // ------------------------------------------------------------------

    /**
     * 需求 1：SmartCooler / SmartBackpack 装载时，如果右键的是交互终端，
     * 就扫描终端连接的所有容器，把「该背包允许装载」的物品尽量装进背包。
     *
     * <p>由 {@link SmartContainer#onHeldRightClick} 在 ItemUseHandler 里调用。
     * 那时事件已经被 cancel，本终端的 BlockUseHandler 不会再打开 GUI；
     * 但 GUI 的 ACL 校验必须在这里补上，否则手持背包就能绕过权限读走网络里的东西。</p>
     */
    public void loadNetworkIntoBackpack(SmartContainer container, Player player,
                                        ItemStack backpackItem, Block block) {
        if (container == null || player == null || block == null
                || BlockStorage.check(block) != this) {
            return;
        }
        Location location = block.getLocation();
        StorageAccess.register(location);

        Acl acl = readAcl(location);
        UUID uuid = player.getUniqueId();
        if (acl.role(uuid) == Role.NONE) {
            if (acl.isEmpty() || !acl.hasAdmin()) {
                writeAcl(location, acl.with(uuid, player.getName(), true));
                player.sendMessage(BECOME_ADMIN);
            } else {
                player.sendMessage(NO_PERMISSION);
                return;
            }
        }

        Object token = container.busyLock(uuid);
        if (token == null) {
            return;
        }
        container.scheduleUnlock(uuid, token);

        resourceLib.runAtBlock(block, ignored -> {
            List<Source> sources = new ArrayList<>();
            try {
                StorageNetwork.Network network =
                        StorageNetwork.discover(location, connectorItem, this);
                for (StorageNetwork.ContainerRef ref : network.containers) {
                    List<SmartContainer.ContainerSlot> found = container.collectItems(ref.inventory);
                    if (!found.isEmpty()) {
                        sources.add(new Source(ref.inventory, found));
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                resourceLib.warn("扫描智能存储交互终端网络失败", ex);
                container.busyUnlock(uuid, token);
                return;
            }
            if (sources.isEmpty()) {
                container.busyUnlock(uuid, token);
                String empty = container.getLoadErrorMsg();
                resourceLib.runAtEntity(player, () -> player.sendMessage(empty));
                return;
            }

            List<Source> snapshot = List.copyOf(sources);
            container.readBackpack(backpackItem,
                    backpack -> resourceLib.runAtBlock(block, task -> {
                        try {
                            int total = 0;
                            Map<String, Integer> byName = new LinkedHashMap<>();
                            for (Source source : snapshot) {
                                SmartContainer.LoadResult part = container.transfer(
                                        source.inventory(), backpack, source.found());
                                total += part.total();
                                for (Map.Entry<String, Integer> entry : part.byName().entrySet()) {
                                    byName.merge(entry.getKey(), entry.getValue(), Integer::sum);
                                }
                            }
                            if (total <= 0) {
                                resourceLib.runAtEntity(player, () -> player.sendMessage(colorize(
                                        "&e背包已满或网络里没有可装载的物品，本次未装载任何东西。")));
                                return;
                            }
                            container.saveBackpack(backpack, backpackItem);
                            String message = container.loadMessage(
                                    new SmartContainer.LoadResult(total, byName));
                            resourceLib.runAtEntity(player, () -> player.sendMessage(message));
                        } finally {
                            container.busyUnlock(uuid, token);
                        }
                    }),
                    () -> {
                        container.busyUnlock(uuid, token);
                        resourceLib.runAtEntity(player, () -> player.sendMessage(colorize(
                                "&c背包尚未初始化，请先手持右键打开一次。")));
                    });
        });
    }

    /** 一个网络容器 + 它里面「该背包允许装载」的槽位快照。 */
    private record Source(Inventory inventory, List<SmartContainer.ContainerSlot> found) { }

    private void open(Player player, Block block) {
        UUID uuid = player.getUniqueId();
        Object token = new Object();
        if (opening.putIfAbsent(uuid, token) != null) {
            return;
        }
        View previous = views.remove(uuid);
        if (previous != null) {
            previous.ready = false;
            stopRefresh(previous);
        }

        Location location = block.getLocation();
        boolean showLabels = readShowLabels(location);
        StorageSnapshot.SortMode sortMode = readSort(location);
        // 发现网络要读世界方块，必须落在接口方块所属区域线程
        resourceLib.runAtBlock(block, ignored -> {
            StorageNetwork.Network network;
            try {
                network = StorageNetwork.discover(location, connectorItem, this);
            } catch (RuntimeException | LinkageError ex) {
                resourceLib.warn("发现智能存储网络失败", ex);
                opening.remove(uuid, token);
                return;
            }
            StorageNetwork.Network discovered = network;
            resourceLib.runAtEntity(player, () -> {
                if (!opening.remove(uuid, token)) {
                    return;
                }
                if (!player.isOnline()) {
                    return;
                }
                View view = new View();
                view.inventory = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);
                view.screen = Screen.MAIN;
                view.blockLocation = location;
                view.network = discovered;
                view.snapshot = StorageSnapshot.of(discovered);
                view.showLabels = showLabels;
                // 排序模式按接口方块持久化，不再每次打开重置
                view.sortMode = sortMode;
                view.page = 0;
                renderScreen(player, view);
                try {
                    // 先开再登记：openInventory 会先关掉玩家当前界面并同步触发
                    // InventoryCloseEvent，先登记的话新会话会被那次关闭事件误清。
                    player.openInventory(view.inventory);
                    views.put(uuid, view);
                    startRefresh(player, view);
                } catch (RuntimeException | LinkageError ex) {
                    views.remove(uuid, view);
                    view.ready = false;
                    stopRefresh(view);
                    resourceLib.warn("打开智能存储界面失败", ex);
                }
            });
        });
    }

    /**
     * 切换到另一个容器页面。Folia 的 {@code CraftHumanEntity#openInventory} 带
     * {@code TickThread.ensureTickThread} 断言，所以延后 1 tick 在实体线程上换页面；
     * 期间 {@code switching} 会挡掉旧界面关闭触发的 InventoryCloseEvent 和残留点击。
     */
    private void switchScreen(Player player, View view, Screen screen) {
        UUID uuid = player.getUniqueId();
        view.switching = true;
        view.ready = false;
        view.closeGuardUntil = System.currentTimeMillis() + 250L;
        try {
            resourceLib.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline() || views.get(uuid) != view) {
                    view.switching = false;
                    return;
                }
                try {
                    Inventory next = Bukkit.createInventory(null, GUI_SIZE, titleFor(screen));
                    view.inventory = next;
                    view.screen = screen;
                    view.menuPage = 0;
                    renderScreen(player, view);
                    // 关闭旧界面会同步触发 InventoryCloseEvent，switching 必须保持 true
                    player.openInventory(next);
                } catch (RuntimeException | LinkageError ex) {
                    view.ready = true;
                    resourceLib.warn("切换智能存储页面失败", ex);
                } finally {
                    view.switching = false;
                }
            }, 1L);
        } catch (RuntimeException | LinkageError ex) {
            view.switching = false;
            view.ready = true;
            resourceLib.warn("调度智能存储页面切换失败", ex);
        }
    }

    private static String titleFor(Screen screen) {
        return switch (screen) {
            case MAIN -> GUI_TITLE;
            case PERMISSIONS -> PERMISSION_TITLE;
            case ADD_PLAYER -> ADD_PLAYER_TITLE;
        };
    }

    /**
     * 用窗口标题识别我们的界面。不比较 Inventory 对象：Bukkit 不同版本会返回不同的
     * 包装实例。这个检查还能兜住「页面切换时的关闭事件被吞掉」留下的过期会话 ——
     * 玩家打开别的容器时，会话会立即作废，不会再去取消别人的点击。
     */
    private static boolean isOurTitle(String title) {
        // 用「包含」而不是精确相等：新版服务端把标题转成文本组件再取回时，
        // 颜色码可能被规范化，精确比较会误判成「不是我们的界面」。
        // 前缀必须足够独特：卸载 / 加载终端的配置页标题是「智能存储卸载终端 /
        // 智能存储加载终端」，不含本串，不会被这个监听器误当成自己的界面。
        return title != null && title.contains("智能存储交互终端");
    }

    /** 开界面后重新发现一次网络，捕捉期间放置 / 拆除的箱子。 */
    private void refreshNetwork(Player player, View view) {
        Location location = view.blockLocation;
        UUID uuid = player.getUniqueId();
        if (location == null || location.getWorld() == null || !refreshing.add(uuid)) {
            return;
        }
        resourceLib.runAtBlock(location.getBlock(), ignored -> {
            StorageNetwork.Network network;
            try {
                network = StorageNetwork.discover(location, connectorItem, this);
            } catch (RuntimeException | LinkageError ex) {
                resourceLib.warn("刷新智能存储网络失败", ex);
                refreshing.remove(uuid);
                return;
            }
            StorageNetwork.Network discovered = network;
            resourceLib.runAtEntity(player, () -> {
                refreshing.remove(uuid);
                if (views.get(uuid) != view || !player.isOnline()) {
                    return;
                }
                view.network = discovered;
                // 聚合必须回玩家线程做：后台算好的快照可能比玩家刚点的那次取/存还旧。
                view.snapshot = StorageSnapshot.of(discovered);
                renderCurrent(player, view);
            });
        });
    }

    /** 从缓存的 Inventory 重新聚合，玩家线程同步执行，不读世界。 */
    private void rescan(Player player, View view) {
        view.snapshot = StorageSnapshot.of(view.network);
        renderCurrent(player, view);
    }

    /**
     * 只在内容真的变了才重绘。漏斗 / 货运每 tick 都可能搬东西，
     * 无条件 clear()+setItem() 会让客户端每秒闪一次 54 格。
     * 停留在权限页时只更新数据、不重绘，避免每 20 tick 把头颅列表刷一遍。
     */
    private void rescanIfChanged(Player player, View view) {
        StorageSnapshot fresh = StorageSnapshot.of(view.network);
        if (!fresh.sameContents(view.snapshot)) {
            view.snapshot = fresh;
            renderCurrent(player, view);
        }
    }

    /** 后台刷新只影响主界面；子页面有自己的数据，切回来时会重新渲染。 */
    private void renderCurrent(Player player, View view) {
        if (view.screen == Screen.MAIN) {
            render(view);
            view.ready = true;
        }
    }

    /**
     * 点击结束后补一次强制重绘。服务端在取消点击之后会单独把「被点击槽位」
     * 的旧内容重发给客户端，只有事件内重绘时，刚被取空的那一对会出现
     * 「物品没了、纸标记还在」；延后一 tick 再整屏同步就能盖掉这个回滚包。
     * 子页面同理：改完 ACL 重新渲染头颅列表，顺带盖掉客户端的槽位回滚。
     */
    private void scheduleRepaint(Player player, View view) {
        UUID uuid = player.getUniqueId();
        try {
            resourceLib.getFoliaLib().getScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline() || views.get(uuid) != view
                        || !view.ready || view.switching) {
                    return;
                }
                view.snapshot = StorageSnapshot.of(view.network);
                renderScreen(player, view);
                player.updateInventory();
            }, 1L);
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("同步智能存储界面失败", ex);
        }
    }

    private void startRefresh(Player player, View view) {
        try {
            view.refreshTask = resourceLib.getFoliaLib().getScheduler()
                    .runAtEntityTimer(player, () -> refreshTick(player, view),
                            REFRESH_PERIOD_TICKS, REFRESH_PERIOD_TICKS);
        } catch (RuntimeException | LinkageError ex) {
            // 定时器起不来不影响使用：点击时仍会 rescanIfChanged / refreshNetwork
            resourceLib.warn("启动智能存储界面刷新任务失败", ex);
        }
    }

    private void refreshTick(Player player, View view) {
        UUID uuid = player.getUniqueId();
        if (views.get(uuid) != view || !player.isOnline()) {
            stopRefresh(view);
            return;
        }
        // 页面切换只是暂时停用刷新，不能把定时器取消掉
        if (!view.ready || view.switching) {
            return;
        }
        // 兜底：切换时的关闭事件若被延迟吞掉，这里也能在 1 秒内回收过期会话
        if (!isOurTitle(player.getOpenInventory().getTitle())) {
            views.remove(uuid, view);
            view.ready = false;
            stopRefresh(view);
            return;
        }
        rescanIfChanged(player, view);
        if (++view.refreshTicks % FULL_REDISCOVER_EVERY == 0) {
            refreshNetwork(player, view);
        }
    }

    private static void stopRefresh(View view) {
        WrappedTask task = view.refreshTask;
        view.refreshTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException | LinkageError ignored) {
                // 任务已结束时再取消，某些实现会抛异常，忽略即可
            }
        }
    }

    // ------------------------------------------------------------------
    // 网络拓扑变化（箱子 / 连接器被拆或新放）
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        // 无条件尝试移除：MONITOR 阶段 Slimefun 可能已经清掉 BlockStorage，
        // 这时再 check(block) == this 会漏登记，而 unregister 对未登记位置是空操作。
        StorageAccess.unregister(block.getLocation());
        onTopologyChanged(StorageNetwork.BlockKey.of(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (SlimefunItem.getByItem(event.getItemInHand()) == this) {
            StorageAccess.register(block.getLocation());
        }
        onTopologyChanged(StorageNetwork.BlockKey.of(block));
    }

    /**
     * 箱子被拆时，缓存的 Inventory 仍指向已被清空并脱离世界的方块实体，
     * 这时候再点「存入」会把物品写进虚空。这里当场把它从网络里剪掉，
     * 再排一次完整重发现；界面本体的重绘统一回玩家线程做。
     */
    private void onTopologyChanged(StorageNetwork.BlockKey key) {
        if (views.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, View> entry : views.entrySet()) {
            UUID uuid = entry.getKey();
            View view = entry.getValue();
            Location location = view.blockLocation;
            if (location == null || location.getWorld() == null) {
                continue;
            }
            StorageNetwork.BlockKey origin = StorageNetwork.BlockKey.of(location);

            // 接口方块自己被拆：直接关掉这个玩家的界面
            if (key.equals(origin)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    resourceLib.runAtEntity(player, () -> {
                        views.remove(uuid, view);
                        view.ready = false;
                        stopRefresh(view);
                        player.closeInventory();
                    });
                }
                continue;
            }

            StorageNetwork.Network trimmed = StorageNetwork.withoutContainer(view.network, key);
            boolean removed = trimmed != view.network;
            if (!removed && !nearNetwork(view, origin, key)) {
                continue;
            }
            if (removed) {
                view.network = trimmed;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                resourceLib.runAtEntity(player, () -> {
                    if (views.get(uuid) != view) {
                        return;
                    }
                    rescan(player, view);
                    refreshNetwork(player, view);
                });
            }
        }
    }

    /** 变化的方块是否挨着接口或网络里任一连接器（只有这些位置会影响拓扑）。 */
    private static boolean nearNetwork(View view, StorageNetwork.BlockKey origin,
                                       StorageNetwork.BlockKey key) {
        for (StorageNetwork.BlockKey neighbour : origin.neighbours()) {
            if (neighbour.equals(key)) {
                return true;
            }
        }
        for (StorageNetwork.ConnectorRef connector : view.network.connectors) {
            for (StorageNetwork.BlockKey neighbour : connector.block.neighbours()) {
                if (neighbour.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 渲染：按当前 Screen 分派
    // ------------------------------------------------------------------

    private void renderScreen(Player player, View view) {
        switch (view.screen) {
            case MAIN -> render(view);
            case PERMISSIONS -> renderPermissions(player, view);
            case ADD_PLAYER -> renderAddPlayer(player, view);
        }
        view.ready = true;
    }

    private void render(View view) {
        Inventory inventory = view.inventory;
        inventory.clear();
        view.entrySlots.clear();
        view.headSlots.clear();
        view.groups = view.snapshot.sorted(view.sortMode);

        boolean showLabels = view.showLabels;
        int perPage = showLabels ? PAIRS_PER_PAGE : COMPACT_PER_PAGE;
        int pages = Math.max(1, (view.groups.size() + perPage - 1) / perPage);
        view.page = Math.max(0, Math.min(view.page, pages - 1));

        // 第一列菜单：其余格子用黑色玻璃板填充
        for (int row = 1; row <= 5; row++) {
            inventory.setItem(row * 9, glass());
        }
        inventory.setItem(SLOT_TOGGLE, toggleItem(showLabels));
        inventory.setItem(SLOT_SORT, sortItem(view.sortMode));
        inventory.setItem(SLOT_CAPACITY, capacityItem(view.snapshot));
        inventory.setItem(SLOT_PERMISSION, permissionItem());
        inventory.setItem(SLOT_COMPRESS, organizeItem(OrganizeMode.COMPRESS));
        inventory.setItem(SLOT_ORGANIZE_CROSS, organizeItem(OrganizeMode.CROSS));
        inventory.setItem(SLOT_ORGANIZE_LOCAL, organizeItem(OrganizeMode.LOCAL));
        inventory.setItem(SLOT_PREV, pageItem(false, view.page + 1, pages, perPage));
        inventory.setItem(SLOT_NEXT, pageItem(true, view.page + 1, pages, perPage));

        if (view.snapshot.containers.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, "&c未连接任何容器",
                    colorize("&7请在相邻位置放置智能存储连接器")));
        }

        int start = view.page * perPage;
        int end = Math.min(start + perPage, view.groups.size());
        for (int offset = 0; offset < perPage && start + offset < end; offset++) {
            int groupIndex = start + offset;
            StorageSnapshot.Group group = view.groups.get(groupIndex);

            if (showLabels) {
                int row = 1 + offset / 4;
                int col = 1 + (offset % 4) * 2;
                int itemSlot = row * 9 + col;
                int labelSlot = itemSlot + 1;

                ItemStack icon = group.template.clone();
                icon.setAmount(1);
                // 显示标记时物品格直接用物品本身的名称，信息交给旁边的纸标记
                inventory.setItem(itemSlot, icon);
                inventory.setItem(labelSlot, labelItem(group, view.snapshot));
                // 物品格与标记格行为一致，都指向同一个组
                view.entrySlots.put(itemSlot, groupIndex);
                view.entrySlots.put(labelSlot, groupIndex);
            } else {
                int row = 1 + offset / 8;
                int col = 1 + offset % 8;
                int slot = row * 9 + col;
                ItemStack icon = group.template.clone();
                // 未显示标记时，物品数量 = 所有容器里的真实总数（可超过单格堆叠上限）
                icon.setAmount(Math.max(1, group.total));
                nameIcon(icon, group.total);
                inventory.setItem(slot, icon);
                view.entrySlots.put(slot, groupIndex);
            }
        }
    }

    /** 权限页：第一行返回 + 添加玩家 + 翻页，其余全是有权限玩家的头颅。 */
    private void renderPermissions(Player player, View view) {
        Inventory inventory = view.inventory;
        inventory.clear();
        view.entrySlots.clear();
        view.headSlots.clear();

        Acl acl = readAcl(view.blockLocation);
        boolean admin = acl.role(player.getUniqueId()) == Role.ADMIN;
        List<Member> members = acl.list();
        int pages = Math.max(1, (members.size() + HEADS_PER_PAGE - 1) / HEADS_PER_PAGE);
        view.menuPage = Math.max(0, Math.min(view.menuPage, pages - 1));

        fillMenuRow(inventory, view.menuPage + 1, pages, admin);
        inventory.setItem(SLOT_BACK, backItem("返回存储界面"));

        if (members.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, "&c还没有有权限的玩家",
                    colorize("&7点击右上角「添加玩家」从在线玩家中选择")));
        }

        int start = view.menuPage * HEADS_PER_PAGE;
        int end = Math.min(start + HEADS_PER_PAGE, members.size());
        for (int offset = 0; start + offset < end; offset++) {
            Member member = members.get(start + offset);
            int slot = 9 + offset;
            inventory.setItem(slot, memberHead(member, admin,
                    member.uuid().equals(player.getUniqueId())));
            view.headSlots.put(slot, member.uuid());
        }
    }

    /** 玩家选择页：第一行为菜单，第二行及以后列出所有在线玩家。 */
    private void renderAddPlayer(Player player, View view) {
        Inventory inventory = view.inventory;
        inventory.clear();
        view.entrySlots.clear();
        view.headSlots.clear();

        Acl acl = readAcl(view.blockLocation);
        boolean admin = acl.role(player.getUniqueId()) == Role.ADMIN;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName));

        int pages = Math.max(1, (online.size() + HEADS_PER_PAGE - 1) / HEADS_PER_PAGE);
        view.menuPage = Math.max(0, Math.min(view.menuPage, pages - 1));

        fillMenuRow(inventory, view.menuPage + 1, pages, admin);
        inventory.setItem(SLOT_BACK, backItem("返回权限管理"));
        inventory.setItem(SLOT_ADD_PLAYER, glass());

        if (online.isEmpty()) {
            inventory.setItem(22, named(Material.BARRIER, "&c当前没有在线玩家",
                    colorize("&7让玩家上线后再来添加")));
        }

        int start = view.menuPage * HEADS_PER_PAGE;
        int end = Math.min(start + HEADS_PER_PAGE, online.size());
        for (int offset = 0; start + offset < end; offset++) {
            Player target = online.get(start + offset);
            int slot = 9 + offset;
            Member existing = acl.get(target.getUniqueId());
            inventory.setItem(slot, onlineHead(target, existing, admin));
            view.headSlots.put(slot, target.getUniqueId());
        }
    }

    /** 子页面第一行：0=返回，6/7=翻页，8=添加玩家，其余黑玻璃。 */
    private static void fillMenuRow(Inventory inventory, int page, int pages, boolean admin) {
        for (int slot = 1; slot <= 5; slot++) {
            inventory.setItem(slot, glass());
        }
        inventory.setItem(SLOT_MENU_PREV, named(Material.ARROW, "« &a上一页",
                colorize("&7第 &f" + page + " &7/ &f" + pages + " &7页"),
                colorize("&7每页最多 " + HEADS_PER_PAGE + " 名玩家")));
        inventory.setItem(SLOT_MENU_NEXT, named(Material.ARROW, "&a下一页 »",
                colorize("&7第 &f" + page + " &7/ &f" + pages + " &7页"),
                colorize("&7每页最多 " + HEADS_PER_PAGE + " 名玩家")));
        inventory.setItem(SLOT_ADD_PLAYER, addPlayerItem(admin));
    }

    /**
     * 标记标题只放物品名；总数量与真实组数在 lore 第一行。
     * 纸的数量只是组数的可视化：客户端最多只能显示 64 张，所以这里封顶 64，
     * 但 lore 里的组数必须是不截断的真实值。
     */
    private static ItemStack labelItem(StorageSnapshot.Group group, StorageSnapshot snapshot) {
        int paperAmount = Math.max(1, Math.min(64, group.groups));
        ItemStack paper = new ItemStack(Material.PAPER, paperAmount);
        ItemMeta meta = paper.getItemMeta();
        if (meta == null) {
            return paper;
        }
        meta.setDisplayName(colorize("&6" + group.name));

        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7总数量 &f" + group.total + " &7/ 组数 &f" + group.groups));
//        if (group.groups > 64) {
//            // lore.add(colorize("&8纸标记最多叠 64 张，实际组数以上方数字为准"));
//        }
        for (int i = 0; i < snapshot.containers.size(); i++) {
            int count = group.perContainer[i];
            if (count <= 0) {
                continue;
            }
            StorageNetwork.ContainerRef container = snapshot.containers.get(i);
            lore.add(colorize("&7· &f" + container.label
                    + "&8 [距离 " + container.distance + "] &7" + ChatColor.WHITE + count + " &7个"));
        }
        lore.add(colorize("&eShift+左击取一组到背包，Shift+右击取一个到背包"));
        lore.add(colorize("&e左击取出一组，右击取出一个"));
        meta.setLore(lore);
        paper.setItemMeta(meta);
        return paper;
    }

    /**
     * 容量监控：只读展示「已用量」，用 SmartBackpack 那套「placeholder 纸」画进度条。
     *
     * <p>客户端对 BUNDLE 不渲染耐久条，所以不能靠 {@code Damageable#setDamage}；
     * 它只认 BUNDLE_CONTENTS 的权重。PAPER 的原版单格上限是 64：1.19.2 客户端
     * 固定按 64 权重算，1.20.5+ 按「数量 / max_stack_size」算（默认仍是 PAPER 的 64），
     * 两代都得到 {@code amount / 64 ≈ 已用槽 / 总槽}。全空时塞空列表画空条，全满时为 64。
     * 这样也不需要反射 {@code setMaxStackSize}：总槽数可能上千，超出该组件 1..99 的合法范围。</p>
     */
    private static ItemStack capacityItem(StorageSnapshot snapshot) {
        StorageSnapshot.Capacity capacity = snapshot.capacity;
        int percent = capacity.usedPercent();
        String title = "&6容量监控 &7| 已用 &f" + percent + "% "
                + "&7| 已用槽 &f" + capacity.usedSlots + "&7/&f" + capacity.totalSlots
                + " &7| 容器 &f" + capacity.containerCount;

        List<String> lore = new ArrayList<>();
        lore.add(progressBar(percent));
//        lore.add(colorize("&7仅用于展示，点击无任何操作"));
        for (int i = 0; i < snapshot.containers.size(); i++) {
            StorageNetwork.ContainerRef container = snapshot.containers.get(i);
            lore.add(colorize("&7· &f" + container.label
                    + "&8 [距离 " + container.distance + "] &7已用 "
                    + ChatColor.WHITE + capacity.containerUsed(i)
                    + "&7/&f" + capacity.containerSize[i]));
        }

        ItemStack item = new ItemStack(Material.BUNDLE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(colorize(title));
        meta.setLore(lore);
        if (meta instanceof BundleMeta bundle) {
            int total = capacity.totalSlots;
            int used = capacity.usedSlots;
            if (total <= 0 || used <= 0) {
                bundle.setItems(List.of());
            } else {
                int amount = (int) Math.max(1L, Math.min(64L,
                        Math.round(64.0D * used / total)));
                bundle.setItems(List.of(new ItemStack(Material.PAPER, amount)));
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 已用百分比的字符进度条（20 格）。 */
    private static String progressBar(int percent) {
        int filled = Math.max(0, Math.min(20, (int) Math.round(percent / 5.0D)));
        return ChatColor.GREEN + "█".repeat(filled)
                + ChatColor.DARK_GRAY + "░".repeat(20 - filled)
                + ChatColor.WHITE + " " + percent + "%";
    }

    private static ItemStack glass() {
        return named(Material.BLACK_STAINED_GLASS_PANE, " ");
    }

    private static ItemStack toggleItem(boolean showLabels) {
        return named(Material.LEVER,
                showLabels ? "&a显示标记和位置：开启" : "&c显示标记和位置：关闭",
                colorize("&7点击切换标记与容器位置显示"),
                colorize(showLabels
                        ? "&7当前：每页 &f20 &7种"
                        : "&7当前：每页 &f40 &7种"));
    }

    private static ItemStack sortItem(StorageSnapshot.SortMode mode) {
        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7点击切换到下一种排序方式"));
        for (StorageSnapshot.SortMode candidate : StorageSnapshot.SortMode.values()) {
            lore.add(colorize((candidate == mode ? "&a» &f" : "&8  &7") + candidate.display()));
        }
        return named(Material.COMPARATOR, "&6排序：" + mode.display(),
                lore.toArray(new String[0]));
    }

    private static ItemStack permissionItem() {
        return named(Material.PLAYER_HEAD, "&6权限管理",
                colorize("&7点击打开成员权限列表"),
                colorize("&7只有管理员可以修改权限"));
    }

    /** 三个整理按钮：图标 + 规则说明，点击后立即在玩家线程重排连接的容器。 */
    private static ItemStack organizeItem(OrganizeMode mode) {
        return switch (mode) {
            case COMPRESS -> named(Material.PISTON, "&6压缩整理",
                    colorize("&7无视跨容器的空槽限制"),
                    colorize("&7先在各容器内堆叠，再从最远容器取物品"),
                    colorize("&7优先补满、然后使用空槽，尽量把近处容器填满"),
                    colorize("&e点击立即执行"));
            case CROSS -> named(Material.HOPPER, "&6整理物品（跨容器）",
                    colorize("&7先在各容器内堆叠至原版上限"),
                    colorize("&7再把远容器里不足一组的物品补进近容器"),
                    colorize("&7只补近容器已有的同类非满堆叠"),
                    colorize("&c绝不往空槽新建堆叠、不拆远容器的满堆叠"),
                    colorize("&e点击立即执行"));
            case LOCAL -> named(Material.CHEST, "&6整理物品（每个容器内）",
                    colorize("&7只在每一个容器内部把同类物品堆叠满"),
                    colorize("&7并从左上开始紧凑排布，减少槽位占用"),
                    colorize("&7物品不会跨容器移动"),
                    colorize("&e点击立即执行"));
        };
    }

    private static ItemStack backItem(String label) {
        return named(Material.ARROW, "&a« " + label,
                colorize("&7点击返回上一页"));
    }

    private static ItemStack addPlayerItem(boolean admin) {
        return named(Material.NAME_TAG, admin ? "&a添加玩家" : "&7添加玩家 &c(仅管理员)",
                colorize("&7点击从在线玩家中选择并添加"),
                colorize("&7新玩家默认为 &f普通成员"),
                colorize(admin ? "&7你有管理员权限" : "&c只有管理员可以添加玩家"));
    }

    private static ItemStack pageItem(boolean next, int page, int pages, int perPage) {
        return named(Material.ARROW,
                next ? "&a下一页 »" : "« &a上一页",
                colorize("&7第 &f" + page + " &7/ &f" + pages + " &7页"),
                colorize("&7每页 " + perPage + " 种物品"));
    }

    private static ItemStack memberHead(Member member, boolean viewerAdmin, boolean self) {
        List<String> lore = new ArrayList<>();
        lore.add(colorize("&7权限 &f" + (member.admin() ? "&c管理员" : "&a普通成员")));
        if (!viewerAdmin) {
            lore.add(colorize("&7只有管理员可以修改权限"));
        } else if (self) {
            lore.add(colorize("&7无法删除自己或切换自己的权限"));
        } else {
            lore.add(colorize("&e左击切换权限"));
            lore.add(colorize("&c右击删除玩家"));
        }
        return head(member.uuid(), member.name(),
                "&e" + member.name() + (member.admin() ? " &c[管理员]" : " &7[普通成员]"), lore);
    }

    private static ItemStack onlineHead(Player target, Member existing, boolean viewerAdmin) {
        List<String> lore = new ArrayList<>();
        if (existing != null) {
            lore.add(colorize("&7已在权限列表中：&f"
                    + (existing.admin() ? "管理员" : "普通成员")));
            lore.add(colorize("&8无需重复添加"));
        } else if (viewerAdmin) {
            lore.add(colorize("&e点击添加为 &a普通成员"));
            lore.add(colorize("&8之后可在权限页切换为管理员"));
        } else {
            lore.add(colorize("&c只有管理员可以添加玩家"));
        }
        return head(target.getUniqueId(), target.getName(),
                (existing == null ? "&a" : "&8") + target.getName(), lore);
    }

    /**
     * 头颅皮肤：在线玩家直接用它的 OfflinePlayer（档案已缓存，不做联网查询）；
     * 离线成员用 UUID + 名字构造档案，避免 {@code getOfflinePlayer} 的阻塞式查库。
     */
    private static ItemStack head(UUID uuid, String name, String title, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (meta instanceof SkullMeta skull) {
            try {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    skull.setOwningPlayer(online);
                } else {
                    skull.setOwnerProfile(Bukkit.createPlayerProfile(uuid, name));
                }
            } catch (RuntimeException | LinkageError ignored) {
                // 档案接口异常时退化成无皮肤头颅，名字与 lore 仍然可读
            }
        }
        meta.setDisplayName(colorize(title));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 物品格的展示名：物品名 + 灰色「x真实总数量」。
     * amount 取容器内聚合后的真实总数，不是 {@link ItemStack#getMaxStackSize()}。
     * 只用于未显示标记时的物品 icon；显示标记时物品格保留物品本身的名称，
     * 纸标记也仍用 {@link StorageSnapshot.Group#name}（纯物品名）。
     */
    private static String itemNameWithAmount(ItemStack stack, int amount) {
        String name = StorageSnapshot.itemName(stack);
        return name + ChatColor.GRAY + " x" + amount;
    }

    /** 只改展示用 icon 的名字；快照里的 template 保持原样，取出/存入拿到的仍是原物品。 */
    private static void nameIcon(ItemStack icon, int amount) {
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(colorize(itemNameWithAmount(icon, amount)));
        icon.setItemMeta(meta);
    }

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

    // ------------------------------------------------------------------
    // 点击事件
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        View view = views.get(player.getUniqueId());
        if (view == null) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0) {
            return; // 界面外点击交给原版
        }
        if (!isOurTitle(event.getView().getTitle())) {
            // 玩家已经打开了别的容器：这是过期会话，立即作废，别拦住别人的界面
            views.remove(player.getUniqueId(), view);
            view.ready = false;
            stopRefresh(view);
            return;
        }

        // 页面切换期间旧界面可能还残留一次点击：一律取消，别让原版搬走展示物
        if (!view.ready || view.switching) {
            event.setCancelled(true);
            return;
        }

        try {
            // 玩家同一时刻只能开一个界面，views 里有会话就说明顶部 54 格是我们的 GUI。
            // 不去比较 Inventory 对象：Bukkit 不同版本可能返回不同的包装实例，
            // equals 失败会让整个界面静默失效。
            if (view.screen != Screen.MAIN) {
                event.setCancelled(true);
                onMenuClick(player, view, raw, event.getClick(),
                        event instanceof InventoryCreativeEvent);
                return;
            }

            // 点击前先把可能被漏斗 / 货运改过的库存同步进快照，
            // 保证下面的 entrySlots 与 groupIndex 对应的是「玩家现在看到的」那批数据。
            rescanIfChanged(player, view);

            if (raw >= GUI_SIZE) {
                onPlayerInventoryClick(player, view, event);
                return;
            }

            // 顶部 54 格：一律拒绝原版交互（展示物品不能被拿走 / 移动）
            event.setCancelled(true);
            boolean creative = event instanceof InventoryCreativeEvent;
            ClickType click = event.getClick();
            ItemStack cursor = event.getCursor();
            boolean cursorEmpty = cursor == null || cursor.getType().isAir();

            if (raw == SLOT_TOGGLE) {
                view.showLabels = !view.showLabels;
                view.page = 0;
                if (view.blockLocation != null) {
                    writeShowLabels(view.blockLocation, view.showLabels);
                }
                render(view);
                return;
            }
            if (raw == SLOT_SORT) {
                view.sortMode = view.sortMode.next();
                writeSort(view.blockLocation, view.sortMode);
                render(view);
                return;
            }
            if (raw == SLOT_CAPACITY) {
                return; // 纯展示，无任何操作
            }
            if (raw == SLOT_PERMISSION) {
                switchScreen(player, view, Screen.PERMISSIONS);
                return;
            }
            if (raw == SLOT_COMPRESS || raw == SLOT_ORGANIZE_CROSS || raw == SLOT_ORGANIZE_LOCAL) {
                OrganizeMode mode = raw == SLOT_COMPRESS ? OrganizeMode.COMPRESS
                        : raw == SLOT_ORGANIZE_CROSS ? OrganizeMode.CROSS : OrganizeMode.LOCAL;
                runOrganize(player, view, mode);
                return;
            }
            if (raw == SLOT_PREV || raw == SLOT_NEXT) {
                int perPage = view.showLabels ? PAIRS_PER_PAGE : COMPACT_PER_PAGE;
                int pages = Math.max(1, (view.groups.size() + perPage - 1) / perPage);
                int delta = raw == SLOT_NEXT ? 1 : -1;
                int target = Math.max(0, Math.min(view.page + delta, pages - 1));
                if (target != view.page) {
                    view.page = target;
                    render(view);
                }
                return;
            }

            // Shift 左/右击固定是「取出到背包」：左击一组、右击一个，不动光标。
            boolean shiftTake = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;

            // 光标上有物品：普通点击任意展示区即存入；shift 点击仍按取出处理。
            // 关闭标记时 40 格全是物品格，存入与取出都仍然可用。
            if (!cursorEmpty && !shiftTake) {
                if (!creative) {
                    storeFromCursor(player, view, cursor, event);
                }
                return;
            }

            Integer groupIndex = view.entrySlots.get(raw);
            if (groupIndex == null || groupIndex >= view.groups.size()) {
                return; // 黑色玻璃板 / 空格：只读
            }
            StorageSnapshot.Group group = view.groups.get(groupIndex);
            boolean wholeGroup = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
            if (click == ClickType.LEFT || click == ClickType.RIGHT || shiftTake) {
                take(player, view, group, wholeGroup, creative, shiftTake);
            }
        } finally {
            // 只有点在顶部 GUI 上时，服务端的「被点击槽位回滚包」才会污染我们的
            // 展示格；点玩家背包时回滚的是背包槽，没必要整屏重绘（否则会干扰整理背包）。
            if (raw < GUI_SIZE) {
                scheduleRepaint(player, view);
            }
        }
    }

    /** 子页面点击：返回 / 翻页 / 添加玩家 / 头颅操作。 */
    private void onMenuClick(Player player, View view, int raw, ClickType click, boolean creative) {
        boolean left = click == ClickType.LEFT || creative;
        boolean right = click == ClickType.RIGHT;

        if (raw == SLOT_BACK) {
            switchScreen(player, view,
                    view.screen == Screen.ADD_PLAYER ? Screen.PERMISSIONS : Screen.MAIN);
            return;
        }
        if (raw == SLOT_ADD_PLAYER && view.screen == Screen.PERMISSIONS) {
            if (!isAdmin(player, view)) {
                player.sendMessage(ADMIN_ONLY);
                return;
            }
            switchScreen(player, view, Screen.ADD_PLAYER);
            return;
        }
        if (raw == SLOT_MENU_PREV || raw == SLOT_MENU_NEXT) {
            int pages = menuPages(view);
            int target = Math.max(0, Math.min(
                    view.menuPage + (raw == SLOT_MENU_NEXT ? 1 : -1), pages - 1));
            if (target != view.menuPage) {
                view.menuPage = target;
                renderScreen(player, view);
            }
            return;
        }

        UUID target = view.headSlots.get(raw);
        if (target == null) {
            return;
        }
        if (view.screen == Screen.PERMISSIONS) {
            onMemberClick(player, view, target, left, right);
        } else if (view.screen == Screen.ADD_PLAYER && (left || right)) {
            onAddMemberClick(player, view, target);
        }
    }

    private int menuPages(View view) {
        int count = view.screen == Screen.PERMISSIONS
                ? readAcl(view.blockLocation).list().size()
                : Bukkit.getOnlinePlayers().size();
        return Math.max(1, (count + HEADS_PER_PAGE - 1) / HEADS_PER_PAGE);
    }

    private boolean isAdmin(Player player, View view) {
        return readAcl(view.blockLocation).role(player.getUniqueId()) == Role.ADMIN;
    }

    /** 左击切换角色、右击删除；只有管理员能改，且不能动自己。 */
    private void onMemberClick(Player player, View view, UUID target, boolean left, boolean right) {
        if (!left && !right) {
            return;
        }
        Acl acl = readAcl(view.blockLocation);
        if (acl.role(player.getUniqueId()) != Role.ADMIN) {
            player.sendMessage(ADMIN_ONLY);
            return;
        }
        if (target.equals(player.getUniqueId())) {
            player.sendMessage(CANNOT_SELF);
            return;
        }
        Member member = acl.get(target);
        if (member == null) {
            return;
        }
        if (left) {
            boolean promote = !member.admin();
            writeAcl(view.blockLocation, acl.withRole(target, promote));
            player.sendMessage(String.format(ROLE_CHANGED, member.name(),
                    promote ? "管理员" : "普通成员"));
        } else {
            Acl updated = acl.without(target);
            writeAcl(view.blockLocation, updated);
            player.sendMessage(String.format(PLAYER_REMOVED, member.name()));
            enforceAcl(view.blockLocation, updated);
        }
        renderScreen(player, view);
    }

    /** 点击在线玩家头颅：默认添加为普通成员，管理员可在权限页再提升。 */
    private void onAddMemberClick(Player player, View view, UUID target) {
        Acl acl = readAcl(view.blockLocation);
        if (acl.role(player.getUniqueId()) != Role.ADMIN) {
            player.sendMessage(ADMIN_ONLY);
            return;
        }
        if (acl.get(target) != null) {
            player.sendMessage(ALREADY_MEMBER);
            return;
        }
        Player online = Bukkit.getPlayer(target);
        String name = online != null ? online.getName() : target.toString().substring(0, 8);
        writeAcl(view.blockLocation, acl.with(target, name, false));
        player.sendMessage(String.format(PLAYER_ADDED, name));
        renderScreen(player, view);
    }

    /** Shift 点击玩家背包里的物品 = 批量存入。 */
    private void onPlayerInventoryClick(Player player, View view, InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        event.setCancelled(true);
        event.setCurrentItem(null);

        ItemStack toStore = current.clone();
        int slot = event.getSlot();
        player.getInventory().setItem(slot, null);

        int stored = store(view, toStore);
        int left = toStore.getAmount() - stored;
        if (left > 0) {
            ItemStack remainder = toStore.clone();
            remainder.setAmount(left);
            player.getInventory().setItem(slot, remainder);
        }
        reportStore(player, view, stored, left);
        rescan(player, view);
    }

    private void storeFromCursor(Player player, View view, ItemStack cursor, InventoryClickEvent event) {
        event.setCursor(null);
        player.setItemOnCursor(null);

        ItemStack toStore = cursor.clone();
        int stored = store(view, toStore);
        int left = toStore.getAmount() - stored;
        if (left > 0) {
            ItemStack remainder = toStore.clone();
            remainder.setAmount(left);
            player.setItemOnCursor(remainder);
        }
        reportStore(player, view, stored, left);
        rescan(player, view);
    }

    /** 返回实际存入的数量；0 表示「连接箱子没有空位且同类物品已达堆叠上限」。 */
    private int store(View view, ItemStack toStore) {
        if (view.network.containers.isEmpty()) {
            return 0;
        }
        ItemStack template = toStore.clone();
        template.setAmount(1);
        return StorageNetwork.store(view.network, template, toStore.getAmount());
    }

    /** 存入结果只发一条消息：没连容器 / 完全存满 / 只存了一部分。 */
    private void reportStore(Player player, View view, int stored, int left) {
        if (view.network.containers.isEmpty()) {
            player.sendMessage(NO_CONNECTOR);
        } else if (stored == 0) {
            player.sendMessage(STORAGE_FULL);
        } else if (left > 0) {
            // 全部存进去时不打扰玩家；只存了一部分才提示剩余已退回
            player.sendMessage(String.format(STORED_PARTIAL, stored));
        }
    }

    /**
     * 执行菜单里的整理按钮。和 take/store 一样直接读写缓存的 {@link Inventory}
     * （Bukkit 的 {@code getItem/setItem} 不带 Folia 区域线程断言），所以可以在
     * 点击事件里同步完成；整屏刷新交给 finally 里的 {@code scheduleRepaint}。
     */
    private void runOrganize(Player player, View view, OrganizeMode mode) {
        if (view.network.containers.isEmpty()) {
            player.sendMessage(NO_CONNECTOR);
            return;
        }
        StorageOrganizer.Result result;
        try {
            result = switch (mode) {
                case COMPRESS -> StorageOrganizer.compress(view.network);
                case CROSS -> StorageOrganizer.acrossContainers(view.network);
                case LOCAL -> StorageOrganizer.withinContainers(view.network);
            };
        } catch (RuntimeException | LinkageError ex) {
            resourceLib.warn("智能存储整理失败", ex);
            player.sendMessage(colorize("&c整理失败，容器内容已尽量保持不变。"));
            return;
        }
        StringBuilder message = new StringBuilder("&a").append(mode.label)
                .append("完成：释放 &f").append(result.freedSlots()).append(" &a个槽位");
        if (result.movedItems() > 0) {
            message.append("，跨容器搬运 &f").append(result.movedItems()).append(" &a个物品");
        }
        message.append("（共 &f").append(result.containerCount()).append(" &a个容器）。");
        player.sendMessage(colorize(message.toString()));
        rescan(player, view);
    }

    /**
     * 取出：最远箱子 -> 最近箱子、每箱槽位倒序；左击一组，右击一个。
     * 普通点击放到光标，shift 点击（以及创造模式）直接进玩家背包。
     */
    private void take(Player player, View view, StorageSnapshot.Group group,
                      boolean wholeGroup, boolean creative, boolean toInventory) {
        // 不要用 group.total 当上限：那是快照值，漏斗刚补货 / 刚抽走时都不可信。
        // 直接要「一组」，由 StorageNetwork.take 扫实时库存时自然封顶（不足一组取全部）。
        int wanted = wholeGroup ? group.maxStack : 1;
        int taken = StorageNetwork.take(view.network, group.template, wanted);
        if (taken <= 0) {
            rescan(player, view);
            return;
        }
        ItemStack result = group.template.clone();
        result.setAmount(taken);

        if (toInventory || creative) {
            // Shift 点击：绕过光标直接进背包；创造模式服务端不跟踪 carried item，同样进背包
            giveToInventory(player, result);
        } else {
            ItemStack onCursor = player.getItemOnCursor();
            boolean cursorEmpty = onCursor == null || onCursor.getType().isAir();
            if (cursorEmpty) {
                player.setItemOnCursor(result);
            } else if (onCursor.isSimilar(result)
                    && onCursor.getAmount() + taken <= result.getMaxStackSize()) {
                ItemStack grown = onCursor.clone();
                grown.setAmount(onCursor.getAmount() + taken);
                player.setItemOnCursor(grown);
            } else {
                giveToInventory(player, result);
                player.sendMessage(CURSOR_OCCUPIED);
            }
        }
        rescan(player, view);
    }

    private static void giveToInventory(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // 页面切换时 openInventory 会同步关掉旧界面，那次事件不能清掉会话
        View view = views.get(player.getUniqueId());
        if (view == null || view.switching) {
            return;
        }
        if (System.currentTimeMillis() < view.closeGuardUntil) {
            // 延迟派发的旧界面关闭事件：关掉的不是当前这个 inventory
            Inventory closed = event.getInventory();
            if (closed != view.inventory && !closed.equals(view.inventory)) {
                return;
            }
        }
        // 不管关的是哪个 Inventory，玩家当前界面都没了，我们的会话一律作废。
        views.remove(player.getUniqueId(), view);
        view.ready = false;
        stopRefresh(view);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        View view = views.remove(uuid);
        if (view != null) {
            view.ready = false;
            stopRefresh(view);
        }
        opening.remove(uuid);
        refreshing.remove(uuid);
    }
}
