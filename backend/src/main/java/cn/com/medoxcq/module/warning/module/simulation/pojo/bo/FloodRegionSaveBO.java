package cn.com.medoxcq.module.warning.module.simulation.pojo.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 *
 * @author system
 * @since 2025/12/11
 */
@Getter
@Setter
@Schema(description = "城市洪涝模拟区域初始化BO")
public class FloodRegionSaveBO {

    @Schema(description = "区域ID（更新时传）")
    private String id;

    @Schema(description = "区域名称")
    @NotBlank(message = "区域名称不能为空")
    private String regionName;

    @Schema(description = "区域描述")
    private String description;
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
    private Map<Integer, Double> landuseClassToManning;
    @Schema(description = "土地利用默认曼宁系数，类别缺失时使用")
    private Double landuseDefaultManning;
    @Schema(description = "是否启用下渗参数")
    private Boolean infiltrationEnabled = true;
    @Schema(description = "下渗参数是否严格按土地利用类型匹配")
    private Boolean infiltrationStrictClasses = true;
    @Schema(description = "土地利用类别-下渗参数映射，键为类别编号，值为Horton下渗参数")
    private Map<Integer, InfiltrationParamBO> landuseClassToInfiltration;
    @Schema(description = "土地利用默认下渗参数，类别缺失时使用")
    private InfiltrationParamBO landuseDefaultInfiltration;
    @Schema(description = "TIN网格类型: regular(规则网格) 或 adaptive(自适应不规则网格)，默认adaptive")
    private String tinGridType = "adaptive";
    @Schema(description = "规则网格大小(米)，仅当tinGridType=regular时使用，默认10米")
    private Double regularGridSize = 10.0;
    @Schema(description = "自适应三角网最大三角形面积，单位跟随DEM坐标系，默认300")
    private Double adaptiveMaxTriangleArea = 300.0;
    @Schema(description = "自适应三角网最小角度约束，默认25度")
    private Double adaptiveMinAngle = 25.0;
    @Schema(description = "自适应三角网约束地物列表，hole=true表示挖空，hole=false表示仅作为边缘约束")
    private List<AdaptiveMeshFeatureBO> adaptiveMeshFeatures;
    @Schema(description = "节点字段映射，键为SHP属性名，值为SWMM节点属性名")
    private Map<String, String> nodeFieldMapping;
    @Schema(description = "管段字段映射，键为SHP属性名，值为SWMM管段属性名")
    private Map<String, String> linkFieldMapping;
    @Schema(description = "节点类型映射，键为SHP类型值，值为SWMM节点类型")
    private Map<String, String> nodeTypeMapping;
    @Schema(description = "管段形状映射，键为SHP形状值，值为SWMM管段形状")
    private Map<String, String> shapeTypeMapping;

    @Getter
    @Setter
    @Schema(description = "自适应三角网约束地物配置")
    public static class AdaptiveMeshFeatureBO {
        @Schema(description = "地物类型，例如building/road")
        private String featureType;

        @Schema(description = "是否挖空，true为挖空，false为仅边缘约束")
        private Boolean hole;

        @Schema(description = "该地物Shapefile上传文件名列表")
        private List<String> fileNames;
    }

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
