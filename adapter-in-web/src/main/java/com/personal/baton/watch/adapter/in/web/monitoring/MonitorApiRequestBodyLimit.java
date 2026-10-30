package com.personal.baton.watch.adapter.in.web.monitoring;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * 모니터 API 요청 본문을 Jackson 객체화 전에 제한된 크기로 읽는다.
 *
 * <p>보안 필터 체인의 인증, 핸들러 선택(경로·메서드·Content-Type·Accept)과 경로 변수 변환 뒤에
 * 판정한다. 빈 본문에는 호출되지 않는다.
 */
@ControllerAdvice(assignableTypes = ResourceMonitorController.class)
public final class MonitorApiRequestBodyLimit extends RequestBodyAdviceAdapter {

    // 최대 2,048자 URL을 모든 문자가 JSON Unicode escape인 경우에도 수용할 여유를 둔다.
    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;

    @Override
    public boolean supports(
            MethodParameter methodParameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        HttpHeaders headers = inputMessage.getHeaders();
        if (headers.getContentLength() > MAX_REQUEST_BODY_BYTES) {
            throw MonitorApiException.payloadTooLarge();
        }
        byte[] body = inputMessage.getBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            throw MonitorApiException.payloadTooLarge();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
