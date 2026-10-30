package com.personal.baton.watch.bootstrap;

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
}
