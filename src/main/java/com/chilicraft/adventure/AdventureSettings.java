package com.chilicraft.adventure;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * cc-adventure 数值缓存：周期任务禁止每秒穿透配置树，全部数值在此重建。
 *
 * <p>refresh() 在 onEnable 与 core.reload 时各调一次；非法值回退默认并告警。</p>
 */
final class AdventureSettings {

    private final JavaPlugin plugin;
    private final Logger log;

    // ---------- 组队 ----------
    int partyMaxSize = 4;           // 单支队伍上限（地城与远征共用）

    // ---------- 地城 ----------
    boolean dungeonEnabled = true;
    String dungeonWorldName = "world_cc_dungeon";
    int instanceSlots = 8;          // 最大并发实例数（也即区域槽位数）
    int slotStride = 256;           // 实例区域槽位间隔（x 方向）
    int baseY = 64;                 // 实例区域基准高度
    int wipeBudgetPerTick = 4000;   // 实例清理每 tick 方块预算（防卡主线程）
    int queueRetrySeconds = 5;      // 排队重试间隔（秒）
    int dungeonTimeLimitSec = 600;  // 默认地城时限（定义未填时兜底）

    // ---------- 远征 ----------
    boolean expeditionEnabled = true;
    String expeditionWorldName = "world_cc_expedition";
    int expeditionLayers = 5;               // 远征层数（规格：5 层制）
    int expeditionMaxRuns = 4;              // 最大并发远征队
    int expeditionSoulPerLayer = 5;         // 每层通关灵魂
    int expeditionClearBonus = 25;          // 全程通关追加灵魂
    int forceCurseAfterBlessings = 2;       // 每 N 个祝福强制 1 诅咒（规格：2:1）
    int effectDurationTicks = 12_000;       // 祝福/诅咒效果时长（每层刷新）
    Map<String, Integer> roomWeights = new LinkedHashMap<>();   // 9 种房间权重
    List<String> blessingEffects = List.of();                   // 祝福池：类型:强度:名称
    List<String> curseEffects = List.of();                      // 诅咒池：类型:强度:名称

    // ---------- 世界 Boss ----------
    boolean bossEnabled = true;
    int bossCheckSeconds = 15;              // 触发条件巡检间隔（秒）
    int bossSkillSeconds = 6;               // Boss 技能事件间隔（秒）
    boolean integrationMythicMobs = false;  // MM 在场时托管 Boss 技能
    int bossDespawnMinutes = 30;            // Boss 存活超时（0=不超时）

    // ---------- 世界 Boss · 能力值（capability） ----------
    // 五项分项各归一到 [0,1] 后相乘得 modifier ∈ [0,1]，
    // 概率触发型 Boss 的有效概率 = trigger-param(%) × modifier（不设全局硬上限，
    // 各 Boss 的 trigger-param 即其自己的天花板）。
    boolean capEnabled = true;              // 总开关；false = 全部退回纯 trigger-param
    double capAttackPeak = 8.0;             // 攻击钟形峰值（背包最强武器攻击力）
    double capAttackSigma = 3.0;            // 攻击钟形宽度 σ
    double capDefenseMax = 26.0;            // 防御归一满值基准（盔甲+权重×韧性）
    double capDefenseToughnessWeight = 0.5; // 韧性折算权重（加入防御分的系数）
    double capDefenseBase = 0.0;            // 防御分底噪（裸装拿到的最低分，0=纯 sqrt）
    double capDefeatPeak = 0.4;             // 击败钟形峰值（0-1 归一战绩分）
    double capDefeatSigma = 0.25;           // 击败钟形宽度 σ
    int capKillsCap = 200;                  // 击败分：击杀封顶值（log1p 归一基准）
    int capDungeonsCap = 20;                // 击败分：地城封顶值
    int capBossesCap = 10;                  // 击败分：Boss 击杀封顶值
    int capArenaCap = 5;                    // 击败分：擂台封顶值
    double capWeightKills = 0.4;            // 击败分权重：击杀
    double capWeightDungeons = 0.3;         // 击败分权重：地城
    double capWeightBosses = 0.2;           // 击败分权重：Boss
    double capWeightArena = 0.1;            // 击败分权重：擂台
    double capAgePeak = 0.3;                // 账号年龄钟形峰值（0-1 归一）
    double capAgeSigma = 0.2;               // 账号年龄钟形宽度 σ
    double capAgeMaxDays = 60.0;            // 账号年龄归一上限（天）
    double capSurvivalCap = 21600.0;        // 生存分封顶秒数（6 小时）
    double capReference = 1.0;              // 参考倍率：modifier/参考 折算到 [0,1]

    // ---------- 消息 ----------
    Map<String, String> messages = new HashMap<>();

    AdventureSettings(JavaPlugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /** 从活配置整体重建全部字段 */
    void refresh() {
        partyMaxSize = clampInt("party.max-size", 4, 1, 8);

        dungeonEnabled = plugin.getConfig().getBoolean("dungeon.enabled", true);
        dungeonWorldName = str("dungeon.world-name", "world_cc_dungeon");
        instanceSlots = clampInt("dungeon.instance-slots", 8, 1, 32);
        slotStride = clampInt("dungeon.slot-stride", 256, 128, 4096);
        baseY = clampInt("dungeon.base-y", 64, 1, 250);
        wipeBudgetPerTick = clampInt("dungeon.wipe-budget-per-tick", 4000, 500, 200_000);
        queueRetrySeconds = clampInt("dungeon.queue-retry-seconds", 5, 1, 60);
        dungeonTimeLimitSec = clampInt("dungeon.time-limit-seconds", 600, 60, 7200);

        expeditionEnabled = plugin.getConfig().getBoolean("expedition.enabled", true);
        expeditionWorldName = str("expedition.world-name", "world_cc_expedition");
        expeditionLayers = clampInt("expedition.layers", 5, 1, 10);
        expeditionMaxRuns = clampInt("expedition.max-runs", 4, 1, 16);
        expeditionSoulPerLayer = clampInt("expedition.soul-per-layer", 5, 0, 1000);
        expeditionClearBonus = clampInt("expedition.clear-bonus", 25, 0, 10_000);
        forceCurseAfterBlessings = clampInt("expedition.force-curse-after-blessings", 2, 1, 10);
        effectDurationTicks = clampInt("expedition.effect-duration-ticks", 12_000, 200, 1_000_000);

        roomWeights.clear();
        var raw = plugin.getConfig().getConfigurationSection("expedition.room-weights");
        if (raw != null) {
            for (String key : raw.getKeys(false)) {
                roomWeights.put(key.toLowerCase(), Math.max(0, raw.getInt(key, 0)));
            }
        }
        if (roomWeights.isEmpty()) {
            // 兜底：9 种房间等权（combat/elite/chest/trap/rest/shop/event/puzzle/boss）
            for (String t : List.of("combat", "elite", "chest", "trap", "rest", "shop", "event", "puzzle", "boss")) {
                roomWeights.put(t, 10);
            }
        }
        blessingEffects = plugin.getConfig().getStringList("expedition.blessings");
        curseEffects = plugin.getConfig().getStringList("expedition.curses");

        bossEnabled = plugin.getConfig().getBoolean("boss.enabled", true);
        bossCheckSeconds = clampInt("boss.check-seconds", 15, 1, 600);
        bossSkillSeconds = clampInt("boss.skill-seconds", 6, 1, 60);
        integrationMythicMobs = plugin.getConfig().getBoolean("integration.mythicmobs", false);
        bossDespawnMinutes = clampInt("boss.despawn-minutes", 30, 0, 1440);

        var cap = plugin.getConfig().getConfigurationSection("boss.capability");
        capEnabled = cap == null || cap.getBoolean("enabled", true);
        capAttackPeak = cap == null ? 8.0 : clampDouble(cap, "attack-peak", 8.0, 0.0, 100.0);
        capAttackSigma = cap == null ? 3.0 : clampDouble(cap, "attack-sigma", 3.0, 0.1, 100.0);
        capDefenseMax = cap == null ? 26.0 : clampDouble(cap, "defense-max", 26.0, 1.0, 100.0);
        capDefenseToughnessWeight = cap == null ? 0.5
                : clampDouble(cap, "defense-toughness-weight", 0.5, 0.0, 10.0);
        capDefenseBase = cap == null ? 0.0 : clampDouble(cap, "defense-base", 0.0, 0.0, 1.0);
        capDefeatPeak = cap == null ? 0.4 : clampDouble(cap, "defeat-peak", 0.4, 0.0, 1.0);
        capDefeatSigma = cap == null ? 0.25 : clampDouble(cap, "defeat-sigma", 0.25, 0.01, 1.0);
        capKillsCap = cap == null ? 200 : clampInt(cap, "kills-cap", 200, 1, 100_000);
        capDungeonsCap = cap == null ? 20 : clampInt(cap, "dungeons-cap", 20, 1, 10_000);
        capBossesCap = cap == null ? 10 : clampInt(cap, "bosses-cap", 10, 1, 10_000);
        capArenaCap = cap == null ? 5 : clampInt(cap, "arena-cap", 5, 1, 10_000);
        capWeightKills = cap == null ? 0.4 : clampDouble(cap, "weight-kills", 0.4, 0.0, 1.0);
        capWeightDungeons = cap == null ? 0.3 : clampDouble(cap, "weight-dungeons", 0.3, 0.0, 1.0);
        capWeightBosses = cap == null ? 0.2 : clampDouble(cap, "weight-bosses", 0.2, 0.0, 1.0);
        capWeightArena = cap == null ? 0.1 : clampDouble(cap, "weight-arena", 0.1, 0.0, 1.0);
        capAgePeak = cap == null ? 0.3 : clampDouble(cap, "age-peak", 0.3, 0.0, 1.0);
        capAgeSigma = cap == null ? 0.2 : clampDouble(cap, "age-sigma", 0.2, 0.01, 1.0);
        capAgeMaxDays = cap == null ? 60.0 : clampDouble(cap, "age-max-days", 60.0, 1.0, 3650.0);
        capSurvivalCap = cap == null ? 21600.0 : clampDouble(cap, "survival-cap", 21600.0, 60.0, 604800.0);
        capReference = cap == null ? 1.0 : clampDouble(cap, "reference", 1.0, 0.0001, 10.0);

        messages.clear();
        var msg = plugin.getConfig().getConfigurationSection("messages");
        if (msg != null) {
            // 深键遍历：消息键本身含点号（expedition.start 等），YAML 嵌套书写，
            // getKeys(true) 还原出的深路径恰与代码键一致；中间层级段只收叶子。
            for (String key : msg.getKeys(true)) {
                if (msg.isConfigurationSection(key)) {
                    continue;
                }
                String v = msg.getString(key, "");
                if (v != null) {
                    messages.put(key, v);
                }
            }
        }
    }

    /** 消息模板；缺失返回空串（展示层判空静默跳过） */
    String message(String key) {
        return messages.getOrDefault(key, "");
    }

    /** 消息模板（带兜底）：GUI 按钮等不可空白场景用，缺失/空串回退 def */
    String messageOr(String key, String def) {
        String v = messages.get(key);
        return v == null || v.isBlank() ? def : v;
    }

    private String str(String path, String def) {
        String v = plugin.getConfig().getString(path, def);
        return v == null || v.isBlank() ? def : v;
    }

    private int clampInt(String path, int def, int min, int max) {
        int v = plugin.getConfig().getInt(path, def);
        if (v < min || v > max) {
            log.warning(() -> "配置 " + path + "=" + v + " 非法，回退默认 " + def);
            return def;
        }
        return v;
    }

    private double clampDouble(ConfigurationSection section, String key, double def, double min, double max) {
        double v = section.getDouble(key, def);
        if (Double.isNaN(v) || v < min || v > max) {
            log.warning(() -> "配置 boss.capability." + key + "=" + v + " 非法，回退默认 " + def);
            return def;
        }
        return v;
    }

    private int clampInt(ConfigurationSection section, String key, int def, int min, int max) {
        int v = section.getInt(key, def);
        if (v < min || v > max) {
            log.warning(() -> "配置 boss.capability." + key + "=" + v + " 非法，回退默认 " + def);
            return def;
        }
        return v;
    }
}
