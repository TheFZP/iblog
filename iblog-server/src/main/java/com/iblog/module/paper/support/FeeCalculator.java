package com.iblog.module.paper.support;

import com.iblog.config.IblogProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A 股费用计算：佣金、印花税（仅卖出）、过户费。
 *
 * <p>费率来自 {@code iblog.paper.*}，全部可配置。所有金额统一保留 2 位小数，
 * 四舍五入（与券商交割单口径一致）。</p>
 */
@Component
@RequiredArgsConstructor
public class FeeCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final IblogProperties properties;

    /**
     * @param amount 成交金额（不含费用）
     * @param sell   是否卖出（卖出才收印花税）
     */
    public Fee of(BigDecimal amount, boolean sell) {
        if (amount == null || amount.signum() <= 0) {
            return new Fee(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        IblogProperties.Paper config = properties.getPaper();

        BigDecimal rate = BigDecimal.valueOf(config.getCommissionRate());
        BigDecimal commission = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = config.getCommissionMin() == null ? BigDecimal.ZERO : config.getCommissionMin();
        if (commission.compareTo(min) < 0) {
            commission = min.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal stampTax = sell
                ? amount.multiply(BigDecimal.valueOf(config.getStampTaxRate())).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        BigDecimal transferFee = amount.multiply(BigDecimal.valueOf(config.getTransferFeeRate()))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = commission.add(stampTax).add(transferFee).setScale(2, RoundingMode.HALF_UP);
        return new Fee(commission, stampTax, transferFee, total);
    }

    /** 费用明细。用 record 而不是实体，避免和数据库字段耦合。 */
    public record Fee(BigDecimal commission, BigDecimal stampTax, BigDecimal transferFee, BigDecimal total) {

        /** 买入时的总成本：金额 + 费用。 */
        public BigDecimal buyCost(BigDecimal amount) {
            return amount.add(total).setScale(2, RoundingMode.HALF_UP);
        }

        /** 卖出时的净收入：金额 - 费用。 */
        public BigDecimal sellIncome(BigDecimal amount) {
            return amount.subtract(total).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
