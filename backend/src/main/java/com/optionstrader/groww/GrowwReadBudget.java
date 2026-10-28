package com.optionstrader.groww;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
/** Shared per-client rolling read budget, with capacity reserved for risk quotes. */
public final class GrowwReadBudget {
    public static final class LimitExceeded extends IllegalStateException {
        private final int retryAfter;
        LimitExceeded(int retryAfter){super("Local broker read budget is busy.");this.retryAfter=retryAfter;}
        public int retryAfter(){return retryAfter;}
    }
    private static final ThreadLocal<Boolean> PRIORITY=ThreadLocal.withInitial(()->false);
    private static final class Window {final Deque<Long> second=new ArrayDeque<>(),minute=new ArrayDeque<>();}
    private final Window live=new Window(),other=new Window();
    private final LongSupplier timer;
    public GrowwReadBudget(){this(System::nanoTime);}
    GrowwReadBudget(LongSupplier timer){this.timer=timer;}
    public static <T>T riskQuote(Supplier<T> operation){
        boolean previous=PRIORITY.get();PRIORITY.set(true);
        try{return operation.get();}finally{if(previous)PRIORITY.set(true);else PRIORITY.remove();}
    }
    public synchronized void acquire(String path){
        boolean market=path.startsWith("/v1/live-data/")||path.startsWith("/v1/option-chain/");
        Window window=market?live:other;long now=timer.getAsLong();
        trim(window.second,now,1_000_000_000L);trim(window.minute,now,60_000_000_000L);
        int second=market?(PRIORITY.get()?10:8):(PRIORITY.get()?20:16);
        int minute=market?(PRIORITY.get()?300:220):(PRIORITY.get()?500:400);
        long wait=0;
        if(window.second.size()>=second)wait=Math.max(wait,1_000_000_000L-(now-window.second.getFirst()));
        if(window.minute.size()>=minute)wait=Math.max(wait,60_000_000_000L-(now-window.minute.getFirst()));
        if(wait>0)throw new LimitExceeded((int)Math.max(1,(wait+999_999_999L)/1_000_000_000L));
        window.second.addLast(now);window.minute.addLast(now);
    }
    private void trim(Deque<Long> values,long now,long duration){
        while(!values.isEmpty()&&now-values.getFirst()>=duration)values.removeFirst();
    }
}
