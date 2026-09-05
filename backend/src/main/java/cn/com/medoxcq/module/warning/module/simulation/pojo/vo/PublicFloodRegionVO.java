package cn.com.medoxcq.module.warning.module.simulation.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
@Getter
@Setter
@Schema(description = "匿名公开页城市洪涝区域")
public class PublicFloodRegionVO {

  @Schema(description = "区域ID")
  private String id;

  @Schema(description = "区域名称")
  private String regionName;

  @Schema(description = "区域描述")
  private String description;

  @Schema(description = "初始化状态")
  private Integer initStatus;

  @Schema(description = "初始化错误信息")
  private String errorMessage;

  @Schema(description = "创建时间")
  private LocalDateTime createTime;

  @Schema(description = "更新时间")
  private LocalDateTime updateTime;
}
