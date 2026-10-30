package com.personal.baton.watch.adapter.out.external.http;

import java.time.Duration;

/** 요청 범위 Apache 클라이언트 하나에 적용하는 연결 및 응답 제한. 구현체가 생성 시 검증한다. */
public interface ApacheHttpClientLimits {

    Duration connectTimeout();

    Duration responseTimeout();

    int maxHeaderCount();

    int maxHeaderLineLength();
}
