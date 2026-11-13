package me.cyantangerine.slimefunaddon.model;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * 智能存储网络的拓扑发现与容器读写。
 *
 * <p><b>网络形状</b>：交互接口相邻的「存储连接器」是入口（距离 1），
 * 连接器之间沿上下左右前后 1 格泛洪（距离 = 经过的连接器个数，最短路）。
 * 每个连接器把自己 6 邻格里的箱子 / 陷阱箱 / 木桶登记为容器，容器的距离取
 * 「所有相邻连接器距离」的最小值。箱子是叶子节点，<b>不会</b>把两个连接器网络
 * 桥接起来（对应「不能组合为一个网络」）。大箱子两半通过 {@code Inventory#getLocation()}
 * 的中点去重，算作 1 个容器。</p>
 *
 * <p><b>Folia</b>：本类所有会触碰世界方块的方法都必须在方块所属区域线程调用
 * （调用方统一用 {@code ResourceLib#runAtBlock} 切到交互接口的位置）。
 * 发现阶段拿到的 {@link Inventory} 是包着真实方块实体的对象，
 * Bukkit 的 {@code getItem/setItem} 不带区域线程断言，所以之后的存取可以直接在
 * 玩家线程用缓存对象完成，无需再读世界。</p>
 */
public final class StorageNetwork {

    /** 一个网络最多连接的容器数（大箱子算 1 个）。 */
    public static final int MAX_CONTAINERS = 32;
    /** 防御性上限：避免有人摆出超长连接器链把一次发现拖死。 */
    public static final int MAX_CONNECTORS = 512;

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /**
     * 同距离时容器的方向优先顺序：上、东、西、南、北、下。
     * 这个下标同时作为 {@link ContainerRef#direction} 的排序键。
     */
    private static final BlockFace[] DIRECTIONS = {
            BlockFace.UP, BlockFace.EAST, BlockFace.WEST,
            BlockFace.SOUTH, BlockFace.NORTH, BlockFace.DOWN
    };

    /**
     * Paper/Folia 的 {@code Block#getState()} 返回快照。快照里的箱子拿不到
     * 「大箱子合并后的 54 格 Inventory」，只会给出那一半的 27 格。
     * 所以优先反射调 Paper 专有的 {@code getState(false)} 拿实时状态；
     * Spigot 1.19.2 没有这个重载，退回 {@code getState()}。
     */
    private static final Method GET_STATE_LIVE = findGetStateLive();

    /**
     * Folia 上「交互接口所在区域线程」读另一个 tick region 的方块会抛线程断言。
     * 正常的一间储物室（十几个方块）都在同一个 region 里；真跨区时至少给一条日志，
     * 否则容器会被静默忽略、很难排查。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean CROSS_REGION_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private StorageNetwork() {
    }

    private static Method findGetStateLive() {
        try {
            return Block.class.getMethod("getState", boolean.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static BlockState liveState(Block block) {
        if (GET_STATE_LIVE != null) {
            try {
                BlockState state = (BlockState) GET_STATE_LIVE.invoke(block, false);
                if (state != null) {
                    return state;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 落到下面的快照路径
            }
        }
        return block.getState();
    }

    // ------------------------------------------------------------------
    // 位置键
    // ------------------------------------------------------------------

    /** 不带 yaw/pitch 的方块坐标键，用作 Map/Set 的键。 */
    public record BlockKey(UUID world, int x, int y, int z) {

        public static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        public static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        public Location location() {
            World world = Bukkit.getWorld(this.world);
            return world == null ? null : new Location(world, x, y, z);
        }

        public Block block() {
            World world = Bukkit.getWorld(this.world);
            return world == null ? null : world.getBlockAt(x, y, z);
        }

        /** 上下左右前后 1 格，绝不含斜对角。 */
        public List<BlockKey> neighbours() {
            List<BlockKey> result = new ArrayList<>(6);
            for (BlockFace face : FACES) {
                result.add(new BlockKey(world,
                        x + face.getModX(), y + face.getModY(), z + face.getModZ()));
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // 数据载体
    // ------------------------------------------------------------------

    /** 容器（箱子 / 大箱子 / 木桶）在网络中的一个节点。 */
    public static final class ContainerRef {
        /** 去重键：单容器用方块位置，大箱子用两半中点。 */
        public final String key;
        public final BlockKey block;
        /** 至交互接口的最短经由连接器距离。 */
        public final int distance;
        /** 容器相对连接器的方向序（0=上 1=东 2=西 3=南 4=北 5=下），同距离时按它排序。 */
        public final int direction;
        public final Inventory inventory;
        public final String label;

        private ContainerRef(String key, BlockKey block, int distance, int direction,
                             Inventory inventory, String label) {
            this.key = key;
            this.block = block;
            this.distance = distance;
            this.direction = direction;
            this.inventory = inventory;
            this.label = label;
        }
    }

    /** 连接器节点，{@code hasChests} 用于「至最后一个有链接箱子的连接器止」。 */
    public static final class ConnectorRef {
        public final BlockKey block;
        public final int distance;
        public final boolean hasChests;

        private ConnectorRef(BlockKey block, int distance, boolean hasChests) {
            this.block = block;
            this.distance = distance;
            this.hasChests = hasChests;
        }
    }

    /** 一次发现的结果。容器与连接器都按距离升序排好。 */
    public static final class Network {
        public final Location interfaceLocation;
        public final List<ContainerRef> containers;
        public final List<ConnectorRef> connectors;
        /** 距离最远的「有链接箱子」的连接器距离；0 表示整个网络没连到任何箱子。 */
        public final int lastChestConnectorDistance;

        private Network(Location interfaceLocation, List<ContainerRef> containers,
                        List<ConnectorRef> connectors, int lastChestConnectorDistance) {
            this.interfaceLocation = interfaceLocation;
            this.containers = containers;
            this.connectors = connectors;
            this.lastChestConnectorDistance = lastChestConnectorDistance;
        }
    }

    // ------------------------------------------------------------------
    // 方块识别
    // ------------------------------------------------------------------

    /** 只认箱子 / 陷阱箱 / 木桶；漏斗、发射器、潜影盒一律不算。 */
    public static boolean isStorageMaterial(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    /** 是否为我们的存储连接器（读 Slimefun 的 BlockStorage，不读世界）。 */
    public static boolean isConnector(BlockKey key, SlimefunItem connectorItem) {
        if (connectorItem == null) {
            return false;
        }
        Location location = key.location();
        if (location == null) {
            return false;
        }
        try {
            return BlockStorage.check(location) == connectorItem;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean isInterface(BlockKey key, SlimefunItem interfaceItem) {
        if (interfaceItem == null) {
            return false;
        }
        Location location = key.location();
        if (location == null) {
            return false;
        }
        try {
            return BlockStorage.check(location) == interfaceItem;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 该位置是否是一个「原版」存储容器（排除所有 Slimefun 方块）。 */
    public static boolean isStorageBlock(BlockKey key) {
        Block block = key.block();
        if (block == null) {
            return false;
        }
        try {
            if (!isStorageMaterial(block.getType())) {
                return false;
            }
            return BlockStorage.check(block) == null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 这个连接器 6 邻格里是否至少有一个容器。 */
    public static boolean hasLinkedContainer(BlockKey key) {
        for (BlockKey neighbour : key.neighbours()) {
            if (isStorageBlock(neighbour)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 发现
    // ------------------------------------------------------------------

    /**
     * 从交互接口出发发现整个网络。必须在交互接口方块所属区域线程调用。
     */
    public static Network discover(Location interfaceLocation,
                                   SlimefunItem connectorItem,
                                   SlimefunItem interfaceItem) {
        BlockKey origin = BlockKey.of(interfaceLocation);

        Map<BlockKey, Integer> connectorDistances = new LinkedHashMap<>();
        Queue<BlockKey> queue = new ArrayDeque<>();
        for (BlockKey neighbour : origin.neighbours()) {
            if (isConnector(neighbour, connectorItem)) {
                connectorDistances.put(neighbour, 1);
                queue.add(neighbour);
            }
        }

        Map<String, ContainerRef> containers = new LinkedHashMap<>();
        Map<BlockKey, Boolean> connectorHasChests = new HashMap<>();

        while (!queue.isEmpty()) {
            BlockKey current = queue.poll();
            int distance = connectorDistances.getOrDefault(current, 1);
            boolean linked = false;

            for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                BlockFace face = DIRECTIONS[direction];
                BlockKey neighbour = new BlockKey(current.world,
                        current.x + face.getModX(),
                        current.y + face.getModY(),
                        current.z + face.getModZ());

                // 接口方块本身不是容器，也绝不参与泛洪
                if (neighbour.equals(origin) || isInterface(neighbour, interfaceItem)) {
                    continue;
                }
                // 遇到连接器就泛洪；箱子不传播，两个相邻网络不会经箱子合并
                if (isConnector(neighbour, connectorItem)) {
                    if (!connectorDistances.containsKey(neighbour)
                            && connectorDistances.size() < MAX_CONNECTORS) {
                        connectorDistances.put(neighbour, distance + 1);
                        queue.add(neighbour);
                    }
                    continue;
                }

                ContainerRef found = readContainer(neighbour, distance, direction);
                if (found == null) {
                    continue;
                }
                linked = true;
                ContainerRef previous = containers.get(found.key);
                if (previous == null) {
                    if (containers.size() < MAX_CONTAINERS) {
                        containers.put(found.key, found);
                    }
                } else if (distance < previous.distance
                        || (distance == previous.distance && direction < previous.direction)) {
                    // 同一个箱子挨着同网络多个连接器：同时连接，距离取最短；
                    // 距离相同时按上-东-西-南-北-下的方向序取靠前的那个位置。
                    containers.put(found.key, found);
                }
            }
            connectorHasChests.put(current, linked);
        }

        List<ConnectorRef> connectors = new ArrayList<>(connectorDistances.size());
        int lastChestDistance = 0;
        for (Map.Entry<BlockKey, Integer> entry : connectorDistances.entrySet()) {
            boolean hasChest = Boolean.TRUE.equals(connectorHasChests.get(entry.getKey()));
            connectors.add(new ConnectorRef(entry.getKey(), entry.getValue(), hasChest));
            if (hasChest) {
                lastChestDistance = Math.max(lastChestDistance, entry.getValue());
            }
        }
        connectors.sort(Comparator.<ConnectorRef>comparingInt(c -> c.distance)
                .thenComparing(c -> c.block.x).thenComparing(c -> c.block.y).thenComparing(c -> c.block.z));

        List<ContainerRef> containerList = new ArrayList<>(containers.values());
        // 这个顺序就是界面默认排序里的「容器顺序」：距离升序 -> 方向序 -> 坐标 -> 去重键。
        containerList.sort(Comparator.<ContainerRef>comparingInt(c -> c.distance)
                .thenComparingInt(c -> c.direction)
                .thenComparingInt(c -> c.block.x)
                .thenComparingInt(c -> c.block.y)
                .thenComparingInt(c -> c.block.z)
                .thenComparing(c -> c.key));

        return new Network(interfaceLocation, containerList, connectors, lastChestDistance);
    }

    /** 读取一个 6 邻格容器；非容器 / Slimefun 方块 / 跨区域读取失败时返回 null。 */
    private static ContainerRef readContainer(BlockKey key, int distance, int direction) {
        Block block = key.block();
        if (block == null) {
            return null;
        }
        try {
            Material type = block.getType();
            if (!isStorageMaterial(type) || BlockStorage.check(block) != null) {
                return null;
            }
            BlockState state = liveState(block);
            if (!(state instanceof InventoryHolder holder)) {
                return null;
            }
            Inventory inventory = holder.getInventory();
            if (inventory == null) {
                return null;
            }

            // 大箱子两半的 getInventory() 都返回同一个 DoubleChest 合并库存，
            // 其 getLocation() 是两半中点；用「半格精度」的坐标做去重键，
            // 既让两半落到同一个键，又不会和单箱子（整数坐标）撞键。
            Location canonical = inventory.getLocation();
            if (canonical == null || canonical.getWorld() == null) {
                canonical = block.getLocation();
            }
            String identity = canonicalKey(canonical);

            String typeName = switch (type) {
                case CHEST -> "箱子";
                case TRAPPED_CHEST -> "陷阱箱";
                case BARREL -> "木桶";
                default -> type.name();
            };
            boolean large = inventory.getSize() > 27;
            String label = (large ? "大" + typeName : typeName)
                    + " (" + key.x + ", " + key.y + ", " + key.z + ")";
            return new ContainerRef(identity, key, distance, direction, inventory, label);
        } catch (RuntimeException | LinkageError ignored) {
            // Folia 上若该方块恰好落在其它区域，跳过它而不是让整次发现炸掉
            if (CROSS_REGION_WARNED.compareAndSet(false, true)) {
                Bukkit.getLogger().warning("[CyanTestAddon] 智能存储：部分容器不在交互接口所在的 Folia 区域，"
                        + "已跳过；请把存储网络放在同一区域内。");
            }
            return null;
        }
    }

    private static String canonicalKey(Location location) {
        return location.getWorld().getUID()
                + ":" + Math.round(location.getX() * 2)
                + ":" + Math.round(location.getY() * 2)
                + ":" + Math.round(location.getZ() * 2);
    }

    /**
     * 给事件里拿到的 {@link Inventory} 算身份键，和 {@link ContainerRef#key} 同一套规则。
     * 用于判断「漏斗 / 货运正在搬运的到底是不是网络里的那个箱子」，
     * 不能靠 Inventory 对象相等：不同 API 会给出同一个方块实体的不同包装实例。
     * 返回空串表示这是个没有方块位置的库存（漏斗矿车、虚拟库存等），不可能是我们的容器。
     */
    public static String inventoryKey(Inventory inventory) {
        if (inventory == null) {
            return "";
        }
        try {
            Location location = inventory.getLocation();
            if (location == null || location.getWorld() == null) {
                return "";
            }
            return canonicalKey(location);
        } catch (RuntimeException | LinkageError ignored) {
            return "";
        }
    }

    /**
     * 同步剔除某个被拆掉的容器，返回一个新的 {@link Network}（原对象不变）。
     * 界面打开期间箱子被拆，缓存的 Inventory 会指向已经清空的方块实体，
     * 再往里存物品会直接丢；这里在方块破坏事件里当场剪掉，避免那次写入。
     * 大箱子拆任意一半都要命中：{@code inventory.getLocation()} 是两半中点。
     */
    public static Network withoutContainer(Network network, BlockKey broken) {
        if (network == null || broken == null) {
            return network;
        }
        List<ContainerRef> kept = new ArrayList<>(network.containers.size());
        for (ContainerRef container : network.containers) {
            if (!containerTouches(container, broken)) {
                kept.add(container);
            }
        }
        if (kept.size() == network.containers.size()) {
            return network;
        }
        return new Network(network.interfaceLocation, kept,
                network.connectors, network.lastChestConnectorDistance);
    }

    private static boolean containerTouches(ContainerRef container, BlockKey broken) {
        if (container.block.equals(broken)) {
            return true;
        }
        try {
            Location canonical = container.inventory.getLocation();
            return canonical != null && canonical.getWorld() != null
                    && BlockKey.of(canonical).equals(broken);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 连接器连通分量（给可视化开关用，不需要交互接口）
    // ------------------------------------------------------------------

    /**
     * 从某个连接器出发泛洪出连通分量，返回「位置 -> 经由连接器跳数」。
     * 只沿连接器走，不经过箱子。读的是 BlockStorage，任意线程都能调用。
     */
    public static Map<BlockKey, Integer> connectorComponent(Location start, SlimefunItem connectorItem) {
        BlockKey root = BlockKey.of(start);
        Map<BlockKey, Integer> distances = new LinkedHashMap<>();
        if (!isConnector(root, connectorItem)) {
            return distances;
        }
        Queue<BlockKey> queue = new ArrayDeque<>();
        distances.put(root, 0);
        queue.add(root);
        while (!queue.isEmpty() && distances.size() < MAX_CONNECTORS) {
            BlockKey current = queue.poll();
            int next = distances.get(current) + 1;
            for (BlockKey neighbour : current.neighbours()) {
                if (isConnector(neighbour, connectorItem) && !distances.containsKey(neighbour)) {
                    distances.put(neighbour, next);
                    queue.add(neighbour);
                }
            }
        }
        return distances;
    }

    /** 把所有已放置连接器按连通分量分组（只读 BlockStorage）。 */
    public static List<Set<BlockKey>> connectorComponents(Iterable<BlockKey> placed, SlimefunItem connectorItem) {
        Set<BlockKey> remaining = new LinkedHashSet<>();
        for (BlockKey key : placed) {
            if (isConnector(key, connectorItem)) {
                remaining.add(key);
            }
        }
        List<Set<BlockKey>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockKey seed = remaining.iterator().next();
            Location location = seed.location();
            if (location == null) {
                remaining.remove(seed);
                continue;
            }
            Map<BlockKey, Integer> component = connectorComponent(location, connectorItem);
            Set<BlockKey> group = new LinkedHashSet<>(component.keySet());
            components.add(group);
            remaining.removeAll(group);
        }
        return components;
    }

    // ------------------------------------------------------------------
    // 取出 / 存入
    // ------------------------------------------------------------------

    /**
     * 取出：从<b>最远</b>箱子到<b>最近</b>箱子，每个箱子按槽位<b>倒序</b>扫描。
     * 返回实际取出的个数，调用方据此写光标。
     */
    public static int take(Network network, ItemStack template, int wanted) {
        if (wanted <= 0 || template == null) {
            return 0;
        }
        int remaining = wanted;
        // network.containers 已经是「距离 -> 方向序 -> 坐标」升序，直接倒着遍历
        // 就是最远箱 -> 最近箱；同距离时也和界面展示顺序严格互为逆序。
        for (int index = network.containers.size() - 1; index >= 0 && remaining > 0; index--) {
            ContainerRef container = network.containers.get(index);
            Inventory inventory = container.inventory;
            for (int slot = inventory.getSize() - 1; slot >= 0 && remaining > 0; slot--) {
                ItemStack current = inventory.getItem(slot);
                if (current == null || current.getType().isAir() || !current.isSimilar(template)) {
                    continue;
                }
                int amount = current.getAmount();
                int taken = Math.min(remaining, amount);
                int left = amount - taken;
                if (left <= 0) {
                    inventory.setItem(slot, null);
                } else {
                    ItemStack rest = current.clone();
                    rest.setAmount(left);
                    inventory.setItem(slot, rest);
                }
                remaining -= taken;
            }
        }
        return wanted - remaining;
    }

    /**
     * 存入：取出逻辑的反向 —— 从<b>最近</b>箱子到<b>最远</b>箱子，槽位<b>正序</b>，
     * 先补同类部分堆叠再用空槽。返回实际存入的个数；返回 0 即「存满」。
     */
    public static int store(Network network, ItemStack template, int amount) {
        if (amount <= 0 || template == null) {
            return 0;
        }
        int maxStack = Math.max(1, template.getMaxStackSize());
        int remaining = amount;

        for (ContainerRef container : network.containers) {
            Inventory inventory = container.inventory;
            for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (current == null || current.getType().isAir()) {
                    int add = Math.min(remaining, maxStack);
                    ItemStack placed = template.clone();
                    placed.setAmount(add);
                    inventory.setItem(slot, placed);
                    remaining -= add;
                } else if (current.isSimilar(template) && current.getAmount() < maxStack) {
                    int add = Math.min(remaining, maxStack - current.getAmount());
                    ItemStack grown = current.clone();
                    grown.setAmount(current.getAmount() + add);
                    inventory.setItem(slot, grown);
                    remaining -= add;
                }
            }
        }
        return amount - remaining;
    }
}
