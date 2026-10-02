package com.randomspellbench.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.randomspellbench.RandomSpellPVP;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = RandomSpellPVP.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    /**
     * ISS（Iron's Spells 'n Spellbooks）默认用 K 打开法术书，与本模组冲突时
     * 换成 F6；未安装 ISS 时仍用 K。玩家仍可在按键设置里自行修改。
     */
    private static int computeDefaultKey() {
        try {
            if (ModList.get() != null && ModList.get().isLoaded("irons_spellbooks")) {
                return GLFW.GLFW_KEY_F6;
            }
        } catch (Throwable ignored) {
        }
        return GLFW.GLFW_KEY_K;
    }

    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.randomspellbench.open_config",
            InputConstants.Type.KEYSYM,
            computeDefaultKey(),
            "key.categories.randomspellbench"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
    }
}
