package cn.com.medoxcq.module.warning.module.simulation.enums;

import cn.com.medoxcq.common.enums.BaseEnum;
import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldNameConstants;

/**
 *
 * @author zc
 * @since 2025/7/3
 */
@Getter
@FieldNameConstants
@RequiredArgsConstructor
public enum ModelNameEnum implements BaseEnum {

  LISFLOOD(0, "流域洪水模型"),

  AVAFLOW(1, "滑坡动力学模型"),

  TRIGRS(2, "滑坡风险评价模型"),

  ROTHERMEL(3, "火灾模型"),

  OUTBURST(4, "溃决洪水"),

  ANUGA_SWMM(5, "城市洪涝模型");

  @EnumValue
  @JsonValue
  private final Integer code;

  private final String desc;

}
