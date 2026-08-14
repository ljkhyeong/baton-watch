package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import tools.jackson.databind.ObjectMapper;

/** HTTP 방화벽이 요청을 거부할 때 안정적이고 민감 정보가 제거된 응답을 작성한다. */
public final class MonitorApiRequestRejectedHandler implements RequestRejectedHandler {

    private final ObjectMapper objectMapper;

    public MonitorApiRequestRejectedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestRejectedException requestRejectedException) throws IOException {
        MonitorApiProblemWriter.write(
                objectMapper,
                response,
                HttpStatus.BAD_REQUEST,
                "request-rejected",
                "Request rejected",
                "REQUEST_REJECTED",
                true);
    }
}
