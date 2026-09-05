package cn.com.medoxcq.module.warning.module.simulation.pojo.bo;

import cn.com.medoxcq.common.validation.InEnum;
import cn.com.medoxcq.common.validation.group.UpdateGroup;
import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

/**
 *
 * @author zc
 * @since 2025/7/2
 */
@Getter
@Setter
@Schema(description = "模型模拟历史表保存BO")
public class ModelSimulationHistorySaveBO {
    @Schema(description = "主键Id")
    @NotBlank(message = "主键Id不能为空", groups = {UpdateGroup.class})
    private String id;
    @Schema(description = "模拟名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "模拟名称不能为空")
    private String simulationName;

    @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @InEnum(enumClass = ModelNameEnum.class, message = "模型名称值不正确")
    private Integer modelName;
    @Schema(description = "模拟状态")
    @InEnum(enumClass = SimulationStatusEnum.class, message = "模拟状态值不正确")
    private Integer state;
    @Schema(description = "模拟参数")
    private String simulationParams;
    @Schema(description = "进程ID")
    private String processId;

    @Schema(description = "保存模拟数据")
    private Boolean saveData;
    @Schema(description = "工作空间路径")
    private String workspacePath;

}