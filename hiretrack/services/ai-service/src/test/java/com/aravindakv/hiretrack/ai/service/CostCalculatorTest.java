package com.aravindakv.hiretrack.ai.service;

import com.aravindakv.hiretrack.ai.config.AiProperties;
import com.aravindakv.hiretrack.ai.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    private final AiProperties props = new AiProperties("openai", Duration.ofSeconds(7), 1, Duration.ofSeconds(60),
            20, false, null, null, null, Map.of("m", new AiProperties.Price(
                    new BigDecimal("2.00"), new BigDecimal("0.50"), new BigDecimal("8.00"), new BigDecimal("0.5"))), null);
    private final CostCalculator calc = new CostCalculator(props);

    @Test
    void realTimeCostUsesCachedAndUncachedRates() {
        // 4,000 uncached × 2 + 1,000 cached × 0.5 + 1,000 output × 8 = 16,500 per million → 0.0165
        assertThat(calc.cost(new TokenUsage("m", 5000, 1000, 1000), false)).isEqualByComparingTo("0.016500");
    }

    @Test
    void batchCostAppliesTheDiscount() {
        assertThat(calc.cost(new TokenUsage("m", 5000, 1000, 1000), true)).isEqualByComparingTo("0.008250");
    }

    @Test
    void unknownModelCostsZeroInsteadOfFailing() {
        assertThat(calc.cost(new TokenUsage("unpriced", 5000, 0, 1000), false)).isEqualByComparingTo("0");
    }
}