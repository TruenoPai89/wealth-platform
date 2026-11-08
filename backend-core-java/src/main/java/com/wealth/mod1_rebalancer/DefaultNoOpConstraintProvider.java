package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.spi.RebalanceConstraintProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Walking skeleton: 0 activos bloqueados. */
@Component
public class DefaultNoOpConstraintProvider implements RebalanceConstraintProvider {

    @Override
    public Map<String, BigDecimal> lockedQuantities(Portfolio portfolio) {
        return Map.of();
    }
}
