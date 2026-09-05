package cn.com.medoxcq.module.warning.module.simulation.service;

import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSimulationBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 *
 * @author system
 * @since 2025/9/17
 */
public interface UrbanFloodService  {

  /**
   *
   */
    String simulation(UrbanFloodSaveBO bo,
             MultipartFile[] nodeShpFiles,
             MultipartFile[] linkShpFiles,
             MultipartFile ascFile,
             MultipartFile prjFile,
             MultipartFile csvFile,
             MultipartFile landuseFile) throws Exception;
    
  /**
   *
   */
  String simulateWithRegion(UrbanFloodSimulationBO bo, MultipartFile csvFile) throws Exception;  /**
   *
   */
  ModelSimulationHistoryVO getSimulationResult(String simulationId) throws Exception;

  /**
   *
   */
  List<ModelSimulationHistoryVO> getSimulationHistory(String projectName) throws Exception;

  /**
   *
   */
  void stopSimulation(String simulationId) throws Exception;

  /**
   *
   */
  java.util.Map<String, Object> loadHistoryResult(String simulationId) throws Exception;
}
