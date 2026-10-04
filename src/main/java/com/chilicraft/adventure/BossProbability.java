package com.chilicraft.adventure;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 世界 Boss 触发概率模型：把「玩家实际攻击能力」与「盔甲防御值」两个输入
 * 各自映射到 [0,1] 的分项，相乘得 modifier，再与 trigger-param 折算成
 * 每次巡检的有效触发概率。
 *
 * <p><b>为什么只有两项</b>：本模型只评估「装备水平」——玩家当前能否扛住并
 * 打赢这只 Boss。击败履历、账号年龄、距上次死亡等长期状态不参与判定，
 * 它们已由 PlayerProfile.createdAt() 等接口保留数据源，需要时可再接入。</p>
 *
 * <p><b>为什么两项都用钟形</b>：钟形曲线表达「中等区间触发最多、两端递减」。
 * 全附魔毕业装在峰值右侧被压低——他们不需要低级 Boss 送上门；铁套铁剑
 * 正好落峰值附近——正是期望「刷出」的目标区间；裸装空手在峰值左侧被压低
 * ——还不该遇到 Boss。</p>
 *
 * <p><b>为什么用乘法而非加权和</b>：攻击与防御是独立门槛（攻击达标但裸装、
 * 或防御达标但空手，都不该刷出），乘法天然表达 AND 语义；加权和会让单项
 * 极高值掩盖单项极低值。</p>
 *
 * <p><b>攻击取值口径</b>（两条路径取最大值）：</p>
 * <ul>
 *   <li><b>实体实时值</b>：{@code GENERIC_ATTACK_DAMAGE} 的 {@code getValue()}
 *       = 玩家当前实际攻击力 = 基值 1 + 主手武器伤害 + 状态效果（力量/虚弱）。
 *       空手 1；手持铁剑 7（1+6）；钻石剑 8（1+7）。与 Minecraft 近战伤害
 *       计算口径一致——只有主手装备的武器计入。</li>
 *   <li><b>背包物品修饰符</b>：遍历背包 36 格，读每件物品
 *       {@code ItemMeta.getAttributeModifiers(GENERIC_ATTACK_DAMAGE)} 的
 *       {@code ADD_NUMBER} 修饰符之和。原版武器（铁剑/钻剑等）的伤害是物品
 *       内置基值，不走 NBT 修饰符路径，此路径对它们返回空——只有显式打了
 *       {@code attribute_modifiers} NBT 的物品（如指令/give 的自定义武器）
 *       能读到。</li>
 * </ul>
 *
 * <p><b>为什么两条路径都要</b>：手持武器靠实体值兜底（保证原版武器生效），
 * 背包里没手持但打了属性 NBT 的武器靠物品值兜底（覆盖自定义武器）。
 * 两者取 max 确保任何一条路径都能读到有效攻击力。</p>
 *
 * <p><b>防御取值口径</b>：{@code GENERIC_ARMOR} 属性值（已含护甲 + 附魔保护 +
 * 盾牌 + 状态效果，Bukkit 返回最终值）。原版护甲点：皮 5 / 铁 12 /
 * 钻石 20 / 下界合金 20（+韧性 3）；附魔保护 IV 每件约 +3 点。</p>
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
     * @param rawAttack     攻击力原值 = max(实体实时攻击, 背包物品修饰符攻击)，调试展示
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
        double rawAttack = Math.max(attackValue(player), inventoryAttack(player));
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
     * 玩家当前实际攻击力。Bukkit 的 {@code GENERIC_ATTACK_DAMAGE} 返回
     * 玩家实体当前生效的攻击总值：基值 1 + 主手武器伤害加成 + 状态效果
     * （力量 +X / 虚弱 -X）。与 Minecraft 近战伤害计算口径一致——
     * 只有主手装备的武器计入，背包中未装备的武器不生效。
     *
     * <p>对原版武器（铁剑/钻剑/下界合金剑）的伤害是物品内置基值，
     * 不走 NBT 修饰符路径，此方法是它们唯一能被读到的路径。</p>
     */
    private static double attackValue(Player player) {
        AttributeInstance attack = player.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        return attack == null ? 0.0 : attack.getValue();
    }

    /**
     * 背包（主背包 36 格，非手持）中物品的属性修饰符攻击力最大值。
     * 读 {@code ItemMeta.getAttributeModifiers(GENERIC_ATTACK_DAMAGE)} 的
     * {@code ADD_NUMBER} 修饰符之和——只有显式打了 {@code attribute_modifiers}
     * NBT 的物品（指令/give 的自定义武器）能读到；原版铁剑/钻剑不走此路径，
     * 返回 0，由 {@link #attackValue(Player)} 兜底。
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
