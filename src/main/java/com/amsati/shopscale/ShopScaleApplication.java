package com.amsati.shopscale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class ShopScaleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopScaleApplication.class, args);
    }
}
