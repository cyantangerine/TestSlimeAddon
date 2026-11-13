package me.cyantangerine.slimefunaddon.model;

import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/**
 * 原版物品的简体中文名。
 *
 * <p>服务端只带 {@code assets/minecraft/lang/en_us.json}，没有中文语言文件；
 * 直接用 {@link Material#name()} 拼展示名会得到「DIAMOND」这种全大写英文名。
 * 所以把 Minecraft 26.3 官方 {@code zh_cn.json} 里的
 * {@code item.minecraft.*} / {@code block.minecraft.*} 抽成一张表随插件打包，
 * 运行时按材质的 namespaced key 查中文。</p>
 *
 * <p>方块材质优先查 {@code block.minecraft.<id>}，其余优先查
 * {@code item.minecraft.<id>}，两者都缺时换另一个前缀再试一次。
 * 表里没有（例如未来新增的物品）返回 {@code null}，由调用方回退到材质名。</p>
 *
 * <p>资源文件按 UTF-8（无 BOM）保存；读取时对 GBK 也做兼容，避免被编辑器
 * 另存成系统编码后整表乱码。</p>
 */
public final class VanillaItemName {

    private static final String RESOURCE = "lang/zh_cn_items.properties";
    private static final Properties NAMES = load();

    private VanillaItemName() {
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = VanillaItemName.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                // Properties.load(InputStream) 固定按 ISO-8859-1 读，中文必须自己按文本编码解码
                properties.load(new StringReader(decode(in.readAllBytes())));
            }
        } catch (IOException | RuntimeException ignored) {
            // 资源缺失 / 损坏时退回英文材质名，不能因为名字查不到就让界面打不开
        }
        return properties;
    }

    /**
     * 语言表统一按 UTF-8 保存，但出现过被 Windows 编辑器另存成 GBK / 带 BOM 的情况：
     * 先严格按 UTF-8 解码，遇到非法字节就退回 GB18030（GBK 超集），
     * 这样两种编码打进 jar 都能显示正确中文，不会整表变成问号。
     */
    private static String decode(byte[] bytes) {
        byte[] data = bytes;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            data = Arrays.copyOfRange(data, 3, data.length);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException ignored) {
            return new String(data, Charset.forName("GB18030"));
        }
    }

    /** 返回官方简体中文名；查不到时返回 {@code null}。 */
    public static String of(Material material) {
        if (material == null) {
            return null;
        }
        String id;
        boolean block;
        try {
            id = material.getKey().getKey();
            block = material.isBlock();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        String primary = (block ? "block.minecraft." : "item.minecraft.") + id;
        String name = NAMES.getProperty(primary);
        if (name == null) {
            name = NAMES.getProperty((block ? "item.minecraft." : "block.minecraft.") + id);
        }
        return (name == null || name.isEmpty()) ? null : name;
    }
}
