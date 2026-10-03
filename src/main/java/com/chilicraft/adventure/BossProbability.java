package com.chilicraft.adventure;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 世界 Boss 触发概率模型：把「背包最强武器攻击力」与「盔甲防御值」两个输入
 * 各自映射到 [0,1] 的分项，相乘得 modifier，再与 trigger-param 折算成
 * 每次巡检的有效触发概率。
 *
 * <p><b>为什么只有两项</b>：本模型只评估「装备水平」——玩家当前能否扛住并
 * 打赢这只 Boss。击败履历、账号年龄、距上次死亡等长期状态不参与判定，
 * 它们已由 PlayerProfile.createdAt() 等接口保留数据源，需要时可再接入。</p>
 *
 * <p><b>为什么两项都用钟形</b>：钟形曲线表达「中等区间触发最多、两端递减」。
 * 全附魔毕业装（攻击 12 / 防御 30+）在峰值右侧被压低——他们不需要低级 Boss
 * 送上门；铁套铁剑（攻击 6 / 防御 15）正好落峰值附近——正是期望「刷出」的
 * 目标区间；裸装空手（0 / 0）在峰值左侧被压低——还不该遇到 Boss。</p>
 *
 * <p><b>为什么用乘法而非加权和</b>：攻击与防御是独立门槛（攻击达标但裸装、
 * 或防御达标但空手，都不该刷出），乘法天然表达 AND 语义；加权和会让单项
 * 极高值掩盖单项极低值。</p>
 *
 * <p><b>数值口径</b>（均以原版默认值为基准，不含药水/附魔加成）：</p>
 * <ul>
 *   <li>攻击 = 背包所有物品对 {@code GENERIC_ATTACK_DAMAGE} 的
 *       {@code ADD_NUMBER} 修饰符最大值。原版近战武器：木 4 / 石 5 /
 *       铁 6 / 钻石 7 / 下界合金 8；空手 0（主手无武器时不计基值）。</li>
 *   <li>防御 = {@code GENERIC_ARMOR} 属性值（已含护甲 + 附魔保护 + 盾牌
 *       + 状态效果，Bukkit 返回最终值）。原版护甲点：皮 5 / 铁 12 /
 *       钻石 20 / 下界合金 20（+韧性 3）；附魔保护 IV 每件约 +3 点。</li>
 * </ul>
 *
 * <p><b>主线程约定</b>：本类全部方法只读 Player 实时属性与 bosses.yml 解析出的
 * 不可变定义，无 DB 访问、无共享可变状态，可在巡检循环与调试命令中直接调用。</p>
 */
final class BossProbability {

    /**
     * 一次概率评估的完整结果：分项、乘积、有效概率，供调试展示与触发判定共用。
     *
     * @param modifier      攻击分 × 防御分，∈ [0,1]
     * @param attackScore   攻击分（钟形曲线输出）
     * @param defenseScore  防御分（钟形曲线输出）
     * @param rawAttack     背包最强武器攻击力（原值，调试展示）
     * @param rawDefense    玩家当前盔甲值（原值，调试展示）
     * @param effectivePercent 每次巡检有效概率（%）= trigger-param × modifier/reference，不放大
     */
    record Result(
            double modifier,
            double attackScore,
            double defenseScore,
            double rawAttack,
            double rawDefense,
            double effectivePercent
    ) {
    }

    private BossProbability() {
    }

    /**
     * 计算指定 Boss 对指定玩家的触发概率。
     *
     * @param def    Boss 定义（携带该 Boss 自己的曲线参数与开关）
     * @param player 被评估玩家
     * @return 分项与有效概率；def 关闭能力值时 effectivePercent 退回 trigger-param 原值
     */
    static Result evaluate(BossDefinition def, Player player) {
        double rawAttack = inventoryAttack(player);
        double rawDefense = armorValue(player);

        double attackScore = bell(rawAttack, def.capability.attackPeak(), def.capability.attackSigma());
        double defenseScore = bell(rawDefense, def.capability.defensePeak(), def.capability.defenseSigma());
        double modifier = clamp01(attackScore * defenseScore);

        double base = def.triggerPercent;
        double effective = base;
        if (def.capabilityEnabled()) {
            double reference = def.capability.reference();
            effective = Math.min(base * clamp01(modifier / reference), base);
        }
        return new Result(modifier, attackScore, defenseScore, rawAttack, rawDefense, effective);
    }

    /**
     * 钟形曲线 exp(-((x-peak)²)/(2σ²))：x = peak 时为 1，向两侧对称衰减。
     * σ ≤ 0 退化为 x == peak ? 1 : 0（无宽度的极窄峰）。
     */
    private static double bell(double x, double peak, double sigma) {
        if (sigma <= 0.0) {
            return x == peak ? 1.0 : 0.0;
        }
        double d = x - peak;
        return Math.exp(-(d * d) / (2.0 * sigma * sigma));
    }

    /**
     * 背包（主背包 36 格，非手持）最强武器攻击力。
     * 只取 {@code ADD_NUMBER} 修饰符（原版武器伤害加成即此类型）；
     * {@code MULTIPLY_SCALAR_1}（如急迫/力量百分比修饰）量纲不同，不计入。
     */
    private static double inventoryAttack(Player player) {
        double best = 0.0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            best = Math.max(best, itemAttack(item));
        }
        return best;
    }

    /** 单件物品对 GENERIC_ATTACK_DAMAGE 的 ADD_NUMBER 修饰符之和 */
    private static double itemAttack(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasAttributeModifiers()) {
            return 0.0;
        }
        double sum = 0.0;
        for (AttributeModifier modifier : meta.getAttributeModifiers(Attribute.GENERIC_ATTACK_DAMAGE)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    /**
     * 玩家当前盔甲值。Bukkit 的 {@code GENERIC_ARMOR} 返回最终生效值
     * （护甲基础点 + 附魔保护折算 + 盾牌 + 效果），无需再叠加韧性。
     */
    private static double armorValue(Player player) {
        AttributeInstance armor = player.getAttribute(Attribute.GENERIC_ARMOR);
        return armor == null ? 0.0 : armor.getValue();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return v > 1.0 ? 1.0 : v;
    }
}
