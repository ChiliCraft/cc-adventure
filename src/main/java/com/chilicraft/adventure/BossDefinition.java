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
 *
 * <p>概率触发型 Boss 每只携带自己的 {@link Capability} 曲线参数：攻击与防御
 * 各自的钟形峰值/宽度、折算倍率，全部在 bosses.yml 该 Boss 节点下声明，
 * 不与其它 Boss 共享。</p>
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

    /**
     * 概率触发型 Boss 的能力值曲线参数（bosses.yml 的 {@code capability} 段）。
     *
     * <p>默认值按原版中期装备标定：铁剑攻击 6、铁套防御 15 正好落在
     * attack-peak / defense-peak 上——这是期望 Boss 刷出的目标区间；
     * σ=3 / σ=5 使峰值两侧各 1 个标准差处仍保留约 60% 分值，
     * 附魔毕业装与裸装则快速衰减到低位。</p>
     *
     * @param curve          曲线类型，当前仅支持 {@code bell}（钟形）
     * @param enabled        该 Boss 是否套用能力值；false = 直接用 trigger-param 原值
     * @param attackPeak     攻击钟形峰值（背包最强武器攻击力，原版铁剑 = 6）
     * @param attackSigma    攻击钟形宽度 σ（越大越平缓）
     * @param defensePeak    防御钟形峰值（玩家盔甲值，原版铁套 = 15）
     * @param defenseSigma   防御钟形宽度 σ
     * @param reference      折算倍率分母；modifier/reference 截断到 [0,1]
     */
    record Capability(
            String curve,
            boolean enabled,
            double attackPeak,
            double attackSigma,
            double defensePeak,
            double defenseSigma,
            double reference
    ) {
        /** 未在 bosses.yml 书写 capability 段时的默认参数（与 javadoc 标定一致） */
        static final Capability DEFAULT = new Capability(
                "bell", true, 6.0, 3.0, 15.0, 5.0, 1.0);
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
    final Capability capability;    // 概率触发型的能力值曲线参数（每 Boss 独立）
    final List<Skill> skills;       // 原版路径技能事件
    final boolean enabled;

    BossDefinition(String id, String displayName, String entityType, int health, int attack,
                   TriggerType trigger, int param, double triggerPercent, int firstKillSoul,
                   String mythicId, Capability capability, List<Skill> skills, boolean enabled) {
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
        this.capability = capability == null ? Capability.DEFAULT : capability;
        this.skills = skills;
        this.enabled = enabled;
    }

    /** 该 Boss 是否套用能力值（capability.enabled 字段直读） */
    boolean capabilityEnabled() {
        return capability.enabled;
    }

    static TriggerType parseTrigger(String name) {
        try {
            return TriggerType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
