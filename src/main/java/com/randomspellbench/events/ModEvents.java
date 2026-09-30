package com.randomspellbench.events;

import com.randomspellbench.Config;
import com.randomspellbench.RandomSpellPVP;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.network.NetworkHandler;
import com.randomspellbench.network.packet.S2CSyncConfigPacket;
import com.randomspellbench.spell.SpellPoolManager;
import com.randomspellbench.spell.SpellbookCatalog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 通用（Forge 总线）事件：
 * - 登录/重生/跨维度时向客户端同步配置
 * - 服务器启动时刷新法术池与法术书目录缓存
 * 玩家配置使用 Player#getPersistentData() 存取，无需 Capability 附加流程。
 *
 * 注意：必须带 @Mod.EventBusSubscriber 才会自动注册，否则所有监听全部失效
 * （此前缺少注解导致客户端收不到配置同步包，GUI 与服务端状态不一致）。
 */
@Mod.EventBusSubscriber(modid = RandomSpellPVP.MODID)
public final class ModEvents {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        syncConfig(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        syncConfig(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        syncConfig(event.getEntity());
    }

    private static void syncConfig(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHandler.sendToPlayer(new S2CSyncConfigPacket(
                    PlayerConfigStore.get(serverPlayer).serializeNBT(),
                    Config.SERVER.maxSpells.get(),
                    new java.util.ArrayList<>(Config.SERVER.bannedSpells.get()),
                    new java.util.ArrayList<>(Config.SERVER.schoolWhitelist.get())), serverPlayer);
        }
    }

    /**
     * 「法术无冷却」（测试开关）：开启的玩家每 10 tick（0.5 秒）清空一次 ISS 冷却。
     *
     * <p>开关状态在玩家配置 NBT 里（{@code PlayerSpellConfig.isNoCooldown()}），
     * 关闭即恢复原版冷却，无残留状态；每 0.5 秒一次足够「冷却永远为零」的观感，
     * 且避免逐 tick 空转。 ISS 的冷却入口与 {@code ClearCooldownCommand} 相同：
     * {@code MagicData.getPlayerMagicData(p).getPlayerCooldowns()} 的 clear + syncToPlayer，
     * 全部位于 ISS api 包，可直接调用（免 Mixin / 反射）。</p>
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!player.isAlive() || player.tickCount % 10 != 0) {
            return;
        }
        if (!PlayerConfigStore.get(player).isNoCooldown()) {
            return;
        }
        io.redspace.ironsspellbooks.api.magic.MagicData magicData =
                io.redspace.ironsspellbooks.api.magic.MagicData.getPlayerMagicData(player);
        magicData.getPlayerCooldowns().clearCooldowns();
        magicData.getPlayerCooldowns().syncToPlayer(player);
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {        // 服务器启动时重建并预热缓存：把注册表扫描放到启动阶段，
        // 避免玩家首次打开 GUI 时在客户端渲染线程上触发一次性扫描（卡顿）
        SpellPoolManager.invalidate();
        SpellPoolManager.getAvailableSpells();
        SpellbookCatalog.invalidate();
        SpellbookCatalog.get();
        Config.invalidateWeightCache();
        // 旧版本 maxSpells 默认 10；升级后 ForgeConfigSpec 不会重置已存在的 key，
        // 把残留的 10 自动迁移到 v1.0.4 的默认 12。
        if (Config.SERVER.maxSpells.get() == 10) {
            Config.SERVER.maxSpells.set(12);
            RandomSpellPVP.LOGGER.info("Migrated randomspellbench-server.maxSpells from legacy default 10 to 12");
        }
        // v1.0.5：上限对齐 ISS 原版 spellbook create 指令的 20 个栏位（轮盘可完整展示），
        // 把 v1.0.4 残留的 12 自动迁移到 20。
        if (Config.SERVER.maxSpells.get() == 12) {
            Config.SERVER.maxSpells.set(20);
            RandomSpellPVP.LOGGER.info("Migrated randomspellbench-server.maxSpells from legacy default 12 to 20");
        }
    }
}
