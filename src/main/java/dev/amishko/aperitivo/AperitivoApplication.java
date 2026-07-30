package dev.amishko.aperitivo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "dev.amishko.aperitivo")
@EnableRetry
@EnableAsync
public class AperitivoApplication {

	public static void main(String[] args) {
		SpringApplication.run(AperitivoApplication.class, args);
	}

}
