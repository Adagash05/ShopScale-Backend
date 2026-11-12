package com.amsati.shopscale.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Every endpoint that returns a signed-in session (login, register, switch business, onboarding, invitation accept,
 * refresh) hands the refresh token to the browser as an HttpOnly cookie and removes it from the JSON body.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class RefreshCookieAdvice implements ResponseBodyAdvice<Object> {
    private final RefreshCookies cookies;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return AuthResponse.class.equals(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof AuthResponse a && a.refreshToken() != null) {
            response.getHeaders().add(HttpHeaders.SET_COOKIE, cookies.issue(a.refreshToken()).toString());
            return a.withoutRefreshToken();
        }
        return body;
    }
}
