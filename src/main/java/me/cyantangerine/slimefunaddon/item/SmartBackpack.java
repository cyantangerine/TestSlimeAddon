package me.cyantangerine.slimefunaddon.item;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.cyantangerine.slimefunaddon.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * 通用智能背包：与 {@link SmartCooler} 共用 {@link SmartContainer} 的装载、快捷存入与
 * 容量可视化逻辑，但不再只装果汁，也不额外限制物品种类 —— 沿用 Slimefun 背包的默认过滤
 * （潜影盒、其它背包不能套娃）。
 *
 * <p>和冰柜唯一的关键区别是单格堆叠上限系数：{@link #STACK_MULTIPLIER} = 4，
 * 即「min(64, 物品原版最大堆叠数 × 4)」，玻璃瓶 / 药水 4 个一格、末影珍珠 / 雪球
 * 64 个一格；原本就能堆 64 的物品维持 64。批量装载与快捷存入都会先合并同类部分堆叠，
 * 再使用空槽。</p>
 *
 * <p>上限封顶 64 是实测结论：Bukkit 的 {@code setAmount()} 不校验，能写出 256，
 * 但客户端不认这种非法堆叠，GUI 同步 / 点击 / 存档往返后整叠会消失；而且 1.20.5+ 的
 * {@code max_stack_size} 组件合法范围只有 1..99。背包 GUI 里的原版点击仍按 vanilla
 * maxStackSize 合并，插件只在自己的装载路径里执行这个上限。</p>
 */
public class SmartBackpack extends SmartContainer {

    /** GUI 格数、Lore 进度条分母、占位物映射，三者统一用这个值。 */
    public static final int CAPACITY = 54;
    public static final int DEFAULT_SIZE = CAPACITY;
    /** 堆叠上限系数：智能背包 = 4 倍原版堆叠，封顶 {@link SmartContainer#VANILLA_MAX_STACK_SIZE}。 */
    public static final int STACK_MULTIPLIER = 1;

    /** 未命名时盖住原版收纳袋 tooltip 的满权重占位物（ICE 最大堆叠 64）。 */
    private static final Material DEFAULT_PLACEHOLDER = Material.ICE;
    /**
     * 容量条占位物。用 maxStackSize = 64 的 PAPER：1.19.2 走 {@code createFiller} 的
     * 64 权重映射时分母正好是 64；1.20.5+ 由父类反射写入 max_stack_size 组件，
     * 分母改用 CAPACITY，材质不再影响权重。两条路径都画出 used / CAPACITY。
     */
    private static final Material PLACEHOLDER = Material.PAPER;

    @ParametersAreNonnullByDefault
    public SmartBackpack(int size, ItemGroup itemGroup, SlimefunItemStack item,
                         RecipeType recipeType, ItemStack[] recipe) {
        super(size, itemGroup, item, recipeType, recipe);
    }

    @Override
    public void preRegister() {
        super.preRegister();
        // SmartContainer 只实现了 Listener，不会自己注册：每个具体物品都要挂一份事件。
        Bukkit.getPluginManager().registerEvents(this, getPlugin());
    }

    @Override
    public Material getDefaultPlaceholder() {
        return DEFAULT_PLACEHOLDER;
    }

    @Override
    public Material getPlaceholder() {
        return PLACEHOLDER;
    }

    @Override
    protected int getCapacity() {
        return CAPACITY;
    }

    @Override
    protected int getStackMultiplier() {
        return STACK_MULTIPLIER;
    }

    /**
     * 不额外限制种类：沿用 {@code SlimefunBackpack#isItemAllowed} 的默认规则
     * （拒绝潜影盒与其它背包，避免套娃），其余物品都能装。
     */
    @Override
    public boolean isItemAllowed(ItemStack item, SlimefunItem itemAsSlimefunItem) {
        return super.isItemAllowed(item, itemAsSlimefunItem);
    }

    @Override
    protected boolean isOurContainer(ItemStack item) {
        return Utils.isOurDevice(item, this);
    }

    public static SlimefunItemStack createItemStack() {
        return SmartContainer.createItemStack(
                "SMART_BACKPACK",
                "&a智能背包",
                () -> new ItemStack(DEFAULT_PLACEHOLDER, SmartContainer.LEGACY_BUNDLE_MAX_WEIGHT),
                "可装载绝大多数物品（潜影盒 / 背包除外）",
                // "&7单格堆叠上限 = 物品原版上限 × &e" + STACK_MULTIPLIER + " &7(最高 64)",
                "&7手持右键容器可批量装载，自动合并同类堆叠",
                "&7在物品栏右键即可打开，容量可视化",
                "&7容量: &e" + CAPACITY + " 格",
                LoreBuilder.RIGHT_CLICK_TO_OPEN
        );
    }
}
