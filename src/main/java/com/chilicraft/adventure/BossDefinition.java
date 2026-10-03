package com.chilicraft.adventure;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 世界 Boss 定义（bosses.yml 数据驱动）。
 *
 * <p>5 个 Boss（规格：饿魔母体·真身 3,000 / 水做之影 2,500 / 台风之眼 2,800 /
 * 幻形鹦鹉 2,000 / 梦魇 4,000），触发条件按总纲（连续夜间死亡 / 雨天深海 /
 * 台风 / 随机 / 半梦界深层）。默认原版生物改属性＋技能事件；MythicMobs 在场时
 * 技能定义交 MM（mythic-id 键），缺失降级原版路径。</p>
 */
final class BossDefinition {

    /** 触发条件类型（总纲口径） */
    enum TriggerType {
        NIGHT_DEATH_STREAK,  // 连续夜间死亡 N 次（param=阈值）
        RAIN_OCEAN,          // 雨天且玩家处于深海群系（param=触发概率%）
        THUNDER,             // 雷暴（台风）天气（param=触发概率%）
        RANDOM,              // 任意时刻低概率（param=触发概率%）
        DEPTH                // 玩家深层（半梦界深层占位：y < param）
    }

    /** 原版路径技能事件类型（MM 在场时由 MM 配置接管，不走此列表） */
    enum SkillType {
        SUMMON,     // 召唤护卫：summon=实体类型, count=数量
        EFFECT,     // 对附近玩家施放药水效果：effect=类型, amplifier=强度, radius=半径
        LIGHTNING   // 对随机附近玩家落雷：radius=半径
    }

    /** 一条技能事件定义 */
    record Skill(SkillType type, String effect, int amplifier, int count, int radius, String summon) {
    }

    final String id;
    final String displayName;
    final String entityType;        // 原版实体类型名
    final int health;               // 最大生命（规格数值）
    final int attack;               // 攻击伤害加值
    final TriggerType trigger;
    final int param;                // 触发参数（阈值/概率%/y 阈）
    final double triggerPercent;    // 概率触发型的 trigger-param（%，支持小数如 0.1）
    final int firstKillSoul;        // 首杀灵魂奖励（按玩家）
    final String mythicId;          // MM 内部 ID（空串=不用 MM）
    final Boolean capability;       // 逐 Boss 能力值开关：null=继承全局，false=强制退回纯概率
    final List<Skill> skills;       // 原版路径技能事件
    final boolean enabled;
    /**
     * 能力值曲线参数逐 Boss 覆盖（capability.* 配置键 → 数值）。
     * 仅当该 Boss 在 bosses.yml 显式写了 capability-cap 之类键才非空；
     * 缺省时回退到全局 config.yml boss.capability 段。
     * 只覆盖"曲线形状"（峰值/宽度/基准），不覆盖开关与折算（reference/weights 属全局）。
     */
    final Map<String, Double> capabilityParams;

    BossDefinition(String id, String displayName, String entityType, int health, int attack,
                   TriggerType trigger, int param, double triggerPercent, int firstKillSoul,
                   String mythicId, Boolean capability, Map<String, Double> capabilityParams,
                   List<Skill> skills, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.entityType = entityType;
        this.health = health;
        this.attack = attack;
        this.trigger = trigger;
        this.param = param;
        this.triggerPercent = triggerPercent;
        this.firstKillSoul = firstKillSoul;
        this.mythicId = mythicId == null ? "" : mythicId;
        this.capability = capability;
        this.capabilityParams = capabilityParams == null ? Map.of() : Map.copyOf(capabilityParams);
        this.skills = skills;
        this.enabled = enabled;
    }

    /** 该 Boss 是否套用能力值（null 继承全局；非空时以该 Boss 为准） */
    boolean capabilityEnabled(boolean global) {
        return capability == null ? global : capability;
    }

    /** 取本 Boss 的曲线参数覆盖；未配置则返回 global（由调用方传入全局默认） */
    double capabilityParam(String key, double global) {
        Double v = capabilityParams.get(key);
        return v == null ? global : v;
    }

    static TriggerType parseTrigger(String name) {
        try {
            return TriggerType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
