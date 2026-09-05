package cn.com.medoxcq.module.warning.module.simulation.controller.publicapi;

import cn.com.medoxcq.common.constant.PageConstants;
import cn.com.medoxcq.common.pojo.CommonResult;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.PublicModelSimulationHistoryQueryBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;
import cn.com.medoxcq.module.warning.module.simulation.service.ModelSimulationHistoryService;
import cn.com.medoxcq.module.warning.module.simulation.service.PublicSimulationAccessService;
import cn.com.medoxcq.module.warning.module.simulation.service.UrbanFloodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@Tag(name = "公共模型模拟历史")
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/simulation/history")
public class PublicModelSimulationHistoryController {

  private final ModelSimulationHistoryService modelSimulationHistoryService;
  private final PublicSimulationAccessService publicAccessService;
  private final UrbanFloodService urbanFloodService;

  @Operation(summary = "公共查询模型模拟历史列表")
  @PostMapping("/list")
  public CommonResult<List<ModelSimulationHistoryVO>> list(@RequestBody PublicModelSimulationHistoryQueryBO bo) {
    bo.setSize(PageConstants.SIZE_NONE);
    List<ModelSimulationHistoryVO> histories = modelSimulationHistoryService.page(bo).getRecords();
    return CommonResult.data(publicAccessService.filterOwnedHistories(histories, bo.getSessionId()));
  }

  @Operation(summary = "公共查询模型模拟历史详情")
  @GetMapping("/detail")
  public CommonResult<ModelSimulationHistoryVO> detail(@RequestParam String id,
                                                       @RequestParam String sessionId) {
    return CommonResult.data(publicAccessService.requireOwnedHistory(id, sessionId));
  }

  @Operation(summary = "公共查询模型模拟历史数据")
  @GetMapping("/data")
  public CommonResult<String> data(@RequestParam String id, @RequestParam String sessionId) {
    publicAccessService.requireOwnedHistory(id, sessionId);
    modelSimulationHistoryService.historyV2(id, sessionId);
    return CommonResult.data("正在发送数据");
  }

  @Operation(summary = "公共终止当前会话的模拟")
  @GetMapping("/stop")
  public CommonResult<String> stop(@RequestParam String id, @RequestParam String sessionId) throws Exception {
    publicAccessService.requireOwnedHistory(id, sessionId);
    urbanFloodService.stopSimulation(id);
    return CommonResult.data("终止成功");
  }
}
