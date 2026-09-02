package com.randomspellbench.events;

import com.randomspellbench.Config;
import com.randomspellbench.RandomSpellPVP;
import com.randomspellbench.capability.PlayerConfigStore;
import com.randomspellbench.network.NetworkHandler;
import com.randomspellbench.network.packet.S2CSyncConfigPacket;
import com.randomspellbench.spell.SpellPoolManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 配置事件（MOD 总线）：配置被重载时让各处缓存失效，
 * 保证服主用 Forge /reload 或客户端改配置后，数据立刻是最新的。
 */
@Mod.EventBusSubscriber(modid = RandomSpellPVP.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ConfigEvents {

    private ConfigEvents() {
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        Config.invalidateWeightCache();
        if (event.getConfig() != null && event.getConfig().getType() != ModConfig.Type.CLIENT) {
            // 服务端配置变动会影响法术黑名单，重建法术池缓存
            SpellPoolManager.invalidate();
            // 主动把最新配置推给所有在线玩家，已打开 GUI 的玩家也会同步刷新（如 maxSpells 变化）
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    NetworkHandler.sendToPlayer(new S2CSyncConfigPacket(
                            PlayerConfigStore.get(p).serializeNBT(), Config.SERVER.maxSpells.get(),
                            new java.util.ArrayList<>(Config.SERVER.bannedSpells.get()),
                            new java.util.ArrayList<>(Config.SERVER.schoolWhitelist.get())), p);
                }
            }
        }
    }
}
