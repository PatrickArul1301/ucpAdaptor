package com.accenture.UCPAdaptor;

import com.accenture.UCPAdaptor.config.UcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(UcpProperties.class)
public class UcpAdaptorApplication {

	public static void main(String[] args) {
		SpringApplication.run(UcpAdaptorApplication.class, args);
	}

}
