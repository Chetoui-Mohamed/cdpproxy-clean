package com.cdpproxy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.cdpproxy.proxy.WebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Value("${websocket.max.text.buffer.size:5242880}")  // 5MB default
    private Integer maxTextBufferSize;

    @Value("${websocket.max.binary.buffer.size:5242880}")  // 5MB default
    private Integer maxBinaryBufferSize;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Main CDP proxy endpoint
        registry.addHandler(cdpWebSocketHandler(), "/cdp")
                .setAllowedOrigins("*");


    }

    @Bean
    public WebSocketHandler cdpWebSocketHandler() {
        return new WebSocketHandler();
    }



    /**
     * Configure WebSocket container to handle large messages
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextBufferSize);
        container.setMaxBinaryMessageBufferSize(maxBinaryBufferSize);
        // Increase session idle timeout (in milliseconds)
        container.setMaxSessionIdleTimeout(120000L);
        return container;
    }
}