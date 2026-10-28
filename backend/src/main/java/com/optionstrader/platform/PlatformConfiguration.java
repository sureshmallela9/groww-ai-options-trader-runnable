package com.optionstrader.platform;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration
public class PlatformConfiguration {
    @Bean public Clock tradingPlatformClock(){return Clock.systemUTC();}
    @Bean(name="taskScheduler") public ThreadPoolTaskScheduler independentSchedulers(){
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("market-risk-events-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
