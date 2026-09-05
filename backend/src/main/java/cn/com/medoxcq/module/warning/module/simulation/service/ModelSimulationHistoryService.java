package cn.com.medoxcq.module.warning.module.simulation.service;

import cn.com.medoxcq.common.pojo.vo.CommonPageVO;
import cn.com.medoxcq.module.warning.module.simulation.entity.ModelSimulationHistory;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistoryQueryBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistorySaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;

import java.util.List;

public interface ModelSimulationHistoryService {

    CommonPageVO<ModelSimulationHistoryVO> page(ModelSimulationHistoryQueryBO bo);

    String save(ModelSimulationHistorySaveBO bo);

    void update(ModelSimulationHistorySaveBO bo);

    ModelSimulationHistoryVO detail(String id);

    List<ModelSimulationHistory> queryRunningModel(ModelSimulationHistoryQueryBO bo);

    void historyV2(String id, String sessionId);
}
