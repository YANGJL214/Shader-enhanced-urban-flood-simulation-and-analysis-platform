package cn.com.medoxcq;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("cn.com.medoxcq.module.warning.module.simulation.mapper")
public class UrbanFloodApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrbanFloodApplication.class, args);
    }
}
