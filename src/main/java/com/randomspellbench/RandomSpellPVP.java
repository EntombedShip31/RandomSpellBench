package com.randomspellbench;

import com.randomspellbench.command.RandomSpellCommands;
import com.randomspellbench.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RandomSpellPVP.MODID)
public class RandomSpellPVP {
    public static final String MODID = "randomspellbench";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public RandomSpellPVP() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        // 注册配置文件（服务端 + 客户端）
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        // MOD 总线事件（初始化阶段）
        modBus.addListener(RandomSpellPVP::onCommonSetup);

        // Forge 总线事件（游戏运行期）。
        // 注意：ModEvents 已通过 @Mod.EventBusSubscriber 自动注册，勿重复注册。
        forgeBus.register(RandomSpellCommands.class);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // 网络通道必须在主线程初始化
        event.enqueueWork(NetworkHandler::register);
    }
}
