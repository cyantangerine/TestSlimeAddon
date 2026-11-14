package me.cyantangerine.slimefunaddon;

import io.github.thebusybiscuit.slimefun4.core.attributes.Radioactivity;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.cyantangerine.slimefunaddon.item.FireCake;
import me.cyantangerine.slimefunaddon.item.SmartBackpack;
import me.cyantangerine.slimefunaddon.item.SmartCooler;
import me.cyantangerine.slimefunaddon.item.SmartStorageConnector;
import me.cyantangerine.slimefunaddon.item.SmartStorageInterface;
import me.cyantangerine.slimefunaddon.item.SmartStorageTransferTerminal;
import me.cyantangerine.slimefunaddon.item.SmartStorageWirelessTerminal;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.libraries.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;

import java.util.logging.Level;

public class CyanTestAddon extends JavaPlugin implements SlimefunAddon {
    private ResourceLib resourceLib;
    public ResourceLib getResourceLib() {
        return resourceLib;
    }

    @Override
    public void onEnable() {
        resourceLib = new ResourceLib(this);
        // 从 config.yml 中读取插件配置
        Config cfg = resourceLib.getConfig();
        if (cfg.getBoolean("options.auto-update")) {
            // 你可以在这里添加自动更新功能
        }

//        NamespacedKey categoryId = new NamespacedKey(this, "cool_category");
//        CustomItemStack categoryItem = new CustomItemStack(Material.DIAMOND, "&4非常炫酷的分类");
//// 创建物品组 ItemGroup
//        ItemGroup itemGroup = new ItemGroup(categoryId, categoryItem);
//// 粘液物品 SlimefunItem 的物品 SlimefunItemStack
//        SlimefunItemStack itemStack = new SlimefunItemStack("FIRE_CAKE", Material.CAKE, "&4火焰蛋糕", "", LoreBuilder.radioactive(Radioactivity.HIGH), LoreBuilder.HAZMAT_SUIT_REQUIRED);
//// 3x3 的有序合成配方
//        ItemStack[] recipe = {
//                new ItemStack(Material.DIAMOND),    null,                               new ItemStack(Material.DIAMOND),
//                null,                               SlimefunItems.CARBONADO,            null,
//                new ItemStack(Material.DIAMOND),    null,                               new ItemStack(Material.DIAMOND)
//        };
//// 我们现在正在使用自己的类
//        FireCake cake = new FireCake(itemGroup, itemStack, RecipeType.ENHANCED_CRAFTING_TABLE, recipe);
//        cake.register(this);


        NamespacedKey category2Id = new NamespacedKey(this, "smart_devices");
        CustomItemStack category2Item = new CustomItemStack(SmartCooler.HEAD_ICON, "&4智能设备");
// 创建物品组 ItemGroup
        ItemGroup item2Group = new ItemGroup(category2Id, category2Item);

        // 可视小冰柜：物品材质为 BUNDLE，容量会像收纳袋一样画在图标上。
        // 中心用官方 COOLER 升级，产物会继承旧背包（Slimefun 的 upgradeBackpack 语义）。
        // 这条路径在 Folia 上曾因回调落在全局线程而报 "Cannot read world asynchronously"，
        // 已在本地 Slimefun fork 的 AbstractCraftingTable 中修复，请勿为了绕开它而改配方。
        ItemStack[] coolerRecipe = {
                SlimefunItems.CLOTH, SlimefunItems.SYNTHETIC_SAPPHIRE, SlimefunItems.CLOTH,
                SlimefunItems.SOLDER_INGOT, SlimefunItems.COOLER, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.GOLD_16K, SlimefunItems.ADVANCED_CIRCUIT_BOARD, SlimefunItems.GOLD_16K
        };
        SmartCooler cooler = new SmartCooler(
                SmartCooler.DEFAULT_SIZE, item2Group, SmartCooler.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, coolerRecipe
        );
        cooler.register(this);

        // 智能背包：与冰柜共用 SmartContainer，只把堆叠上限系数从 1 提到 4。
        // 配方中心用 Slimefun 最高级背包（辐射背包 RADIANT_BACKPACK，54 格），
        // 因此会走 AbstractCraftingTable#upgradeBackpack 的「继承输入背包」语义：
        // 合成时会把中心那个辐射背包里已有的物品继承到新的智能背包里。
        // 这条路径在本地 Slimefun fork 的 AbstractCraftingTable 中已修复（Folia 上不炸），
        // 与冰柜中心用 COOLER 升级是同一套机制。
        ItemStack[] backpackRecipe = {
                SlimefunItems.CLOTH, SlimefunItems.SYNTHETIC_SAPPHIRE, SlimefunItems.CLOTH,
                SlimefunItems.SOLDER_INGOT, SlimefunItems.RADIANT_BACKPACK, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.GOLD_16K, SlimefunItems.ADVANCED_CIRCUIT_BOARD, SlimefunItems.GOLD_16K
        };
        SmartBackpack backpack = new SmartBackpack(
                SmartBackpack.DEFAULT_SIZE, item2Group, SmartBackpack.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, backpackRecipe
        );
        backpack.register(this);

        // ------------------------------------------------------------------
        // 智能存储：连接器负责泛洪成网络，交互接口负责把网络里的容器聚合成一个分页 GUI。
        // 两者都不是 SlimefunBackpack，配方里也不放任何背包物品，避免走
        // AbstractCraftingTable#upgradeBackpack 的「继承输入背包」语义（Folia 上会炸）。
        // 连接器配方：7 个焊锡锭包裹漏斗，底部为电动马达；外观末地烛不变，一次合成 8 个。
        // ------------------------------------------------------------------
        ItemStack[] connectorRecipe = {
                SlimefunItems.SOLDER_INGOT, SlimefunItems.SOLDER_INGOT, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.SOLDER_INGOT, new ItemStack(Material.HOPPER), SlimefunItems.SOLDER_INGOT,
                SlimefunItems.SOLDER_INGOT, SlimefunItems.ELECTRIC_MOTOR, SlimefunItems.SOLDER_INGOT
        };
        SmartStorageConnector connector = new SmartStorageConnector(
                item2Group, SmartStorageConnector.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, connectorRecipe
        );
        // 一次合成 8 个：把「配方产出」单独设成 8 份，而不是改物品模板的数量。
        // 物品模板保持 1 个（破坏方块时只掉 1 个），否则「放置 1 个 -> 破坏掉 8 个」可无限刷。
        // Slimefun 在 load() 里用 getRecipeOutput() 注册配方产出，必须在 register() 之前设置。
        connector.setRecipeOutput(new SlimefunItemStack(
                SmartStorageConnector.createItemStack(), 8));
        connector.register(this);

        ItemStack[] interfaceRecipe = {
                SlimefunItems.SOLDER_INGOT, SlimefunItems.ADVANCED_CIRCUIT_BOARD, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.CARGO_MOTOR, new ItemStack(Material.OBSERVER), SlimefunItems.CARGO_MOTOR,
                SlimefunItems.GOLD_24K, SlimefunItems.ELECTRIC_MOTOR, SlimefunItems.GOLD_24K
        };
        SmartStorageInterface storageInterface = new SmartStorageInterface(
                item2Group, SmartStorageInterface.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, interfaceRecipe, connector
        );
        storageInterface.register(this);
        // 让连接器在计算可视化链的入口时能认出交互终端
        connector.setInterfaceItem(storageInterface);

        // ------------------------------------------------------------------
        // 背包卸载 / 加载终端：同样是「可放置方块 + 相邻连接器泛洪出容器网络」。
        // 手持智能背包 / 冰柜右键执行搬运，空手右键打开 9×6 配置页。
        // ------------------------------------------------------------------
        ItemStack[] unloadRecipe = {
                SlimefunItems.BRASS_INGOT, SlimefunItems.BASIC_CIRCUIT_BOARD, SlimefunItems.BRASS_INGOT,
                SlimefunItems.ELECTRIC_MOTOR, new ItemStack(Material.LODESTONE), SlimefunItems.ELECTRIC_MOTOR,
                SlimefunItems.GOLD_24K, SlimefunItems.CARGO_MOTOR, SlimefunItems.GOLD_24K
        };
        SmartStorageTransferTerminal unloadTerminal = new SmartStorageTransferTerminal(
                SmartStorageTransferTerminal.Kind.UNLOAD, item2Group,
                SmartStorageTransferTerminal.createItemStack(SmartStorageTransferTerminal.Kind.UNLOAD),
                RecipeType.ENHANCED_CRAFTING_TABLE, unloadRecipe, connector, storageInterface
        );
        unloadTerminal.register(this);

        ItemStack[] loadRecipe = {
                SlimefunItems.BILLON_INGOT, SlimefunItems.BASIC_CIRCUIT_BOARD, SlimefunItems.BILLON_INGOT,
                SlimefunItems.ELECTRIC_MOTOR, new ItemStack(Material.DROPPER), SlimefunItems.ELECTRIC_MOTOR,
                SlimefunItems.GOLD_24K, SlimefunItems.CARGO_MOTOR, SlimefunItems.GOLD_24K
        };
        SmartStorageTransferTerminal loadTerminal = new SmartStorageTransferTerminal(
                SmartStorageTransferTerminal.Kind.LOAD, item2Group,
                SmartStorageTransferTerminal.createItemStack(SmartStorageTransferTerminal.Kind.LOAD),
                RecipeType.ENHANCED_CRAFTING_TABLE, loadRecipe, connector, storageInterface
        );
        loadTerminal.register(this);

        // 无线交互终端：不可放置，右键已放置的交互终端输入名称完成绑定。
        ItemStack[] wirelessRecipe = {
                SlimefunItems.SOLDER_INGOT, SlimefunItems.ADVANCED_CIRCUIT_BOARD, SlimefunItems.SOLDER_INGOT,
                SlimefunItems.CARGO_MOTOR, new ItemStack(Material.ENDER_EYE), SlimefunItems.CARGO_MOTOR,
                SlimefunItems.GOLD_24K, new ItemStack(Material.END_ROD), SlimefunItems.GOLD_24K
        };
        SmartStorageWirelessTerminal wirelessTerminal = new SmartStorageWirelessTerminal(
                item2Group, SmartStorageWirelessTerminal.createItemStack(),
                RecipeType.ENHANCED_CRAFTING_TABLE, wirelessRecipe, storageInterface
        );
        wirelessTerminal.register(this);

        getLogger().log(Level.WARNING, "cyan 插件已启用！");
    }

    @Override
    public void onDisable() {
        resourceLib.getFoliaLib().getScheduler().cancelAllTasks();
        // 禁用插件的逻辑...
        getLogger().log(Level.WARNING, "cyan 插件已禁用！");
    }

    @Override
    public String getBugTrackerURL() {
        // 你可以在这里返回你的问题追踪器的网址，而不是 null
        return null;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        /*
         * 你需要返回对你插件的引用。
         * 如果这是你插件的主类，只需要返回 "this" 即可。
         */
        return this;
    }

}
