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
 * @since 2025/7/2
 */
@Getter
@FieldNameConstants
@RequiredArgsConstructor
public enum SimulationStatusEnum implements BaseEnum {

  FAILED(0, "模拟失败"),

  SUCCEED(1, "模拟成功"),

  RUNNING(2, "正在模拟");

  @EnumValue
  @JsonValue
  private final Integer code;

  private final String desc;

}
