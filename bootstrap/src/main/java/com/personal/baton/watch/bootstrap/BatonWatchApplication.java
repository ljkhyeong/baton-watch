package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.application.system.service.GetSystemStatusService;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.personal.baton.watch")
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
        return new GetSystemStatusService(clock);
    }
}

