package me.cyantangerine.slimefunaddon.model;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * 把网络里所有容器的库存聚合成「一种物品一行」的只读快照。
 *
 * <p>同一物品用 {@code ItemStack#isSimilar} 归并（显示名、附魔、NBT 不同算两种，
 * 数量不同算同一种）。「组」= 一个满堆叠，组数 = ceil(总数 / 单格上限)，不足一组显示 1。
 * 组数保留真实值（可以远超 64），纸标记的实际堆叠数量在界面层才截到 64，
 * lore 里始终显示真实组数。</p>
 *
 * <p>每个组额外记录「最近位置」：网络容器列表已按「距离 -> 方向序 -> 坐标」排好，
 * 按这个顺序扫描时第一次遇到该物品的 (容器下标, 槽位下标) 就是它的最近位置，
 * 用作容器距离排序的键。同一种物品即使在多个箱子里也只占一行。</p>
 */
public final class StorageSnapshot {

    /**
     * 创造物品栏分类在新版 Folia 上已被标记 for removal，直接调用一旦方法被删就会
     * 让整个排序按钮抛 NoSuchMethodError。反射取 ordinal，缺失时退回材质 ID 排序。
     */
    private static final Method GET_CREATIVE_CATEGORY = findCreativeCategory();

    private static Method findCreativeCategory() {
        try {
            return Material.class.getMethod("getCreativeCategory");
        } catch (NoSuchMethodException | LinkageError ignored) {
            return null;
        }
    }

    /** 菜单排序按钮依次切换的 6 种顺序。 */
    public enum SortMode {
        DISTANCE_ASC("容器距离升序"),
        DISTANCE_DESC("容器距离降序"),
        CREATIVE("创造物品栏"),
        COUNT_DESC("物品数量降序"),
        COUNT_ASC("物品数量升序"),
        ID_ASC("物品ID升序");

        private final String display;

        SortMode(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** 一种物品聚合后的展示数据。 */
    public static final class Group {
        /** 数量固定为 1 的物品模板，用作展示图标与取出/存入的匹配依据。 */
        public final ItemStack template;
        public final String name;
        public final int total;
        /** 真实组数 = ceil(total / maxStack)，最少 1，不按客户端 64 张纸的上限截断。 */
        public final int groups;
        public final int maxStack;
        /** 下标与 {@link StorageNetwork.Network#containers} 对齐，每个容器里有多少个。 */
        public final int[] perContainer;
        /** 最近位置（容器列表下标）。 */
        public final int firstContainer;
        /** 最近位置里的第一个槽位。 */
        public final int firstSlot;
        /** namespaced id，例如 minecraft:stone，用于「物品ID升序」。 */
        public final String id;

        private Group(ItemStack template, String name, int total, int groups, int maxStack,
                      int[] perContainer, int firstContainer, int firstSlot, String id) {
            this.template = template;
            this.name = name;
            this.total = total;
            this.groups = groups;
            this.maxStack = maxStack;
            this.perContainer = perContainer;
            this.firstContainer = firstContainer;
            this.firstSlot = firstSlot;
            this.id = id;
        }
    }

    /** 已连接容器的容量统计，给菜单里的容量监控用（对外统一按「已用」展示）。 */
    public static final class Capacity {
        public final int totalSlots;
        public final int emptySlots;
        /** 已用槽数 = 总槽数 - 空槽数。 */
        public final int usedSlots;
        public final int containerCount;
        /** 每个容器各自的空槽数与总槽数，下标与 containers 对齐。 */
        public final int[] containerEmpty;
        public final int[] containerSize;

        private Capacity(int totalSlots, int emptySlots, int containerCount,
                         int[] containerEmpty, int[] containerSize) {
            this.totalSlots = totalSlots;
            this.emptySlots = emptySlots;
            this.usedSlots = Math.max(0, totalSlots - emptySlots);
            this.containerCount = containerCount;
            this.containerEmpty = containerEmpty;
            this.containerSize = containerSize;
        }

        /** 已用百分比（0-100）；没有连接容器时返回 0。 */
        public int usedPercent() {
            return totalSlots <= 0 ? 0 : (int) Math.round(usedSlots * 100.0D / totalSlots);
        }

        /** 第 index 个容器的已用槽数。 */
        public int containerUsed(int index) {
            return Math.max(0, containerSize[index] - containerEmpty[index]);
        }
    }

    private static final class Builder {
        private final ItemStack template;
        private final int[] perContainer;
        private int total;
        private int firstSlot = -1;
        private int firstContainer = -1;

        private Builder(ItemStack template, int containerCount, int containerIndex, int slot) {
            this.template = template;
            this.perContainer = new int[containerCount];
            this.firstContainer = containerIndex;
            this.firstSlot = slot;
        }
    }

    public final List<Group> groups;
    /** 快照对应的容器顺序，lore 里显示容器名时要用到。 */
    public final List<StorageNetwork.ContainerRef> containers;
    public final Capacity capacity;
    /** 库存内容指纹：外部（漏斗 / 货运 / 其它玩家）改动后用来判断要不要重绘界面。 */
    public final String fingerprint;

    private StorageSnapshot(List<Group> groups, List<StorageNetwork.ContainerRef> containers,
                            Capacity capacity, String fingerprint) {
        this.groups = groups;
        this.containers = containers;
        this.capacity = capacity;
        this.fingerprint = fingerprint;
    }

    public boolean sameContents(StorageSnapshot other) {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    /** 按指定模式返回排序后的组列表；原 {@link #groups} 保持发现顺序，不受排序影响。 */
    public List<Group> sorted(SortMode mode) {
        List<Group> result = new ArrayList<>(groups);
        if (mode == null) {
            mode = SortMode.DISTANCE_ASC;
        }
        Comparator<Group> fallback = Comparator.<Group>comparingInt(g -> g.firstContainer)
                .thenComparingInt(g -> g.firstSlot)
                .thenComparing(g -> g.id)
                .thenComparing(g -> g.name);

        Comparator<Group> comparator = switch (mode) {
            case DISTANCE_ASC -> fallback;
            // 「只取最近位置」仍然成立，只是把最近位置的容器/槽位顺序整体倒过来
            case DISTANCE_DESC -> Comparator.<Group>comparingInt(g -> g.firstContainer).reversed()
                    .thenComparing(Comparator.comparingInt((Group g) -> g.firstSlot).reversed())
                    .thenComparing(g -> g.id)
                    .thenComparing(g -> g.name);
            case CREATIVE -> Comparator.<Group>comparingInt(StorageSnapshot::creativeRank)
                    .thenComparingInt(g -> g.template.getType().ordinal())
                    .thenComparing(g -> g.id)
                    .thenComparing(g -> g.name);
            case COUNT_DESC -> Comparator.<Group>comparingInt(g -> g.total).reversed()
                    .thenComparing(g -> g.id)
                    .thenComparing(g -> g.name);
            case COUNT_ASC -> Comparator.<Group>comparingInt(g -> g.total)
                    .thenComparing(g -> g.id)
                    .thenComparing(g -> g.name);
            case ID_ASC -> Comparator.comparing((Group g) -> g.id)
                    .thenComparing(g -> g.name);
        };
        result.sort(comparator);
        return result;
    }

    /**
     * 创造物品栏的分类序号；Bukkit 没暴露分类内的精确顺序，同分类内再按材质 ID 排。
     * 直接用 ordinal 而不是 switch 穷举：运行时的枚举若比编译期多出常量，
     * javac 生成的 switch 映射表会越界，ordinal 则始终安全。
     */
    private static int creativeRank(Group group) {
        if (GET_CREATIVE_CATEGORY != null) {
            try {
                Object category = GET_CREATIVE_CATEGORY.invoke(group.template.getType());
                if (category instanceof Enum<?> value) {
                    return value.ordinal();
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 落到下面的兜底
            }
        }
        // 无分类（命令方块等）或该 API 已移除：统一排到最后
        return 99;
    }

    /**
     * 直接读缓存的 {@link Inventory} 对象，不触碰世界方块，因此可在玩家线程同步调用。
     */
    public static StorageSnapshot of(StorageNetwork.Network network) {
        List<StorageNetwork.ContainerRef> containers = network.containers;
        // 键统一把数量设为 1，ItemStack#equals 会按 isSimilar + 数量比较，正好合用
        Map<ItemStack, Builder> builders = new LinkedHashMap<>();
        int[] containerEmpty = new int[containers.size()];
        int[] containerSize = new int[containers.size()];
        int totalSlots = 0;
        int emptySlots = 0;

        for (int index = 0; index < containers.size(); index++) {
            Inventory inventory = containers.get(index).inventory;
            int size = inventory.getSize();
            containerSize[index] = size;
            totalSlots += size;
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                    containerEmpty[index]++;
                    emptySlots++;
                    continue;
                }
                ItemStack key = stack.clone();
                key.setAmount(1);
                // lambda 只能捕获 effectively final 的局部变量
                int containerIndex = index;
                int slotIndex = slot;
                Builder builder = builders.computeIfAbsent(key,
                        k -> new Builder(k.clone(), containers.size(), containerIndex, slotIndex));
                builder.total += stack.getAmount();
                builder.perContainer[index] += stack.getAmount();
            }
        }

        List<Group> result = new ArrayList<>(builders.size());
        for (Builder builder : builders.values()) {
            ItemStack template = builder.template;
            int maxStack = Math.max(1, template.getMaxStackSize());
            long ceil = (builder.total + (long) maxStack - 1) / maxStack;
            // 这里保留真实组数：纸标记最多只能叠 64 张，那个上限只在界面渲染时套用，
            // 否则 lore 里 64 组以上的真实数字会被吞掉。
            int groups = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ceil));
            result.add(new Group(template, itemName(template), builder.total, groups,
                    maxStack, builder.perContainer, builder.firstContainer, builder.firstSlot,
                    template.getType().getKey().toString()));
        }

        // 指纹覆盖「容器集合 + 每种物品 + 每箱分布 + 空槽数」，既能发现外部搬运，
        // 又不会因同类物品在箱子内部换格子而误判成变动、白重绘一次。
        StringBuilder fingerprint = new StringBuilder();
        for (StorageNetwork.ContainerRef container : containers) {
            fingerprint.append(container.key).append('|');
        }
        fingerprint.append('#').append(Arrays.toString(containerEmpty)).append('#');
        for (Group group : result) {
            fingerprint.append(group.template.hashCode()).append(':')
                    .append(group.total).append(':')
                    .append(Arrays.toString(group.perContainer)).append('|');
        }
        return new StorageSnapshot(result, containers,
                new Capacity(totalSlots, emptySlots, containers.size(), containerEmpty, containerSize),
                fingerprint.toString());
    }

    /**
     * Slimefun 物品用显示名，其余优先自定义名，其次原版简体中文名，最后退回材质名。
     * 界面类给「物品格」拼展示名时也复用这一套，避免两处命名规则分叉。
     */
    public static String itemName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        // 自定义显示名优先：含玩家重命名的 Slimefun 物品（如改名的背包 / 无线终端）。
        // 普通 Slimefun 物品的显示名本身写在 ItemMeta 里，所以没改名时这里取到的就是原名，
        // 改名后才返回玩家输入的名字，从而避免「带改名 Slimefun 物品只显示原始名称」的问题。
        if (meta != null) {
            String display = meta.getDisplayName();
            if (display != null && !display.isEmpty()) {
                return display;
            }
        }
        // 没有自定义显示名时，Slimefun 物品退回其注册名
        SlimefunItem slimefunItem = SlimefunItem.getByItem(stack);
        if (slimefunItem != null) {
            return slimefunItem.getItemName();
        }
        String chinese = VanillaItemName.of(stack.getType());
        return ChatColor.WHITE + (chinese != null ? chinese : stack.getType().name());
    }
}
