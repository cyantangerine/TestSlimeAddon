package me.cyantangerine.slimefunaddon.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 智能存储背包卸载 / 加载终端的过滤规则工具。
 *
 * <p><b>四种模式</b>：容器内叠加（以网络容器已有物品为白名单）、背包内叠加
 * （以背包已有物品为白名单）、黑名单、白名单。前两种的「白名单」由调用方在
 * 动作开始前从实时库存里快照出来，本类只负责比较。</p>
 *
 * <p><b>匹配标签</b>：开启时用 {@link ItemStack#isSimilar}（附魔、耐久、显示名、
 * NBT 全都一致才算同一种）；关闭时只比较 {@link ItemStack#getType()}，即
 * 「仅匹配物品 ID」，标签不同的同类物品会被视为同一种。</p>
 */
public final class StorageFilter {

    public enum Mode {
        CONTAINER_STACK("容器内叠加"),
        BACKPACK_STACK("背包内叠加"),
        BLACKLIST("黑名单"),
        WHITELIST("白名单");

        private final String display;

        Mode(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }

        /** 在一组允许的模式里循环切换，非法 / 旧值回退到第一个。 */
        public static Mode next(Mode current, List<Mode> allowed) {
            if (allowed == null || allowed.isEmpty()) {
                return CONTAINER_STACK;
            }
            for (int i = 0; i < allowed.size(); i++) {
                if (allowed.get(i) == current) {
                    return allowed.get((i + 1) % allowed.size());
                }
            }
            return allowed.get(0);
        }

        public static Mode parse(String raw, Mode fallback, List<Mode> allowed) {
            if (raw != null && !raw.isEmpty()) {
                try {
                    Mode mode = valueOf(raw);
                    if (allowed == null || allowed.contains(mode)) {
                        return mode;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 旧版本留下的非法值：回退默认
                }
            }
            return fallback;
        }
    }

    private StorageFilter() {
    }

    /** 空槽 / 空气 / 数量非正都算空。 */
    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** 两个物品是否算「同一种」。 */
    public static boolean matches(ItemStack a, ItemStack b, boolean tags) {
        if (isEmpty(a) || isEmpty(b)) {
            return false;
        }
        return tags ? a.isSimilar(b) : a.getType() == b.getType();
    }

    /** 物品是否命中过滤列表里的任意一项。空列表永远返回 false。 */
    public static boolean anyMatch(List<ItemStack> filters, ItemStack stack, boolean tags) {
        if (filters == null || filters.isEmpty()) {
            return false;
        }
        for (ItemStack filter : filters) {
            if (matches(filter, stack, tags)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一批物品按 {@code tags} 归并成模板列表（数量一律 1），用于
     * 「容器内叠加 / 背包内叠加」的动态白名单。
     */
    public static List<ItemStack> distinct(Collection<ItemStack> source, boolean tags) {
        List<ItemStack> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ItemStack stack : source) {
            if (isEmpty(stack) || anyMatch(result, stack, tags)) {
                continue;
            }
            ItemStack template = stack.clone();
            template.setAmount(1);
            result.add(template);
        }
        return result;
    }

    /** 把一个 Inventory 的全部内容展开成模板列表。 */
    public static List<ItemStack> templatesOf(Iterable<org.bukkit.inventory.Inventory> inventories, boolean tags) {
        List<ItemStack> all = new ArrayList<>();
        if (inventories == null) {
            return List.of();
        }
        for (org.bukkit.inventory.Inventory inventory : inventories) {
            if (inventory == null) {
                continue;
            }
            for (ItemStack stack : inventory.getContents()) {
                if (!isEmpty(stack)) {
                    all.add(stack);
                }
            }
        }
        return distinct(all, tags);
    }
}
