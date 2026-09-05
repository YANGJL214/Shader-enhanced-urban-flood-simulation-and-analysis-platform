package cn.com.medoxcq.module.warning.module.simulation.pojo.bo;

import cn.com.medoxcq.common.pojo.bo.SortablePageBO;
import cn.com.medoxcq.common.validation.InEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@Setter
@Schema(description = "模型模拟历史表查询BO")
public class ModelSimulationHistoryQueryBO extends SortablePageBO {

  @Schema(description = "模拟名称")
  private String simulationName;

  @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED)
  @InEnum(enumClass = ModelNameEnum.class, message = "模型名称值不正确")
  private ModelNameEnum modelName;

  @Schema(description = "模型状态")
  @InEnum(enumClass = SimulationStatusEnum.class, message = "模型状态值不正确")
  private SimulationStatusEnum state;

  @Schema(description = "保存模拟数据")
  private Boolean saveData;

  @Schema(description = "是否只查询当天模拟历史")
  private Boolean today;

}