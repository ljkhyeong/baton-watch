package com.personal.baton.watch.bootstrap;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 종료 시작을 작업자에게 알린다. 예약 작업 대기를 켜면 Spring은 컨텍스트 종료 때 스케줄러를 멈추지 않고
 * 인터럽트도 보내지 않아, 빈 소멸 전까지 새 배치가 시작되고 배치는 다음 항목을 계속 점유한다.
 * 이 신호가 세 스케줄러를 함께 닫고 다음 점유를 막으며, 실행 중인 항목은 끝까지 마친다.
 *
 * <p>스케줄러를 늦게 찾아 빈 의존 관계를 만들지 않는다. 의존 관계가 생기면 소멸 순서가 바뀌어
 * 실행 중 항목이 끝나기 전에 점검기와 DB 연결이 닫힐 수 있다.
 */
final class WorkerShutdownSignal implements ApplicationListener<ContextClosedEvent> {

    private final ObjectProvider<ThreadPoolTaskScheduler> schedulers;
    private volatile boolean requested;

    WorkerShutdownSignal(ObjectProvider<ThreadPoolTaskScheduler> schedulers) {
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        requested = true;
        schedulers.forEach(ThreadPoolTaskScheduler::initiateShutdown);
    }

    boolean requested() {
        return requested;
    }
}
