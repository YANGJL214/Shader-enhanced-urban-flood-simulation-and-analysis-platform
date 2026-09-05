package cn.com.medoxcq.module.warning.module.simulation.pojo.vo;

import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 *
 * @author zc
 * @since 2025/7/2
 */
@Getter
@Setter
@Schema(description = "模型模拟历史表VO")
public class ModelSimulationHistoryVO {
    @TableId("id")
    @Schema(description = "主键Id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String id;
    @Schema(description = "模拟名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String simulationName;
    @Schema(description = "模型名称")
    private ModelNameEnum modelName;
    @Schema(description = "模拟状态")
    private SimulationStatusEnum state;
    @Schema(description = "模拟参数")
    private String simulationParams;
    @Schema(description = "进程ID")
    private String processId;

    @Schema(description = "保存模拟数据")
    private Boolean saveData;
    @Schema(description = "工作空间路径")
    private String workspacePath;
    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
