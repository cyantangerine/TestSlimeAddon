package me.cyantangerine.slimefunaddon.model;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 智能存储终端的纯搬运逻辑（不碰世界、不碰玩家、不做线程调度）。
 *
 * <p><b>调用约定</b>：调用方必须已经在「容器方块所属区域线程」上，并且已经把
 * {@link Inventory}（网络的原版箱子 + Slimefun 背包的 Inventory）都准备好。
 * 本类只做 {@code getItem/setItem}，Bukkit 的这两个方法没有 Folia 区域线程断言，
 * 所以和 {@link SmartStorageInterface} 的取 / 存一样可以同步完成。</p>
 *
 * <p><b>网络顺序</b>：传入的 {@code network} 已经是 StorageNetwork 的
 * 「距离升序 -> 方向序 -> 坐标」顺序，也就是近容器在前。</p>
 */
public final class StorageTransfer {

    /** moved = 搬运的物品个数；kinds = 涉及多少种物品；outOfSpace = 是否因网络满而提前停止。
     *  entries = 按物品种类拆分的明细（展示名 + 数量），用于给玩家的回执消息。 */
    public record Result(int moved, int kinds, boolean outOfSpace, List<Entry> entries) {
        /** 单种物品的回执：{@code name} 是中文展示名，{@code amount} 是本次搬运的净数量。 */
        public record Entry(String name, int amount) {}

        public static Result empty() {
            return new Result(0, 0, false, List.of());
        }
    }

    private StorageTransfer() {
    }

    /**
     * 卸载：把背包里的物品存进网络容器。
     *
     * <p>背包槽位<b>倒序</b>扫描；只搬 {@code allowed && permit} 命中的物品；
     * 每个槽位尽量整叠搬走，网络先补同类非满堆叠再用空槽。
     * 一旦某个槽位一个都放不下（或部分放下后网络已满），立即停止整次卸载 ——
     * 这对应需求里的「不足时停止」。</p>
     */
    public static Result unload(List<Inventory> network, Inventory backpack,
                                Predicate<ItemStack> permitted, boolean tags,
                                Predicate<ItemStack> allowed) {
        if (network == null || network.isEmpty() || backpack == null) {
            return Result.empty();
        }
        Acc acc = new Acc();

        for (int slot = backpack.getSize() - 1; slot >= 0; slot--) {
            ItemStack stack = backpack.getItem(slot);
            if (StorageFilter.isEmpty(stack)) {
                continue;
            }
            if (!allowed.test(stack) || !permitted.test(stack)) {
                continue;
            }
            int wanted = stack.getAmount();
            int stored = storeIntoNetwork(network, stack, wanted, tags);
            if (stored <= 0) {
                acc.outOfSpace = true;
                break;
            }

            int left = wanted - stored;
            if (left <= 0) {
                backpack.setItem(slot, null);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(left);
                backpack.setItem(slot, rest);
            }
            count(acc, stack, stored, tags);

            if (left > 0) {
                // 这个物品没能整叠放下，说明网络已经没有它的位置了
                acc.outOfSpace = true;
                break;
            }
        }
        return build(acc);
    }

    /**
     * 加载：把网络容器里的物品取进背包。
     *
     * <p>背包槽位<b>正序</b>扫描（需求：加载所有时「匹配顺序按照正序来」）。</p>
     * <ul>
     *   <li>槽位已有物品且命中过滤：从网络取同类把它补到 {@code stackLimit}（背包自己的单格上限）。</li>
     *   <li>槽位为空：{@code fillOnly}（加载至满堆叠）时跳过，绝不新占槽位；
     *       「加载所有」时从网络里按容器 / 槽位正序找第一个命中的物品种类，取一组放进去。</li>
     * </ul>
     */
    public static Result load(List<Inventory> network, Inventory backpack,
                              Predicate<ItemStack> permitted, boolean tags,
                              boolean fillOnly, Predicate<ItemStack> allowed,
                              ToIntFunction<ItemStack> stackLimit) {
        if (network == null || network.isEmpty() || backpack == null) {
            return Result.empty();
        }
        Acc acc = new Acc();

        for (int slot = 0; slot < backpack.getSize(); slot++) {
            ItemStack current = backpack.getItem(slot);

            if (StorageFilter.isEmpty(current)) {
                if (fillOnly) {
                    continue;
                }
                ItemStack chosen = firstAvailable(network, permitted, allowed);
                if (chosen == null) {
                    continue;
                }
                int limit = Math.max(1, stackLimit.applyAsInt(chosen));
                int taken = takeFromNetwork(network, chosen, limit, tags);
                if (taken <= 0) {
                    continue;
                }
                ItemStack placed = chosen.clone();
                placed.setAmount(taken);
                backpack.setItem(slot, placed);
                count(acc, chosen, taken, tags);
                continue;
            }

            if (!allowed.test(current) || !permitted.test(current)) {
                continue;
            }
            int limit = Math.max(1, stackLimit.applyAsInt(current));
            int room = limit - current.getAmount();
            if (room <= 0) {
                continue;
            }
            int taken = takeFromNetwork(network, current, room, tags);
            if (taken <= 0) {
                continue;
            }
            ItemStack grown = current.clone();
            grown.setAmount(current.getAmount() + taken);
            backpack.setItem(slot, grown);
            count(acc, current, taken, tags);
        }
        return build(acc);
    }

    /** 网络里按「容器正序 -> 槽位正序」找第一个命中过滤且还存在的物品，返回数量 1 的模板。 */
    private static ItemStack firstAvailable(List<Inventory> network,
                                            Predicate<ItemStack> permitted,
                                            Predicate<ItemStack> allowed) {
        for (Inventory inventory : network) {
            if (inventory == null) {
                continue;
            }
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (StorageFilter.isEmpty(stack)) {
                    continue;
                }
                if (!allowed.test(stack) || !permitted.test(stack)) {
                    continue;
                }
                ItemStack template = stack.clone();
                template.setAmount(1);
                return template;
            }
        }
        return null;
    }

    /**
     * 存入网络：先补所有容器里已有的同类非满堆叠，再使用空槽。
     * 单格上限用原版的 {@link ItemStack#getMaxStackSize()}（网络里都是原版箱子）。
     */
    private static int storeIntoNetwork(List<Inventory> network, ItemStack template,
                                        int amount, boolean tags) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        int remaining = amount;

        for (Inventory inventory : network) {
            if (inventory == null) {
                continue;
            }
            for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (StorageFilter.isEmpty(current)
                        || !StorageFilter.matches(current, template, tags)) {
                    continue;
                }
                int room = maxStack - current.getAmount();
                if (room <= 0) {
                    continue;
                }
                int add = Math.min(room, remaining);
                ItemStack grown = current.clone();
                grown.setAmount(current.getAmount() + add);
                inventory.setItem(slot, grown);
                remaining -= add;
            }
        }

        for (Inventory inventory : network) {
            if (inventory == null) {
                continue;
            }
            for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (!StorageFilter.isEmpty(current)) {
                    continue;
                }
                int add = Math.min(maxStack, remaining);
                ItemStack placed = template.clone();
                placed.setAmount(add);
                inventory.setItem(slot, placed);
                remaining -= add;
            }
        }
        return amount - remaining;
    }

    /** 从网络按「容器正序 -> 槽位正序」取出最多 wanted 个同类物品。 */
    private static int takeFromNetwork(List<Inventory> network, ItemStack template,
                                       int wanted, boolean tags) {
        int remaining = wanted;
        for (Inventory inventory : network) {
            if (inventory == null) {
                continue;
            }
            for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (StorageFilter.isEmpty(current)
                        || !StorageFilter.matches(current, template, tags)) {
                    continue;
                }
                int take = Math.min(remaining, current.getAmount());
                int left = current.getAmount() - take;
                if (left <= 0) {
                    inventory.setItem(slot, null);
                } else {
                    ItemStack rest = current.clone();
                    rest.setAmount(left);
                    inventory.setItem(slot, rest);
                }
                remaining -= take;
            }
        }
        return wanted - remaining;
    }

    /** 搬运明细累加器：保持首次遇到顺序，便于回执按类别列出。 */
    private static final class Acc {
        int moved = 0;
        boolean outOfSpace = false;
        final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        final LinkedHashMap<String, String> labels = new LinkedHashMap<>();
    }

    /** 累加某一种物品的搬运数量，并记录它的展示名（首次遇到时）。 */
    private static void count(Acc acc, ItemStack stack, int amount, boolean tags) {
        if (amount <= 0) {
            return;
        }
        acc.moved += amount;
        String key = kindKey(stack, tags);
        acc.counts.merge(key, amount, Integer::sum);
        acc.labels.putIfAbsent(key, StorageSnapshot.itemName(stack));
    }

    /** 把累加器汇总成不可变的 {@link Result}。重名的种类追加 #2 / #3 序号以便区分。 */
    private static Result build(Acc acc) {
        List<Result.Entry> entries = new ArrayList<>(acc.counts.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : acc.counts.entrySet()) {
            String base = acc.labels.get(entry.getKey());
            int n = seen.merge(base, 1, Integer::sum);
            String name = n == 1 ? base : base + " #" + n;
            entries.add(new Result.Entry(name, entry.getValue()));
        }
        return new Result(acc.moved, acc.counts.size(), acc.outOfSpace, entries);
    }

    /** 统计「种类」用的键；关闭标签匹配时只按材质区分。 */
    private static String kindKey(ItemStack stack, boolean tags) {
        if (!tags || stack.getItemMeta() == null) {
            return stack.getType().getKey().toString();
        }
        return stack.getType().getKey() + "|" + stack.getItemMeta().hashCode();
    }
}
