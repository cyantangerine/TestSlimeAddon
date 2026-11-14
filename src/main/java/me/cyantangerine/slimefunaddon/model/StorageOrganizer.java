package me.cyantangerine.slimefunaddon.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能存储交互终端菜单里的三种「整理」操作。
 *
 * <p>全部基于 {@link StorageNetwork.Network#containers} 的缓存 {@link Inventory}，
 * 顺序就是「距离升序 → 方向序 → 坐标」；下标越小代表容器离接口越近。
 * 读取时把每格 {@link ItemStack} clone 成内存数组（{@code getItem} 返回的是
 * 直接指向方块实体的 mirror，必须先 clone 再改数量），算完只把内容真的变了的
 * 槽位写回去。所有物品只会在容器之间<b>向更近的方向</b>移动，不会凭空增减。</p>
 *
 * <p><b>容器内整理</b>：逐容器把同类物品合并成尽可能少的满堆叠，并从 0 号槽
 * 开始紧凑排布；物品绝不跨容器。</p>
 *
 * <p><b>跨容器整理</b>：先做容器内整理，再把「不足一组」的堆叠从远容器往
 * 近容器里已有的<b>同类非满堆叠</b>里补满。严格不新增槽位占用：
 * 目标容器必须本来就有该物品的非满堆叠，绝不往空槽里建新堆叠，也不拆远容器
 * 里已经满的堆叠。因此「近容器同类都已满、但还有空槽」时物品留在原地。</p>
 *
 * <p><b>压缩整理</b>：先做容器内整理，然后近→远依次把每个容器尽量填满，
 * 优先补已有的非满堆叠、再允许使用空槽，来源一律先从最远容器取（可拆满堆叠）。
 * 也就是无视跨容器整理那条「不新增槽位」的限制。</p>
 */
public final class StorageOrganizer {

    /** 一次整理的结果：参与容器数、释放的槽位数、跨容器搬运的物品个数。 */
    public record Result(int containerCount, int freedSlots, int movedItems) { }

    private StorageOrganizer() {
    }

    /** 整理物品（每个容器内）：只在各自容器里堆叠、压紧。 */
    public static Result withinContainers(StorageNetwork.Network network) {
        State state = State.read(network);
        for (ItemStack[] slots : state.slots) {
            compactWithin(slots);
        }
        return state.commit();
    }

    /** 整理物品（跨容器）：容器内堆叠 + 只往已有非满堆叠里补，绝不新增槽位占用。 */
    public static Result acrossContainers(StorageNetwork.Network network) {
        State state = State.read(network);
        for (ItemStack[] slots : state.slots) {
            compactWithin(slots);
        }
        state.mergePartials();
        // 收尾再压紧一次：跨容器搬运可能让某些容器中间出现空洞
        for (ItemStack[] slots : state.slots) {
            compactWithin(slots);
        }
        return state.commit();
    }

    /** 压缩整理：容器内堆叠 + 尽量把近处容器填满（允许使用空槽）。 */
    public static Result compress(StorageNetwork.Network network) {
        State state = State.read(network);
        for (ItemStack[] slots : state.slots) {
            compactWithin(slots);
        }
        state.compress();
        for (ItemStack[] slots : state.slots) {
            compactWithin(slots);
        }
        return state.commit();
    }

    // ------------------------------------------------------------------
    // 内存状态
    // ------------------------------------------------------------------

    private static final class State {
        private final List<StorageNetwork.ContainerRef> containers = new ArrayList<>();
        /** 当前布局，元素是可随意修改的 clone。 */
        private final List<ItemStack[]> slots = new ArrayList<>();
        /** 原始布局，写回时用于 diff 与失败回滚。 */
        private final List<ItemStack[]> originals = new ArrayList<>();
        private int occupiedBefore;
        private int movedItems;

        private static State read(StorageNetwork.Network network) {
            State state = new State();
            if (network == null) {
                return state;
            }
            // 发现阶段已按 inventory 位置去重，这里再挡一次，避免大箱子两半被写两遍
            Set<String> seen = new HashSet<>();
            for (StorageNetwork.ContainerRef container : network.containers) {
                if (container == null || container.inventory == null
                        || !seen.add(container.key)) {
                    continue;
                }
                try {
                    Inventory inventory = container.inventory;
                    int size = inventory.getSize();
                    ItemStack[] current = new ItemStack[size];
                    ItemStack[] original = new ItemStack[size];
                    for (int slot = 0; slot < size; slot++) {
                        ItemStack stack = clean(inventory.getItem(slot));
                        current[slot] = stack;
                        original[slot] = stack == null ? null : stack.clone();
                    }
                    state.containers.add(container);
                    state.slots.add(current);
                    state.originals.add(original);
                    state.occupiedBefore += occupied(current);
                } catch (RuntimeException | LinkageError ex) {
                    Bukkit.getLogger().warning("[CyanTestAddon] 智能存储整理：读取容器 "
                            + container.label + " 失败，已跳过。");
                }
            }
            return state;
        }

        /** 只写回真正变了的槽位；单个容器写失败时整箱回滚到原始布局。 */
        private Result commit() {
            int occupiedAfter = 0;
            for (int c = 0; c < containers.size(); c++) {
                ItemStack[] current = slots.get(c);
                ItemStack[] original = originals.get(c);
                Inventory inventory = containers.get(c).inventory;
                boolean failed = false;
                for (int slot = 0; slot < current.length; slot++) {
                    if (identical(current[slot], original[slot])) {
                        continue;
                    }
                    try {
                        inventory.setItem(slot, current[slot] == null
                                ? null : current[slot].clone());
                    } catch (RuntimeException | LinkageError ex) {
                        failed = true;
                        Bukkit.getLogger().warning("[CyanTestAddon] 智能存储整理：写入容器 "
                                + containers.get(c).label + " 失败，已回滚该容器。");
                        break;
                    }
                }
                if (failed) {
                    for (int slot = 0; slot < original.length; slot++) {
                        try {
                            inventory.setItem(slot, original[slot] == null
                                    ? null : original[slot].clone());
                        } catch (RuntimeException | LinkageError ignored) {
                            // 已经尽力回滚
                        }
                    }
                    ItemStack[] reverted = new ItemStack[original.length];
                    for (int slot = 0; slot < original.length; slot++) {
                        reverted[slot] = original[slot] == null ? null : original[slot].clone();
                    }
                    slots.set(c, reverted);
                    occupiedAfter += occupied(reverted);
                } else {
                    occupiedAfter += occupied(current);
                }
            }
            return new Result(containers.size(),
                    Math.max(0, occupiedBefore - occupiedAfter), movedItems);
        }

        /**
         * 跨容器整理：对每种物品，按近→远找「已有的非满堆叠」当目标，
         * 再按远→近从其它容器的「不足一组」堆叠里搬过来补满。
         */
        private void mergePartials() {
            int count = slots.size();
            for (ItemStack type : types()) {
                int max = maxStack(type);
                if (max <= 1) {
                    continue; // 不可堆叠物品没有「不足一组」的概念
                }
                for (int dest = 0; dest < count; dest++) {
                    ItemStack[] destination = slots.get(dest);
                    int destSlot = firstPartial(destination, type, max);
                    if (destSlot < 0) {
                        continue;
                    }
                    int need = max - destination[destSlot].getAmount();
                    // 来源只能是更远的容器，且槽位倒序（与界面取出顺序一致）
                    for (int source = count - 1; source > dest && need > 0; source--) {
                        ItemStack[] sourceSlots = slots.get(source);
                        for (int slot = sourceSlots.length - 1; slot >= 0 && need > 0; slot--) {
                            ItemStack stack = sourceSlots[slot];
                            if (stack == null || !stack.isSimilar(type)) {
                                continue;
                            }
                            if (stack.getAmount() >= max) {
                                // 远容器里已经堆满的不拆，只搬不足一组的
                                continue;
                            }
                            int take = Math.min(need, stack.getAmount());
                            if (take <= 0) {
                                continue;
                            }
                            int left = stack.getAmount() - take;
                            if (left <= 0) {
                                sourceSlots[slot] = null;
                            } else {
                                stack.setAmount(left);
                            }
                            destination[destSlot].setAmount(
                                    destination[destSlot].getAmount() + take);
                            need -= take;
                            movedItems += take;
                        }
                    }
                }
            }
        }

        /** 压缩整理：近→远依次把每个容器尽量填满，来源优先最远容器。 */
        private void compress() {
            int count = slots.size();
            for (int dest = 0; dest < count - 1; dest++) {
                ItemStack[] destination = slots.get(dest);

                // 1) 先补满容器内已有的非满堆叠，不占用任何空槽
                for (int slot = 0; slot < destination.length; slot++) {
                    ItemStack stack = destination[slot];
                    if (stack == null) {
                        continue;
                    }
                    int max = maxStack(stack);
                    int need = max - stack.getAmount();
                    if (need <= 0) {
                        continue;
                    }
                    stack.setAmount(stack.getAmount() + pull(dest, stack, need));
                }

                // 2) 再允许使用空槽：从最远容器挑物品，尽量凑成一整组搬过来
                for (int slot = 0; slot < destination.length; slot++) {
                    if (destination[slot] != null) {
                        continue;
                    }
                    ItemStack type = farthestItem(dest);
                    if (type == null) {
                        break;
                    }
                    int pulled = pull(dest, type, maxStack(type));
                    if (pulled <= 0) {
                        break;
                    }
                    ItemStack placed = type.clone();
                    placed.setAmount(pulled);
                    destination[slot] = placed;
                }
            }
        }

        /**
         * 从所有比 {@code dest} 更远的容器里取同类物品，最多 {@code need} 个：
         * 容器远→近、槽位倒序。返回实际取到的数量（同时扣减来源）。
         */
        private int pull(int dest, ItemStack template, int need) {
            int pulled = 0;
            for (int source = slots.size() - 1; source > dest && need > 0; source--) {
                ItemStack[] sourceSlots = slots.get(source);
                for (int slot = sourceSlots.length - 1; slot >= 0 && need > 0; slot--) {
                    ItemStack stack = sourceSlots[slot];
                    if (stack == null || !stack.isSimilar(template)) {
                        continue;
                    }
                    int take = Math.min(need, stack.getAmount());
                    if (take <= 0) {
                        continue;
                    }
                    int left = stack.getAmount() - take;
                    if (left <= 0) {
                        sourceSlots[slot] = null;
                    } else {
                        stack.setAmount(left);
                    }
                    need -= take;
                    pulled += take;
                    movedItems += take;
                }
            }
            return pulled;
        }

        /** 最远容器里（槽位倒序）还剩下的任意一个物品类型。 */
        private ItemStack farthestItem(int dest) {
            for (int source = slots.size() - 1; source > dest; source--) {
                ItemStack[] sourceSlots = slots.get(source);
                for (int slot = sourceSlots.length - 1; slot >= 0; slot--) {
                    ItemStack stack = sourceSlots[slot];
                    if (stack != null) {
                        ItemStack type = stack.clone();
                        type.setAmount(1);
                        return type;
                    }
                }
            }
            return null;
        }

        /** 按「容器近→远、槽位正序」收集所有去重后的物品类型，顺序稳定。 */
        private List<ItemStack> types() {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack[] container : slots) {
                for (ItemStack stack : container) {
                    if (stack == null || containsSimilar(result, stack)) {
                        continue;
                    }
                    ItemStack type = stack.clone();
                    type.setAmount(1);
                    result.add(type);
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // 单容器堆叠 / 工具方法
    // ------------------------------------------------------------------

    /** 在单个容器内把同类物品合并成尽量少的满堆叠，从 0 号槽紧凑排布。 */
    private static void compactWithin(ItemStack[] slots) {
        List<ItemStack> templates = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();
        for (ItemStack stack : slots) {
            if (stack == null) {
                continue;
            }
            int index = -1;
            for (int i = 0; i < templates.size(); i++) {
                if (templates.get(i).isSimilar(stack)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                ItemStack template = stack.clone();
                template.setAmount(1);
                templates.add(template);
                totals.add(stack.getAmount());
            } else {
                totals.set(index, totals.get(index) + stack.getAmount());
            }
        }

        Arrays.fill(slots, null);
        int write = 0;
        for (int group = 0; group < templates.size(); group++) {
            ItemStack template = templates.get(group);
            int max = maxStack(template);
            int remaining = totals.get(group);
            int lastWrite = -1;
            while (remaining > 0) {
                int amount = Math.min(remaining, max);
                if (write < slots.length) {
                    ItemStack stack = template.clone();
                    stack.setAmount(amount);
                    slots[write] = stack;
                    lastWrite = write++;
                } else if (lastWrite >= 0) {
                    // 防御：源容器里若有超出上限的非法堆叠，也不能把物品弄丢
                    ItemStack stack = slots[lastWrite];
                    stack.setAmount(stack.getAmount() + amount);
                } else {
                    break;
                }
                remaining -= amount;
            }
        }
    }

    /** 容器内第一个「同类且未满」的槽位；没有则返回 -1。 */
    private static int firstPartial(ItemStack[] slots, ItemStack template, int max) {
        for (int slot = 0; slot < slots.length; slot++) {
            ItemStack stack = slots[slot];
            if (stack != null && stack.isSimilar(template) && stack.getAmount() < max) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean containsSimilar(List<ItemStack> templates, ItemStack stack) {
        for (ItemStack template : templates) {
            if (template.isSimilar(stack)) {
                return true;
            }
        }
        return false;
    }

    private static int maxStack(ItemStack stack) {
        return Math.max(1, stack.getMaxStackSize());
    }

    private static int occupied(ItemStack[] slots) {
        int count = 0;
        for (ItemStack stack : slots) {
            if (stack != null) {
                count++;
            }
        }
        return count;
    }

    private static boolean identical(ItemStack a, ItemStack b) {
        return a == b || (a != null && b != null && a.equals(b));
    }

    private static ItemStack clean(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        return stack.clone();
    }
}
