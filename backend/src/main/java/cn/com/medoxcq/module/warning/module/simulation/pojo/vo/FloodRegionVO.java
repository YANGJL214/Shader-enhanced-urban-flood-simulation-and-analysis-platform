package cn.com.medoxcq.module.warning.module.simulation.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 *
 * @author system
 * @since 2025/12/11
 */
@Getter
@Setter
@Schema(description = "城市洪涝模拟区域VO")
public class FloodRegionVO {

    @Schema(description = "区域ID")
    private String id;

    @Schema(description = "区域名称")
    private String regionName;

    @Schema(description = "区域描述")
    private String description;

    @Schema(description = "区域静态数据存储路径")
    private String dataPath;

    @Schema(description = "TIN网格文件路径")
    private String tinFilePath;

    @Schema(description = "DEM文件路径")
    private String demFilePath;

    @Schema(description = "管点Shapefile路径")
    private String nodeShpPath;

    @Schema(description = "管线Shapefile路径")
    private String linkShpPath;

    @Schema(description = "土地利用文件路径")
    private String landuseFilePath;

    @Schema(description = "区域配置参数")
    private Map<String, Object> configParams;

    @Schema(description = "初始化状态：0-未初始化，1-初始化中，2-初始化完成，3-初始化失败")
    private Integer initStatus;

    @Schema(description = "初始化错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
