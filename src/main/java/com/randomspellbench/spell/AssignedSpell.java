package com.randomspellbench.spell;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;

/**
 * 一条分配结果：法术 id + 等级。
 */
public record AssignedSpell(String spellId, int level) {

    public static final String KEY_ID = "id";
    public static final String KEY_LEVEL = "level";

    public static AssignedSpell of(AbstractSpell spell, int level) {
        return new AssignedSpell(spell.getSpellId(), level);
    }

    /** 客户端解析法术对象；id 失效或为 none 时返回 null。 */
    @Nullable
    public AbstractSpell spell() {
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        return isNoneSpell(spell) ? null : spell;
    }

    /** NoneSpell 位于非 API 包，避免直接引用其类型，改用 id 判断。 */
    public static boolean isNoneSpell(@Nullable AbstractSpell spell) {
        return spell == null || "irons_spellbooks:none".equals(spell.getSpellId());
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, spellId);
        tag.putInt(KEY_LEVEL, level);
        return tag;
    }

    @Nullable
    public static AssignedSpell fromNBT(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        String id = tag.getString(KEY_ID);
        if (id.isEmpty()) {
            return null;
        }
        return new AssignedSpell(id, tag.getInt(KEY_LEVEL));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(spellId);
        buf.writeVarInt(level);
    }

    public static AssignedSpell decode(FriendlyByteBuf buf) {
        return new AssignedSpell(buf.readUtf(), buf.readVarInt());
    }
}
