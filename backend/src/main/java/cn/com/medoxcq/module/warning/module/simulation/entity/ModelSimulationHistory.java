package cn.com.medoxcq.module.warning.module.simulation.entity;

import cn.com.medoxcq.common.mybatis.core.dataobject.BaseDO;
import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;


/**
 *
 * @author zc
 * @since 2025/7/2
 */
@Getter
@Setter
@FieldNameConstants
@TableName(value = "model_simulation_history", autoResultMap = true)
public class ModelSimulationHistory extends BaseDO {
    @TableId("id")
    private String id;
    @TableField("simulation_name")
    private String simulationName;
    @TableField("model_name")
    private ModelNameEnum modelName;
    @TableField("state")
    private SimulationStatusEnum state;
    @TableField("simulation_params")
    private String simulationParams;
    @TableField("process_id")
    private String processId;
    @TableField("save_data")
    private Boolean saveData;
    @TableField("workspace_path")
    private String workspacePath;

}