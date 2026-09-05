package cn.com.medoxcq.module.warning.module.simulation.controller.publicapi;

import cn.com.medoxcq.common.pojo.CommonResult;
import cn.com.medoxcq.module.warning.module.simulation.service.PublicSimulationAccessService;
import cn.com.medoxcq.module.warning.module.simulation.service.UrbanFloodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
@Tag(name = "公共城市洪涝模拟")
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/urban_flood")
public class PublicUrbanFloodController {

  private final UrbanFloodService urbanFloodService;
  private final PublicSimulationAccessService publicAccessService;

  @Operation(summary = "公共加载城市洪涝历史结果")
  @GetMapping("/load_history_result")
  public CommonResult<Map<String, Object>> loadHistoryResult(@RequestParam String id,
                                                             @RequestParam String sessionId) throws Exception {
    publicAccessService.requireOwnedHistory(id, sessionId);
    return CommonResult.data(urbanFloodService.loadHistoryResult(id));
  }
}
