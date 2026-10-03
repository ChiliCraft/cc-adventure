package com.chilicraft.adventure;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * bosses.yml 内容装载器：整体重建世界 Boss 注册表（onEnable 与 core.reload 各调一次）。
 */
final class BossContent {

    private final File file;
    private final Logger log;
    private final Map<String, BossDefinition> bosses = new LinkedHashMap<>();

    BossContent(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "bosses.yml");
        this.log = log;
    }

    /** 重新装载；返回注册表（只读视图） */
    Map<String, BossDefinition> reload() {
        bosses.clear();
        if (!file.isFile()) {
            log.warning(() -> "bosses.yml 不存在，世界 Boss 注册表为空：" + file.getPath());
            return Map.copyOf(bosses);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("bosses");
        if (root == null) {
            log.warning(() -> "bosses.yml 缺少 bosses 段，注册表为空");
            return Map.copyOf(bosses);
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            BossDefinition def = parse(id, s);
            if (def != null) {
                bosses.put(id, def);
            }
        }
        log.info(() -> "bosses.yml 已装载 " + bosses.size() + " 个世界 Boss 定义");
        return Map.copyOf(bosses);
    }

    private BossDefinition parse(String id, ConfigurationSection s) {
        if (!s.getBoolean("enabled", true)) {
            return null;
        }
        BossDefinition.TriggerType trigger = BossDefinition.parseTrigger(s.getString("trigger", "RANDOM"));
        if (trigger == null) {
            log.warning(() -> "Boss " + id + " 触发类型非法：" + s.getString("trigger") + "，跳过");
            return null;
        }
        int health = Math.max(20, s.getInt("health", 1000));
        int attack = Math.max(0, s.getInt("attack", 5));
        int param = Math.max(0, s.getInt("trigger-param", 50));
        // 概率触发型读小数（如 0.1 = 0.1%）；阈值型忽略此值
        double triggerPercent = Math.max(0.0, s.getDouble("trigger-param", 50.0));
        int firstKillSoul = Math.max(0, s.getInt("first-kill-soul", 100));
        // capability 键：缺省 null（继承全局），显式 true/false 以本 Boss 为准
        Boolean capability = s.contains("capability") ? s.getBoolean("capability") : null;
        // 能力值曲线参数逐 Boss 覆盖：capability-cap 下的键（attack-peak 等）覆盖全局同名键
        Map<String, Double> capabilityParams = parseCapabilityParams(s.getConfigurationSection("capability-cap"));

        List<BossDefinition.Skill> skills = new ArrayList<>();
        ConfigurationSection sk = s.getConfigurationSection("skills");
        if (sk != null) {
            for (String key : sk.getKeys(false)) {
                ConfigurationSection e = sk.getConfigurationSection(key);
                if (e == null) {
                    continue;
                }
                String typeName = e.getString("type", "").toUpperCase(Locale.ROOT);
                BossDefinition.SkillType type;
                try {
                    type = BossDefinition.SkillType.valueOf(typeName);
                } catch (IllegalArgumentException ex) {
                    log.warning(() -> "Boss " + id + " 技能 " + key + " 类型非法：" + typeName + "，跳过");
                    continue;
                }
                skills.add(new BossDefinition.Skill(type,
                        e.getString("effect", "SLOW").toUpperCase(Locale.ROOT),
                        Math.max(0, e.getInt("amplifier", 0)),
                        Math.max(1, e.getInt("count", 2)),
                        Math.max(3, e.getInt("radius", 8)),
                        e.getString("summon", "ZOMBIE")));
            }
        }
        return new BossDefinition(id,
                s.getString("display", id),
                s.getString("entity", "ZOMBIE"),
                health, attack, trigger, param, triggerPercent, firstKillSoul,
                s.getString("mythic-id", ""),
                capability,
                capabilityParams,
                List.copyOf(skills), true);
    }

    /**
     * 解析逐 Boss 的能力值曲线参数覆盖段；只收白名单键（防误配未知键静默生效），
     * 非法数值告警跳过。键与全局 config.yml boss.capability 同名。
     */
    private Map<String, Double> parseCapabilityParams(ConfigurationSection s) {
        if (s == null) {
            return Map.of();
        }
        Set<String> allowed = Set.of(
                "attack-peak", "attack-sigma",
                "defense-max", "defense-toughness-weight", "defense-base",
                "defeat-peak", "defeat-sigma",
                "kills-cap", "dungeons-cap", "bosses-cap", "arena-cap",
                "weight-kills", "weight-dungeons", "weight-bosses", "weight-arena",
                "age-peak", "age-sigma", "age-max-days",
                "survival-cap", "reference");
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : s.getKeys(false)) {
            if (!allowed.contains(key)) {
                log.warning(() -> "Boss capability-cap 键非法（不在白名单）：" + key + "，忽略");
                continue;
            }
            double v = s.getDouble(key, Double.NaN);
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                log.warning(() -> "Boss capability-cap." + key + "=" + v + " 非法，忽略");
                continue;
            }
            result.put(key, v);
        }
        return result;
    }
}
