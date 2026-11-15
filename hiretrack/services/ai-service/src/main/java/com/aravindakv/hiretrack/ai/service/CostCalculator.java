package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.ai.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** cost = (uncached input × P_in + cached input × P_cache + output × P_out) / 1e6 × (1 − batch discount) */
@Component
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AiProperties props;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CostCalculator(AiProperties props) {
        this.props = props;
    }

    public BigDecimal cost(TokenUsage u, boolean batch) {
        AiProperties.Price p = props.prices().get(u.model());
        if (p == null) {
            if (warned.add(u.model())) {
                log.warn("No price configured for model {}; cost recorded as 0. Add it under hiretrack.ai.prices.", u.model());
            }
            return BigDecimal.ZERO;
        }
        long uncached = Math.max(0, u.inputTokens() - u.cachedInputTokens());
        BigDecimal total = p.input().multiply(BigDecimal.valueOf(uncached))
                .add(p.cachedInput().multiply(BigDecimal.valueOf(u.cachedInputTokens())))
                .add(p.output().multiply(BigDecimal.valueOf(u.outputTokens())))
                .divide(MILLION, MathContext.DECIMAL64);
        if (batch) {
            total = total.multiply(BigDecimal.ONE.subtract(p.batchDiscount()));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }
}