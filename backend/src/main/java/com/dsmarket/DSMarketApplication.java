package com.dsmarket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.dsmarket.**.mapper")
public class DSMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(DSMarketApplication.class, args);
    }
}
