package com.optionstrader.platform;
import java.util.Map;
/** Future broker execution boundary. There is deliberately no live adapter enabled. */
public interface BrokerOrderPort {
    Map<String,Object> capabilities();
    default Object submit(Object command){throw new UnsupportedOperationException("Live execution is unavailable; use the paper intent engine.");}
    default Object modify(Object command){throw new UnsupportedOperationException("Live order modification is unavailable.");}
    default Object cancel(Object command){throw new UnsupportedOperationException("Live order cancellation is unavailable.");}
    default Object reconcile(String reference){throw new UnsupportedOperationException("Live reconciliation requires a reviewed broker adapter.");}
}
