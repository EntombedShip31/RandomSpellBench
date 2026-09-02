package com.randomspellbench.spell;

import com.randomspellbench.RandomSpellPVP;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ISS 法术书目录。
 *
 * 只在 ISS 命名空间内收集 id 含 "spell_book" 的物品（如 irons_spellbooks:spell_book、
 * blaze_spell_book、netherite_spell_book 等），**绝不回退到原版书**。
 *
 * 扫描结果会被缓存：物品注册表在模组包里可能有上万项，
 * 每次分配都扫一遍既慢又会产生大量临时对象。
 */
public final class SpellbookCatalog {
    private static final String ISS_NAMESPACE = "irons_spellbooks";
    private static final String MARKER = "spell_book";

    private static List<Item> cached;

    private SpellbookCatalog() {
    }

    /** 全部可用的 ISS 法术书（按 id 排序，顺序稳定）。 */
    public static List<Item> get() {
        if (cached == null) {
            List<Item> books = new ArrayList<>();
            try {
                for (Map.Entry<net.minecraft.resources.ResourceKey<Item>, Item> entry
                        : ForgeRegistries.ITEMS.getEntries()) {
                    ResourceLocation id = entry.getKey().location();
                    if (!ISS_NAMESPACE.equals(id.getNamespace())) {
                        continue;
                    }
                    if (!id.getPath().contains(MARKER)) {
                        continue;
                    }
                    books.add(entry.getValue());
                }
            } catch (Throwable t) {
                RandomSpellPVP.LOGGER.error("Failed to scan ISS spellbook items", t);
            }
            // 稳定排序：保证同一包内每次顺序一致，便于复现
            books.sort(Comparator.comparing(item -> ForgeRegistries.ITEMS.getKey(item).toString()));
            cached = List.copyOf(books);
            RandomSpellPVP.LOGGER.info("SpellbookCatalog: found {} ISS spellbook item(s)", cached.size());
        }
        return cached;
    }

    /** 随机取一本 ISS 法术书；目录为空时返回 null。 */
    @Nullable
    public static Item random(RandomSource random) {
        List<Item> books = get();
        if (books.isEmpty()) {
            return null;
        }
        return books.get(random.nextInt(books.size()));
    }

    /** 缓存失效（服务器启动 / /rspvp reload 时调用）。 */
    public static void invalidate() {
        cached = null;
    }
}
