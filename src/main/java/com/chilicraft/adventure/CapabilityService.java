package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界 Boss 能力值（capability）评估：把五项输入各自归一到 [0,1] 后相乘，
 * 得到 modifier ∈ [0,1]，概率触发型 Boss 的有效概率 = trigger-param(%) × modifier。
 *
 * <p><b>为什么用乘法</b>：五项是独立门槛（攻击中等 + 防御高 + 击败中等 +
 * 年龄中等 + 生存久，缺一条整体降档），乘法天然表达 AND 语义；加权和会让
 * 单项极高值掩盖单项极低值。</p>
 *
 * <p><b>曲线方向（按设计要求）</b>：攻击 = 钟形（中高区间峰值，读背包而非手）、
 * 防御 = 单调递增、击败 = 钟形（中等峰值）、账号年龄 = 钟形（中等峰值）、
 * 生存 = 单调递增带饱和。</p>
 *
 * <p><b>线程契约</b>：evaluate() 只在主线程调用（巡检循环 / debug 命令）。
 * 战绩与死亡时间来自 DB，登录/死亡时异步拉取并写入 {@link #cache}，
 * 主线程只读缓存，绝不阻塞（见 RowStore javadoc：回调在 DB 线程）。</p>
 */
final class CapabilityService {

    /** 一次能力评估的完整分项（供 debug 展示与触发判定共用，避免重复计算） */
    record Snapshot(
            double attack,        // 攻击分 [0,1]（背包最强武器 → 钟形）
            double defense,       // 防御分 [0,1]（盔甲+韧性 → 单调递增）
            double defeat,        // 击败分 [0,1]（战绩归一 → 钟形）
            double age,           // 年龄分 [0,1]（账号年龄 → 钟形）
            double survival,      // 生存分 [0,1]（距上次死亡 → 饱和递增）
            double modifier,      // 五项乘积 ∈ [0,1]
            double rawAttack,     // 原始值（debug 展示）
            double rawDefense,
            long accountAgeDays,
            long secondsSinceDeath
    ) {
    }

    /** 每名玩家的 DB 侧缓存（登录/死亡事件刷新，主线程只读） */
    private static final class Cached {
        int kills;
        int dungeons;
        int bosses;
        int arena;
        long lastDeathAt;      // epoch ms；0 = 无死亡记录
        long lastLoadAt;       // 最近一次拉取时间，避免反复查库
    }

    /** 缓存中性值（尚无 DB 数据时的占位，避免首轮误算） */
    private static final double NEUTRAL_SCORE = 0.5;
    private static final long NEVER_DIED = -1L;   // secondsSinceDeath 的哨兵值：无死亡记录
    private static final long MS_PER_DAY = 86_400_000L;
    private static final long MS_PER_SECOND = 1_000L;

    /**
     * 同一玩家两次查库的最小间隔（毫秒）。
     * 纯性能护栏（防止 DB 故障时每轮重查），非玩法数值，故留代码常量；
     * 所有玩法数值一律走 config.yml / bosses.yml。
     */
    private static final long RELOAD_MIN_INTERVAL_MS = 60_000L;

    private final ChiliCraftAPI api;
    private final AdventureSettings settings;

    /** 玩家 → DB 侧缓存；ConcurrentHashMap 保证 DB 线程写、主线程读安全 */
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    CapabilityService(ChiliCraftAPI api, AdventureSettings settings) {
        this.api = api;
        this.settings = settings;
    }

    /** 玩家退出/重载时清缓存（缓存有 60s 拉取间隔，不清会短暂用旧值） */
    void invalidate(UUID playerId) {
        cache.remove(playerId);
    }

    /** 全部清空（core.reload 触发，强制下次重新拉库） */
    void invalidateAll() {
        cache.clear();
    }

    // ---------------- 主线程评估 ----------------

    /**
     * 计算玩家能力快照（主线程，全局参数版）。
     * 用于 boss debug 展示"按全局配置"的分项；概率判定走
     * {@link #evaluateFor(BossDefinition, Player)}（支持逐 Boss 曲线覆盖）。
     */
    Snapshot evaluate(Player player) {
        return evaluateFor(null, player);
    }

    /**
     * 计算玩家能力快照（主线程）。传入的 BossDefinition 提供逐 Boss 曲线参数覆盖
     * （capability-cap 段），缺省回退全局 config.yml boss.capability。
     *
     * @param def     当前 Boss 定义；null = 纯全局（debug 展示用）
     * @param player  被评估玩家
     */
    Snapshot evaluateFor(BossDefinition def, Player player) {
        double rawAttack = inventoryAttack(player);
        double rawDefense = defenseRaw(player, def);
        Cached c = ensureCached(player.getUniqueId());

        double attack = bell(rawAttack,
                p(def, "attack-peak", settings.capAttackPeak),
                p(def, "attack-sigma", settings.capAttackSigma));
        double defense = defenseRaw(player, def);                 // 单调递增（开方段），带 defense-base 底噪
        double defeat = bell(defeatScore(c, def),
                p(def, "defeat-peak", settings.capDefeatPeak),
                p(def, "defeat-sigma", settings.capDefeatSigma));
        double age = bell(ageScore(player.getUniqueId(), def),
                p(def, "age-peak", settings.capAgePeak),
                p(def, "age-sigma", settings.capAgeSigma));
        double survival = survivalScore(c, def);

        double modifier = clamp01(attack * defense * defeat * age * survival);
        return new Snapshot(attack, defense, defeat, age, survival, modifier,
                rawAttack, rawDefense, accountAgeDays(player.getUniqueId()),
                secondsSinceDeath(c));
    }

    /** 逐 Boss 曲线参数取值：def 为 null 或该 Boss 未覆盖此键时回退全局值 */
    private double p(BossDefinition def, String key, double global) {
        return def == null ? global : def.capabilityParam(key, global);
    }

    /**
     * 概率触发型 Boss 的有效触发概率（%）。
     *
     * <p>公式：{@code min(trigger-param × modifier/reference, trigger-param)} ——
     * 能力倍率只向下拉，不放大；不设全局硬上限，各 Boss 的 trigger-param
     * 就是它自己的天花板（配置即语义）。</p>
     *
     * @param def     Boss 定义（决定曲线参数与 reference 覆盖）
     * @param player  被评估玩家
     */
    double effectivePercent(BossDefinition def, Player player) {
        double base = def.triggerPercent;
        if (!settings.capEnabled || !def.capabilityEnabled(settings.capEnabled)) {
            return base;
        }
        Snapshot snap = evaluateFor(def, player);
        double ref = p(def, "reference", settings.capReference);
        double factor = clamp01(snap.modifier() / ref);
        return Math.min(base * factor, base);
    }

    /** 供 debug 展示：按给定快照与 Boss 覆盖算有效概率（不重复评估玩家） */
    double effectivePercent(double basePercent, double modifier, BossDefinition def) {
        double ref = p(def, "reference", settings.capReference);
        double factor = clamp01(modifier / ref);
        return Math.min(basePercent * factor, basePercent);
    }

    // ---------------- 分项取值 ----------------

    /** 背包（非手持）最强武器攻击力：遍历 storageContents 取 GENERIC_ATTACK_DAMAGE 修饰符最大值 */
    private double inventoryAttack(Player player) {
        double best = 0.0;
        ItemStack[] items = player.getInventory().getStorageContents();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            best = Math.max(best, itemAttack(item));
        }
        return best;
    }

    /** 单件物品对 GENERIC_ATTACK_DAMAGE 的 ADD_NUMBER 修饰符之和 */
    private double itemAttack(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasAttributeModifiers()) {
            return 0.0;
        }
        double sum = 0.0;
        for (AttributeModifier modifier : meta.getAttributeModifiers(Attribute.GENERIC_ATTACK_DAMAGE)) {
            // 只累加直接加成；MULTIPLY_SCALAR_1（百分比）按其作用后的量纲会放大，这里不计
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    /**
     * 防御归一（带逐 Boss 覆盖）：base + (1-base)×sqrt((盔甲+权重×韧性)/max)。
     *
     * <p>裸装（armor=0）返回 {@code defense-base} 而非 0 —— 让完全没盔甲的萌新
     * 也拿到非零防御分，避免"没甲就被彻底排除"；base=0 时退化为纯 sqrt。</p>
     */
    private double defenseRaw(Player player, BossDefinition def) {
        AttributeInstance armor = player.getAttribute(Attribute.GENERIC_ARMOR);
        AttributeInstance toughness = player.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS);
        double a = armor == null ? 0.0 : armor.getValue();
        double t = toughness == null ? 0.0 : toughness.getValue();
        double weight = p(def, "defense-toughness-weight", settings.capDefenseToughnessWeight);
        double max = p(def, "defense-max", settings.capDefenseMax);
        double base = clamp01(p(def, "defense-base", settings.capDefenseBase));
        if (max <= 0.0) {
            return base;
        }
        double ratio = clamp01((a + weight * t) / max);
        return clamp01(base + (1.0 - base) * Math.sqrt(ratio));
    }

    /** 击败分：四个战绩计数器 log1p 加权归一（cap*Cap=封顶值，capWeight*=权重，逐 Boss 可覆盖） */
    private double defeatScore(Cached c, BossDefinition def) {
        double kills = log1p(c.kills) / log1p(p(def, "kills-cap", settings.capKillsCap))
                * p(def, "weight-kills", settings.capWeightKills);
        double dungeons = log1p(c.dungeons) / log1p(p(def, "dungeons-cap", settings.capDungeonsCap))
                * p(def, "weight-dungeons", settings.capWeightDungeons);
        double bosses = log1p(c.bosses) / log1p(p(def, "bosses-cap", settings.capBossesCap))
                * p(def, "weight-bosses", settings.capWeightBosses);
        double arena = log1p(c.arena) / log1p(p(def, "arena-cap", settings.capArenaCap))
                * p(def, "weight-arena", settings.capWeightArena);
        return clamp01(kills + dungeons + bosses + arena);
    }

    /** 账号年龄（天）/ age-max-days（逐 Boss 可覆盖），封顶 1 */
    private double ageScore(UUID playerId, BossDefinition def) {
        long days = accountAgeDays(playerId);
        double maxDays = p(def, "age-max-days", settings.capAgeMaxDays);
        if (maxDays <= 0.0) {
            return 0.0;
        }
        return clamp01((double) days / maxDays);
    }

    /** 生存分：距上次死亡的秒数 / survival-cap（逐 Boss 可覆盖），封顶 1；无死亡记录给中性值 */
    private double survivalScore(Cached c, BossDefinition def) {
        if (c.lastDeathAt <= 0) {
            return NEUTRAL_SCORE;
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - c.lastDeathAt) / MS_PER_SECOND);
        double cap = p(def, "survival-cap", settings.capSurvivalCap);
        if (cap <= 0.0) {
            return 0.0;
        }
        return clamp01((double) seconds / cap);
    }

    // ---------------- 数学工具 ----------------

    /** 钟形曲线 exp(-((x-μ)²)/(2σ²))，σ≤0 时退化为 x==μ ? 1 : 0 */
    private static double bell(double x, double peak, double sigma) {
        if (sigma <= 0) {
            return x == peak ? 1.0 : 0.0;
        }
        double d = x - peak;
        return Math.exp(-(d * d) / (2.0 * sigma * sigma));
    }

    private static double log1p(double v) {
        return Math.log1p(Math.max(0.0, v));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return v > 1.0 ? 1.0 : v;
    }

    // ---------------- DB 缓存 ----------------

    /** 账号年龄（天）；档案不可用（离线/未预载）时给 0（debug 会显示） */
    private long accountAgeDays(UUID playerId) {
        var profile = api.getProfile(playerId);
        if (profile == null) {
            return 0L;
        }
        long createdAt = profile.createdAt();
        if (createdAt <= 0) {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - createdAt) / MS_PER_DAY);
    }

    private long secondsSinceDeath(Cached c) {
        if (c.lastDeathAt <= 0) {
            return NEVER_DIED;      // -1 = 无死亡记录
        }
        return Math.max(0L, (System.currentTimeMillis() - c.lastDeathAt) / MS_PER_SECOND);
    }

    /** 确保缓存在位；缺则异步拉库并先返回一份中性缓存（不阻塞主线程） */
    private Cached ensureCached(UUID playerId) {
        Cached c = cache.get(playerId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.lastLoadAt < RELOAD_MIN_INTERVAL_MS) {
            return c;
        }
        if (c == null) {
            c = new Cached();
            cache.put(playerId, c);
            loadAsync(playerId, c);
        }
        return c;
    }

    /**
     * 异步拉取战绩与上次死亡时间，完成后回主线程写缓存。
     * 两个查询并行发出，互不阻塞；任一失败只告警不抛（能力值降级为中性）。
     *
     * <p>注意：RowStoreImpl.select 只支持 WHERE（不支持 ORDER BY/LIMIT），
     * 所以死亡记录拉全量后在内存取 max(died_at)。</p>
     */
    private void loadAsync(UUID playerId, Cached target) {
        CompletableFuture<List<Map<String, Object>>> skills =
                api.rowStore().select("cc_skills", "player_id = ?", playerId);
        CompletableFuture<List<Map<String, Object>>> deaths =
                api.rowStore().select("cc_death_records", "player_id = ?", playerId);

        skills.whenComplete((rows, error) -> {
            if (error != null) {
                return;
            }
            int kills = 0, dungeons = 0, bosses = 0, arena = 0;
            for (Map<String, Object> row : rows) {
                String skillId = str(row.get("skill_id"));
                int xp = intOf(row.get("xp"));
                switch (skillId.toLowerCase(Locale.ROOT)) {
                    case "__kills__" -> kills = xp;
                    case "__dungeons__" -> dungeons = xp;
                    case "__bosses__" -> bosses = xp;
                    case "__arena__" -> arena = xp;
                    default -> { }
                }
            }
            target.kills = kills;
            target.dungeons = dungeons;
            target.bosses = bosses;
            target.arena = arena;
            target.lastLoadAt = System.currentTimeMillis();
        });

        deaths.whenComplete((rows, error) -> {
            if (error != null || rows.isEmpty()) {
                return;
            }
            long max = 0L;
            for (Map<String, Object> row : rows) {
                max = Math.max(max, longOf(row.get("died_at")));
            }
            target.lastDeathAt = max;
        });
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static int intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
