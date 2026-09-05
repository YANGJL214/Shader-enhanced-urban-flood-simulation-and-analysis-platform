package cn.com.medoxcq.module.warning.module.simulation.pojo.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
@Getter
@Setter
@Schema(description = "匿名公开页模型历史查询参数")
public class PublicModelSimulationHistoryQueryBO extends ModelSimulationHistoryQueryBO {

  @Schema(description = "匿名会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
  @NotBlank(message = "匿名会话ID不能为空")
  private String sessionId;
}
