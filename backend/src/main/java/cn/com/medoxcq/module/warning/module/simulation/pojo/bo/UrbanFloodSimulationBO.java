package cn.com.medoxcq.module.warning.module.simulation.pojo.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 *
 * @author system
 * @since 2025/12/11
 */
@Getter
@Setter
@Schema(description = "城市洪涝模拟执行BO")
public class UrbanFloodSimulationBO {

    @Schema(description = "区域ID")
    @NotBlank(message = "区域ID不能为空")
    private String regionId;

    @Schema(description = "模拟名称")
    @NotBlank(message = "模拟名称不能为空")
    private String simulationName;

    @Schema(description = "保存模拟结果")
    @NotNull(message = "保存模拟结果不能为空")
    private Boolean saveData;
    @Schema(description = "模拟持续时间（分钟）")
    @NotNull(message = "模拟持续时间不能为空")
    private Double simulationDuration;
    @Schema(description = "节点字段映射：key为内部字段(name/elevation/node_type/...)，value为Shapefile属性名")
    private Map<String, String> nodeFieldMapping;
    @Schema(description = "管线字段映射：key为内部字段(name/from_node/...)，value为Shapefile属性名")
    private Map<String, String> linkFieldMapping;
    @Schema(description = "节点类型映射：key为Shapefile节点类型，value为SWMM节点类型(JUNCTION/OUTFALL/INLET)")
    private Map<String, String> nodeTypeMapping;
    @Schema(description = "断面形状映射：key为Shapefile形状描述，value为SWMM形状(CIRCULAR/RECT_CLOSED等)")
    private Map<String, String> shapeTypeMapping;

    @Schema(description = "会话ID")
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;
}
