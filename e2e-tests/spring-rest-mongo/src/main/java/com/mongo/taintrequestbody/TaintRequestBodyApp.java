package com.mongo.taintrequestbody;

import com.mongo.SwaggerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@EnableSwagger2
@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
public class TaintRequestBodyApp extends SwaggerConfiguration {
    public TaintRequestBodyApp() {
        super("taintrequestbody");
    }

    public static void main(String[] args) {
        SpringApplication.run(TaintRequestBodyApp.class, args);
    }

}