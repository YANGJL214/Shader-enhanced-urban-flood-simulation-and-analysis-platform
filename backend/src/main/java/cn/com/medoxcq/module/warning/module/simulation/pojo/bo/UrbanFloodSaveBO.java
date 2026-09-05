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
 * @since 2025/9/17
 */
@Getter
@Setter
@Schema(description = "ANUGA-SWMM耦合城市洪涝模拟保存BO")
public class UrbanFloodSaveBO {

  @Schema(description = "项目名称")
  @NotBlank(message = "项目名称不能为空")
  private String projectName;

  @Schema(description = "保存模拟结果")
  @NotNull(message = "保存模拟结果不能为空")
  private Boolean saveData;
  @Schema(description = "模拟持续时间")
  @NotNull(message = "模拟持续时间不能为空")
  private Double simulationDuration;
  @Schema(description = "Manning粗糙度系数")
  @NotNull(message = "Manning粗糙度系数不能为空")
  private Double manningsN;
  @Schema(description = "沉积物浓度")
  @NotNull(message = "沉积物浓度不能为空")
  private Double sedimentConcentration;
  @Schema(description = "植被茎直径")
  @NotNull(message = "植被茎直径不能为空")
  private Double vegetationStemDiameter;
  @Schema(description = "植被茎间距")
  @NotNull(message = "植被茎间距不能为空")
  private Double vegetationStemSpacing;
  @Schema(description = "土地利用类别-曼宁系数映射，键为类别编号，值为系数")
  private java.util.Map<Integer, Double> landuseClassToManning;
  @Schema(description = "土地利用默认曼宁系数，类别缺失时使用")
  private Double landuseDefaultManning;
  @Schema(description = "是否启用下渗参数")
  private Boolean infiltrationEnabled = true;
  @Schema(description = "下渗参数是否严格按土地利用类型匹配")
  private Boolean infiltrationStrictClasses = true;
  @Schema(description = "土地利用类别-下渗参数映射，键为类别编号，值为Horton下渗参数")
  private java.util.Map<Integer, InfiltrationParamBO> landuseClassToInfiltration;
  @Schema(description = "土地利用默认下渗参数，类别缺失时使用")
  private InfiltrationParamBO landuseDefaultInfiltration;
  @Schema(description = "节点字段映射：key为内部字段(name/elevation/node_type/...)，value为Shapefile属性名")
  private Map<String, String> nodeFieldMapping;
  @Schema(description = "管线字段映射：key为内部字段(name/from_node/...)，value为Shapefile属性名")
  private Map<String, String> linkFieldMapping;
  @Schema(description = "节点类型映射：key为Shapefile节点类型，value为SWMM节点类型(JUNCTION/OUTFALL/INLET)")
  private Map<String, String> nodeTypeMapping;
  @Schema(description = "断面形状映射：key为Shapefile形状描述，value为SWMM形状(CIRCULAR/RECT_CLOSED等)")
  private Map<String, String> shapeTypeMapping;

  public Map<String, String> getNodeFieldMapping() {
    return nodeFieldMapping;
  }

  public Map<String, String> getLinkFieldMapping() {
    return linkFieldMapping;
  }

  public Map<String, String> getNodeTypeMapping() {
    return nodeTypeMapping;
  }

  public Map<String, String> getShapeTypeMapping() {
    return shapeTypeMapping;
  }

  @Schema(description = "会话ID")
  @NotBlank(message = "会话ID不能为空")
  private String sessionId;

  @Getter
  @Setter
  @Schema(description = "Horton下渗参数")
  public static class InfiltrationParamBO {
    @Schema(description = "最大下渗率(mm/hr)")
    private Double maxRate;

    @Schema(description = "最小下渗率(mm/hr)")
    private Double minRate;

    @Schema(description = "衰减系数(1/hr)")
    private Double decay;

    @Schema(description = "干燥时间(days)")
    private Double dryingTimeDays;

    @Schema(description = "最大入渗量(mm)")
    private Double maxInfiltration;
  }

}
