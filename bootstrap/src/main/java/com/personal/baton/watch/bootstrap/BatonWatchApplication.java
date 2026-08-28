package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.domain.system.SystemStatus;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.personal.baton.watch")
@EnableScheduling
@EnableConfigurationProperties({
        WatchProperties.class,
        EventDeliveryProperties.class
})
public class BatonWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatonWatchApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    GetSystemStatusUseCase getSystemStatusUseCase(Clock clock) {
        return () -> new SystemStatus("baton-watch", SystemStatus.State.UP, clock.instant());
    }
}
