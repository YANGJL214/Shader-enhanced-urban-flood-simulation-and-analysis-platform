package cn.com.medoxcq.module.warning.module.simulation.service;

import cn.com.medoxcq.common.util.AssertUtils;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
@Service
@RequiredArgsConstructor
public class PublicSimulationAccessService {

  private static final int MAX_SESSION_ID_LENGTH = 50;

  private final ModelSimulationHistoryService historyService;
  private final ObjectMapper objectMapper;

  public void validateSessionId(String sessionId) {
    AssertUtils.isTrue(StrUtil.isBlank(sessionId) || sessionId.length() > MAX_SESSION_ID_LENGTH,
            "匿名会话ID无效");
  }

  public String publicOwnerKey(String sessionId) {
    validateSessionId(sessionId);
    return "public:" + sessionId;
  }

  public ModelSimulationHistoryVO requireOwnedHistory(String id, String sessionId) {
    validateSessionId(sessionId);
    ModelSimulationHistoryVO history = historyService.detail(id);
    AssertUtils.isTrue(!belongsToSession(history, sessionId), "无权访问该匿名模拟记录");
    return history;
  }

  public List<ModelSimulationHistoryVO> filterOwnedHistories(List<ModelSimulationHistoryVO> histories,
                                                              String sessionId) {
    validateSessionId(sessionId);
    return histories.stream()
            .filter(history -> belongsToSession(history, sessionId))
            .collect(Collectors.toList());
  }

  private boolean belongsToSession(ModelSimulationHistoryVO history, String sessionId) {
    if (history == null || StrUtil.isBlank(history.getSimulationParams())) {
      return false;
    }
    try {
      JsonNode params = objectMapper.readTree(history.getSimulationParams());
      return Objects.equals(sessionId, params.path("sessionId").asText(null));
    } catch (Exception ignored) {
      return false;
    }
  }
}
