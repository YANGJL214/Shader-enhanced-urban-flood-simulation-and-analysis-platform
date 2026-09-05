package cn.com.medoxcq.module.warning.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 *
 * @author zc
 * @since 2025/2/20
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "simulation")
public class SimulationConfiguration {
  private String osName;
  private String trigrsModel;
  private String trigrsTopoModel;
  private String lisfloodModel;
  private String avaflowInputPath;
  private String avaflowStartPath;
  private String avaflowOutputPath;
  private String avaflowScriptPath;
  private String rothermelInputPath;
  private String rothermelStartPath;
  private String rothermelOutputPath;
  private String rothermelScriptPath;
  private String grassDataPath;

  private String urbanFloodModel;
  private String urbanFloodWorkspace;
  private String urbanFloodTemplatePath;
  private String urbanFloodRegionRoot;
  private String pythonExecutable;

  private String wslPrefix;

  private String debrisBase;

  private String fireBase;

}
