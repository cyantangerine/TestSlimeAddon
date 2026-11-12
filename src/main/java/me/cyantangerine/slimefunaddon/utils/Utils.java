package me.cyantangerine.slimefunaddon.utils;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.implementation.items.backpacks.SlimefunBackpack;
import me.cyantangerine.slimefunaddon.CyanTestAddon;
import me.cyantangerine.slimefunaddon.model.ResourceLib;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class Utils {
    public static ResourceLib initialCheck(Plugin plugin) {
        try {
            PlayerBackpack.class.getMethod("getAsync", ItemStack.class);
        } catch (NoSuchMethodException ignored) {
            throw new VerifyError("不支持的版本 至少使用 Slimefun RC-36 及以上");
        }

        if (plugin instanceof CyanTestAddon addon) {
            return addon.getResourceLib();
        }
        throw new RuntimeException("错误：该组件必须在插件中运行");
    }

    public static boolean isContainerBlock(Block block) {
        try {
            if (block == null) return false;
            return isContainer(block.getType());
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    public static boolean isContainer(Material type) {
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DISPENSER, DROPPER -> true;
            default -> Tag.SHULKER_BOXES.isTagged(type);
        };
    }
    public static boolean isOurDevice(ItemStack item, SlimefunBackpack target) {
        return item != null && SlimefunItem.getByItem(item) == target;
    }

    public static int clamp(int used, int capacity) {
        return Math.max(0, Math.min(capacity, used));
    }
}
