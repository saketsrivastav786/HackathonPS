package com.hacksys.backend.config;

import com.hacksys.backend.service.InventoryService;
import com.hacksys.backend.service.OrderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

@Configuration
public class AppConfig {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    public AppConfig(OrderService orderService, InventoryService inventoryService) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
    }

    /**
     * Break circular dependency: OrderService ↔ InventoryService.
     * Injected via setter post-construction.
     */
    @PostConstruct
    public void wireServices() {
        orderService.setInventoryService(inventoryService);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
