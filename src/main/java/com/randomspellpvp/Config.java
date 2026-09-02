package com.randomspellpvp;

import com.randomspellpvp.capability.AssignMode;
import com.randomspellpvp.capability.LevelMode;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组配置（ForgeConfigSpec）。
 *
 * 定位：创造模式下的「随机法术测试台」。
 * SERVER 类型用于随机规则、分配行为、测试点坐标、法术池过滤；
 * CLIENT 类型用于纯客户端交互选项。
 */
public final class Config {
    private Config() {
    }

    // ---------------- SERVER ----------------
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    // ---------------- CLIENT ----------------
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CLIENT = new Client(builder);
        CLIENT_SPEC = builder.build();
    }

    // ======================= 服务端配置 =======================
    public static final class Server {
        // -- 创造模式限制（全局开关：true = 任何模式都能用 /rspvp）--
        public final ForgeConfigSpec.BooleanValue bypassCreativeOnly;

        // -- 随机规则 --
        public final ForgeConfigSpec.IntValue maxSpells;          // 法术书槽位上限
        public final ForgeConfigSpec.IntValue defaultSpellCount;  // 默认随机数量
        public final ForgeConfigSpec.IntValue defaultMinLevel;
        public final ForgeConfigSpec.IntValue defaultMaxLevel;
        public final ForgeConfigSpec.EnumValue<AssignMode> defaultAssignMode;
        public final ForgeConfigSpec.EnumValue<LevelMode> defaultLevelMode;
        public final ForgeConfigSpec.IntValue defaultFixedLevel;
        public final ForgeConfigSpec.BooleanValue defaultMinOnePerSchool; // 默认「每学派至少 1 个」

        // -- 分配行为 --
        public final ForgeConfigSpec.BooleanValue clearInventory;      // 分配时是否清空背包（默认关）
        public final ForgeConfigSpec.ConfigValue<String> defaultSpellbook;
        public final ForgeConfigSpec.BooleanValue randomizeSpellbook;  // 从 ISS 法术书中随机挑一本
        public final ForgeConfigSpec.BooleanValue resetOnRandomize;    // 分配后回满血蓝/清冷却/buff
        public final ForgeConfigSpec.BooleanValue fillSpellbookSlots;  // 槽位固定为 maxSpells
        public final ForgeConfigSpec.BooleanValue appendToSpellbook;   // 追加到现有法术书而非替换
        public final ForgeConfigSpec.BooleanValue avoidRepeatLast;     // 尽量不抽到与上次相同的一批
        public final ForgeConfigSpec.BooleanValue showResultInChat;    // 新玩家的聊天栏播报默认值

        // -- 法术池 --
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> bannedSpells;   // 禁用法术 id
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> schoolWhitelist; // 学派白名单（空 = 不过滤），如 "eldritch"
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> spellWeights;   // "法术id=权重"

        Server(ForgeConfigSpec.Builder b) {
            b.comment("全局开关：true = 任何模式都能用本模组").push("access");
            bypassCreativeOnly = b.define("bypassCreativeOnly", false);
            b.pop();

            b.comment("随机分配规则").push("random");
            maxSpells = b.defineInRange("maxSpells", 12, 1, 16);
            defaultSpellCount = b.defineInRange("defaultSpellCount", 6, 1, 16);
            defaultMinLevel = b.defineInRange("defaultMinLevel", 1, 1, 20);
            defaultMaxLevel = b.defineInRange("defaultMaxLevel", 10, 1, 20);
            defaultAssignMode = b.defineEnum("defaultAssignMode", AssignMode.RANDOM);
            defaultLevelMode = b.defineEnum("defaultLevelMode", LevelMode.RANGE);
            defaultFixedLevel = b.defineInRange("defaultFixedLevel", 10, 1, 20);
            defaultMinOnePerSchool = b.define("defaultMinOnePerSchool", false);
            b.pop();

            b.comment("分配行为").push("assignment");
            clearInventory = b.define("clearInventory", false);
            defaultSpellbook = b.define("defaultSpellbook", "irons_spellbooks:spell_book");
            randomizeSpellbook = b.define("randomizeSpellbook", true);
            resetOnRandomize = b.define("resetOnRandomize", true);
            fillSpellbookSlots = b.define("fillSpellbookSlots", false);
            appendToSpellbook = b.define("appendToSpellbook", false);
            avoidRepeatLast = b.define("avoidRepeatLast", false);
            showResultInChat = b.define("showResultInChat", true);
            b.pop();

            b.comment("法术池过滤").push("spells");
            bannedSpells = b.defineList("bannedSpells", List.of(), o -> o instanceof String);
            schoolWhitelist = b.defineList("schoolWhitelist", List.of(), o -> o instanceof String);
            spellWeights = b.defineList("spellWeights", List.of(), o -> o instanceof String);
            b.pop();
        }
    }

    // ======================= 客户端配置 =======================
    public static final class Client {
        public final ForgeConfigSpec.BooleanValue closeScreenAfterRandomize;
        public final ForgeConfigSpec.BooleanValue pauseScreen;

        Client(ForgeConfigSpec.Builder b) {
            b.comment("界面交互").push("ui");
            closeScreenAfterRandomize = b.define("closeScreenAfterRandomize", true);
            pauseScreen = b.define("pauseScreen", true);
            b.pop();
        }
    }

    // ======================= 便捷访问 =======================

    public static boolean isSpellBanned(String spellId) {
        return SERVER.bannedSpells.get().contains(spellId);
    }

    /**
     * 法术权重缓存。配权重表读取是 O(n) 的线性扫描，
     * 而每次分配会对每个候选法术都查一次，缓存后避免在热路径上反复扫描配置。
     */
    private static final Map<String, Integer> weightCache = new HashMap<>();

    public static int getSpellWeight(String spellId) {
        Integer cached = weightCache.get(spellId);
        if (cached != null) {
            return cached;
        }
        int weight = 1;
        for (String entry : SERVER.spellWeights.get()) {
            int eq = entry.indexOf('=');
            if (eq > 0 && entry.substring(0, eq).trim().equals(spellId)) {
                try {
                    weight = Math.max(0, Integer.parseInt(entry.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                }
                break;
            }
        }
        weightCache.put(spellId, weight);
        return weight;
    }

    /** 配置重载后清空权重缓存。 */
    public static void invalidateWeightCache() {
        weightCache.clear();
    }
}
